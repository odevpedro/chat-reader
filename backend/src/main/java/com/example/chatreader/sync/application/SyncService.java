package com.example.chatreader.sync.application;

import com.example.chatreader.bookmark.application.BookmarkService;
import com.example.chatreader.chat.application.ChatService;
import com.example.chatreader.shared.ConflictException;
import com.example.chatreader.shared.NotFoundException;
import com.example.chatreader.sync.domain.AckResult;
import com.example.chatreader.sync.domain.ChangeLog;
import com.example.chatreader.sync.domain.LocalChange;
import com.example.chatreader.sync.domain.SyncChange;
import com.example.chatreader.sync.domain.SyncDelta;
import com.example.chatreader.sync.domain.SyncSnapshot;
import com.example.chatreader.tag.application.TagService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Delta sync e ACK (secoes 15/16/17; docs/sync-protocol.md).
 *
 * <p>Puro pull: o Kindle nunca recebe push. O backend responde "o que mudou
 * depois do seu token" e o cliente decide quando puxar.
 */
@Service
public class SyncService {

    private final ChangeLog changeLog;
    private final ChatService chatService;
    private final BookmarkService bookmarkService;
    private final TagService tagService;
    private final SyncProperties properties;

    public SyncService(ChangeLog changeLog, ChatService chatService, BookmarkService bookmarkService,
                       TagService tagService, SyncProperties properties) {
        this.changeLog = changeLog;
        this.chatService = chatService;
        this.bookmarkService = bookmarkService;
        this.tagService = tagService;
        this.properties = properties;
    }

    /**
     * Mudancas posteriores a {@code since}. Retencao e aplicada aqui: e a unica
     * janela em que o servidor faz escrita durante um sync, e acontece uma vez
     * por requisicao com indice por {@code created_at}.
     */
    public SyncDelta pull(long since, int limit) {
        int safeLimit = clampLimit(limit);
        prune();

        var oldest = changeLog.oldestRetainedVersion();
        var current = changeLog.currentVersion();

        if (requiresFullResync(since, oldest)) {
            return new SyncDelta(current, false, true, Instant.now(), List.of());
        }

        // Pedimos limit + 1 para saber se ha pagina seguinte sem contar duas vezes.
        var page = changeLog.findSince(since, safeLimit + 1);
        boolean hasMore = page.size() > safeLimit;
        List<SyncChange> changes = hasMore ? List.copyOf(page.subList(0, safeLimit)) : List.copyOf(page);

        // O token devolvido e a ultima mudanca desta pagina; sem mudancas, o
        // cliente mantem o token que ja tem.
        long version = changes.isEmpty() ? Math.max(since, 0L)
                : changes.get(changes.size() - 1).version();
        return new SyncDelta(version, hasMore, false, Instant.now(), changes);
    }

    /**
     * Retencao: 30 dias <b>ou</b> o teto de linhas, o que vier primeiro
     * (docs/sync-protocol.md, secao 9). Roda uma vez por requisicao, na unica
     * janela em que o servidor escreve durante um sync.
     */
    private void prune() {
        changeLog.deleteOlderThan(Instant.now().minus(properties.retentionDays(), ChronoUnit.DAYS));
        changeLog.keepLatest(properties.maxLogRows());
    }

