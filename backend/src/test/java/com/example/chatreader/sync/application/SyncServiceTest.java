package com.example.chatreader.sync.application;

import com.example.chatreader.bookmark.application.BookmarkService;
import com.example.chatreader.chat.application.ChatService;
import com.example.chatreader.shared.ConflictException;
import com.example.chatreader.shared.NotFoundException;
import com.example.chatreader.sync.domain.ChangeLog;
import com.example.chatreader.sync.domain.ChangeType;
import com.example.chatreader.sync.domain.LocalChange;
import com.example.chatreader.sync.domain.SyncChange;
import com.example.chatreader.tag.application.TagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SyncService — delta, paginacao, retencao e ACK (secao 15/16)")
class SyncServiceTest {

    private final FakeChangeLog changeLog = new FakeChangeLog();
    private final ChatService chatService = mock(ChatService.class);
    private final BookmarkService bookmarkService = mock(BookmarkService.class);
    private final TagService tagService = mock(TagService.class);
    private final SyncService service = new SyncService(changeLog, chatService, bookmarkService,
            tagService, new SyncProperties(200, 1000, 30, 200, 50, 1000, 1_000_000));

    @Test
    @DisplayName("cliente sem token e mandado para o snapshot")
    void clientWithoutTokenGetsFullResync() {
        changeLog.append(ChangeType.CHAT_CREATED, "c-1", "c-1", 1L);
        changeLog.append(ChangeType.CHAT_CREATED, "c-2", "c-2", 1L);

        var delta = service.pull(0, 0);

        // o delta desde a versao 1 so valeria se o log guardasse toda a historia,
        // e a retencao de 30 dias nao garante isso
        assertThat(delta.fullResyncRequired()).isTrue();
        assertThat(delta.changes()).isEmpty();
        assertThat(delta.syncVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("delta devolve exatamente o que veio depois do token do cliente")
    void deltaReturnsChangesAfterClientToken() {
        changeLog.append(ChangeType.CHAT_CREATED, "c-1", "c-1", 1L);
        changeLog.append(ChangeType.CHAT_UPDATED, "c-1", "c-1", 2L);
        changeLog.append(ChangeType.CHAT_UPDATED, "c-1", "c-1", 3L);

        var delta = service.pull(1, 0);

        assertThat(delta.changes()).extracting(SyncChange::type)
                .containsExactly(ChangeType.CHAT_UPDATED, ChangeType.CHAT_UPDATED);
        assertThat(delta.syncVersion()).isEqualTo(3);
        assertThat(delta.hasMore()).isFalse();
        assertThat(delta.fullResyncRequired()).isFalse();
    }

    @Test
    @DisplayName("since igual ao token do cliente devolve so o que veio depois")
    void incrementalSyncReturnsOnlyNewChanges() {
        changeLog.append(ChangeType.CHAT_CREATED, "c-1", "c-1", 1L);
        changeLog.append(ChangeType.CHAT_UPDATED, "c-1", "c-1", 2L);

        var delta = service.pull(1, 0);

        assertThat(delta.changes()).extracting(SyncChange::type)
                .containsExactly(ChangeType.CHAT_UPDATED);
        assertThat(delta.syncVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("limit corta a pagina e sinaliza hasMore")
    void limitTruncatesPageAndSignalsMore() {
        changeLog.append(ChangeType.CHAT_CREATED, "c-1", "c-1", 1L);
        changeLog.append(ChangeType.CHAT_UPDATED, "c-1", "c-1", 2L);
        changeLog.append(ChangeType.CHAT_UPDATED, "c-1", "c-1", 3L);
        changeLog.append(ChangeType.CHAT_UPDATED, "c-1", "c-1", 4L);

        var first = service.pull(1, 2);
        assertThat(first.changes()).extracting(SyncChange::version).containsExactly(2L, 3L);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.syncVersion()).isEqualTo(3);

        var second = service.pull(first.syncVersion(), 2);
        assertThat(second.changes()).extracting(SyncChange::version).containsExactly(4L);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.syncVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("limit acima do teto e reduzida para maxLimit")
    void limitIsCapped() {
        changeLog.append(ChangeType.CHAT_CREATED, "c-1", "c-1", 1L);
        var capped = new SyncService(changeLog, chatService, bookmarkService, tagService,
                new SyncProperties(200, 3, 30, 200, 50, 1000, 1_000_000));

        for (int i = 0; i < 5; i++) {
            changeLog.append(ChangeType.CHAT_UPDATED, "c-1", "c-1", (long) i);
        }

        assertThat(capped.pull(1, 9999).changes()).hasSize(3);
    }

    @Test
    @DisplayName("token anterior ao log retido pede full resync")
    void staleTokenRequiresFullResync() {
        changeLog.append(ChangeType.CHAT_CREATED, "c-1", "c-1", 1L);
        changeLog.append(ChangeType.CHAT_CREATED, "c-2", "c-2", 1L);
        changeLog.append(ChangeType.CHAT_CREATED, "c-3", "c-3", 1L);
        changeLog.keepLatest(1); // janela podada: sobrou so a versao 3

        // token 1 perdeu a versao 2, que nao esta mais no log
        var stale = service.pull(1, 0);
        assertThat(stale.fullResyncRequired()).isTrue();
        assertThat(stale.changes()).isEmpty();

        // token contiguo com o que sobrou (2) nao perde nada
        assertThat(service.pull(2, 0).fullResyncRequired()).isFalse();
    }

    @Test
    @DisplayName("log vazio com token guardado nao força full resync")
    void emptyLogWithTokenDoesNotForceResync() {
        // as linhas podem ter sido podadas *depois* de aplicadas pelo cliente:
        // log vazio nao prova buraco (limitacao da secao 9, tratada no cliente)
        var delta = service.pull(42, 0);

        assertThat(delta.fullResyncRequired()).isFalse();
        assertThat(delta.changes()).isEmpty();
    }

    @Test
    @DisplayName("sem mudancas o cliente mantem o token que ja tinha")
    void noChangesKeepsClientToken() {
        changeLog.append(ChangeType.CHAT_CREATED, "c-1", "c-1", 1L);

        var delta = service.pull(1, 0);

        assertThat(delta.changes()).isEmpty();
        assertThat(delta.syncVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("ACK aplica cada escrita e nao desfaz as demais quando uma falha")
    void ackAppliesEachChangeIndependently() {
        when(bookmarkService.createFromClient("b-2", "c-1", "m-9", null))
                .thenThrow(new NotFoundException("Mensagem nao encontrada no chat c-1: m-9"));

        var result = service.ack(7, List.of(
                new LocalChange.BookmarkCreate("b-1", "c-1", "m-1", "nota"),
                new LocalChange.BookmarkCreate("b-2", "c-1", "m-9", null),
                new LocalChange.BookmarkDelete("b-3"),
                new LocalChange.ChatFavorite("c-1", true)));

        assertThat(result.ackVersion()).isEqualTo(7);
        assertThat(result.accepted()).isEqualTo(3);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.rejections()).singleElement().satisfies(rejection -> {
            assertThat(rejection.index()).isEqualTo(1);
            assertThat(rejection.reason()).isEqualTo("NOT_FOUND");
        });

        verify(bookmarkService).createFromClient("b-1", "c-1", "m-1", "nota");
        verify(bookmarkService).deleteFromClient("b-3");
        verify(chatService).setFavorite("c-1", true);
    }

    @Test
    @DisplayName("escrita de conteudo e recusada com CONFLICT e nao toca no chat")
    void contentWriteIsRejected() {
        var result = service.ack(1, List.of(
                new LocalChange.ContentWrite("c-1", "Kindle tentou reescrever mensagens")));

        assertThat(result.accepted()).isZero();
        assertThat(result.rejections()).singleElement().satisfies(rejection -> {
            assertThat(rejection.index()).isZero();
            assertThat(rejection.reason()).isEqualTo("CONFLICT");
        });
    }

    @Test
    @DisplayName("ACK lista vazia e aceito e devolve a versao atual")
    void emptyAckIsAccepted() {
        changeLog.append(ChangeType.CHAT_CREATED, "c-1", "c-1", 1L);

        var result = service.ack(1, null);

        assertThat(result.accepted()).isZero();
        assertThat(result.newSyncVersion()).isEqualTo(1);
    }

    /** Change log em memoria com as mesmas garantias do Postgres (versao crescente). */
    private static class FakeChangeLog implements ChangeLog {

        private final List<SyncChange> entries = new ArrayList<>();
        /** Maior versao consumida; nunca diminui, como a sequence do BIGSERIAL. */
        private long watermark;

        @Override
        public SyncChange append(ChangeType type, String entityId, String chatId, Long contentVersion) {
            var change = new SyncChange(++watermark, type, entityId, chatId,
                    contentVersion, Instant.now());
            entries.add(change);
            return change;
        }

        @Override
        public List<SyncChange> findSince(long since, int limit) {
            return entries.stream().filter(c -> c.version() > since).limit(limit).toList();
        }

        @Override
        public long currentVersion() {
            return watermark;
        }

        @Override
        public long reserveVersion() {
            if (watermark == 0) {
                watermark = 1; // versao reservada sem evento
            }
            return watermark;
        }

        @Override
        public long oldestRetainedVersion() {
            return entries.isEmpty() ? 0 : entries.getFirst().version();
        }

        @Override
        public int deleteOlderThan(Instant cutoff) {
            var before = entries.size();
            entries.removeIf(c -> c.createdAt().isBefore(cutoff));
            return before - entries.size();
        }

        @Override
        public int keepLatest(int maxRows) {
            var before = entries.size();
            while (entries.size() > maxRows) {
                entries.removeFirst();
            }
            return before - entries.size();
        }
    }
}