    /**
     * Full snapshot paginado por chat (docs/sync-protocol.md, secao 4.4).
     *
     * <p>A versao e reservada <b>antes</b> dos dados: qualquer mudanca que caia
     * durante a leitura fica acima do token devolvido e volta no proximo delta, em
     * vez de sumir no meio do snapshot. Como remocoes sao tombstones, o cliente
     * consegue reconstruir o estado.
     */
    public SyncSnapshot snapshot(int page, int size) {
        int safeSize = size <= 0 ? properties.snapshotDefaultPageSize() : size;
        var syncVersion = changeLog.reserveVersion();

        var total = chatService.count(null, null);
        var summaries = chatService.list(page, safeSize, null, null);
        // conversa dentro do limite vai com as mensagens; acima do limite so
        // metadados, e o cliente pagina sob demanda (secao 9)
        var chats = summaries.stream()
                .map(summary -> summary.messageCount() > properties.maxMessagesPerChange()
                        ? summary
                        : chatService.get(summary.id()))
                .toList();
        var last = (long) (page + 1) * safeSize >= total;

        var maxBookmarks = properties.maxBookmarksInSnapshot();
        var bookmarks = bookmarkService.list(null, 0, maxBookmarks);
        var bookmarksOmitted = bookmarkService.count(null) > bookmarks.size();

        return new SyncSnapshot(syncVersion, page, safeSize, total, last,
                tagService.all(), chats, bookmarks, bookmarksOmitted);    }
    /**
     * Quando o delta nao entrega o estado inteiro, o cliente tem de fazer
     * snapshot (docs/sync-protocol.md, secao 4.4).
     *
     * <p>Sem token ({@code since <= 0}) o servidor sempre pede snapshot: o delta
     * so valeria se o log guardasse <b>toda</b> a historia, e a retencao de 30 dias
     * nao garante isso. Sem esse cuidado, um cliente novo receberia
     * {@code changes: []} e mostraria uma biblioteca vazia.
     *
     * <p>Com token, ha um buraco apenas quando o token e anterior a janela
     * retida ({@code since < oldest - 1}). Log vazio nao prova buraco: as linhas
     * podem ter sido podadas depois de aplicadas pelo cliente. A lacuna residual
     * (cliente mais offline que a retencao) e tratada no cliente, que refaz o
     * snapshot quando o intervalo passa de {@code retentionDays} — limitacao
     * registrada em docs/sync-protocol.md, secao 9.
     */
    private static boolean requiresFullResync(long since, long oldest) {
        if (since <= 0) {
            return true;
        }
        if (oldest <= 0) {
            return false;
        }
        return since < oldest - 1;
    }

    /**
     * Aplica as escritas que o Kindle fez offline. Cada mudanca e independente:
     * uma recusa nao desfaz as demais, e o cliente so remove o item da fila
     * pendente quando o indice nao aparece em {@code rejections}.
     */
    public AckResult ack(long ackVersion, List<LocalChange> localChanges) {
        var changes = localChanges == null ? List.<LocalChange>of() : localChanges;
        var rejections = new ArrayList<AckResult.Rejection>();
        int accepted = 0;

        for (int index = 0; index < changes.size(); index++) {
            try {
                apply(changes.get(index));
                accepted++;
            } catch (RuntimeException e) {
                rejections.add(new AckResult.Rejection(index, reasonFor(e), e.getMessage()));
            }
        }

        return new AckResult(ackVersion, changeLog.currentVersion(), accepted, rejections);
    }

    private void apply(LocalChange change) {
        switch (change) {
            case LocalChange.BookmarkCreate create -> bookmarkService.createFromClient(
                    create.id(), create.chatId(), create.messageId(), create.note());
            case LocalChange.BookmarkDelete delete -> bookmarkService.deleteFromClient(delete.id());
            case LocalChange.ChatFavorite favorite -> chatService.setFavorite(
                    favorite.chatId(), favorite.favorite());
            case LocalChange.ContentWrite write -> throw new ConflictException(
                    "O conteudo da conversa e controlado pelo backend; recusado: " + write.motivo());
            default -> throw new IllegalArgumentException("Tipo de mudanca local desconhecido");
        }
    }

    private static String reasonFor(RuntimeException e) {
        if (e instanceof NotFoundException) {
            return "NOT_FOUND";
        }
        if (e instanceof ConflictException) {
            return "CONFLICT";
        }
        if (e instanceof IllegalArgumentException) {
            return "INVALID";
        }
        return "REJECTED";
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return properties.defaultLimit();
        }
        return Math.min(limit, properties.maxLimit());
    }
}
