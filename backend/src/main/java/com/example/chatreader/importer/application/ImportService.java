package com.example.chatreader.importer.application;

import com.example.chatreader.chat.domain.Chat;
import com.example.chatreader.chat.domain.ChatRepository;
import com.example.chatreader.chat.domain.ChatSource;
import com.example.chatreader.chat.domain.Message;
import com.example.chatreader.importer.domain.ImportException;
import com.example.chatreader.importer.domain.ImportResult;
import com.example.chatreader.importer.domain.ImportSource;
import com.example.chatreader.importer.domain.NormalizedChat;
import com.example.chatreader.sync.application.ChangeLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orquestra a importacao: resolve o adapter, normaliza e persiste de forma
 * <b>idempotente</b> (secao 12 da especificacao).
 *
 * <p>Algoritmo por chat (um item invalido nao derruba o lote):
 * <pre>
 * 1. h = ContentHash(canonico do NormalizedChat)
 * 2. procura chat existente por (source, externalId); se nao houver e externalId
 *    for nulo, procura por (source, contentHash)
 *    nao achou -&gt; INSERT                                        -&gt; created++
 *    achou    -&gt; c
 *               c.hasSameContentAs(...)  -&gt; nada                 -&gt; skipped++
 *               senao applyImportedContent + save                  -&gt; updated++
 * 3. tags: aplicadas na Etapa 3 (modulo tag)
 * </pre>
 *
 * <p>Reprocessar o mesmo arquivo produz {@code created=0, updated=0, skipped=N}.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ChatImporterRegistry registry;
    private final ChatRepository chatRepository;
    private final ImportProperties properties;
    private final ChangeLogWriter changeLogWriter;

    public ImportService(ChatImporterRegistry registry,
                         ChatRepository chatRepository,
                         ImportProperties properties,
                         ChangeLogWriter changeLogWriter) {
        this.registry = registry;
        this.chatRepository = chatRepository;
        this.properties = properties;
        this.changeLogWriter = changeLogWriter;
    }

    @Transactional
    public ImportResult importSource(ImportSource source) {
        var startedAt = System.nanoTime();
        var importer = registry.resolve(source);
        var normalized = importer.importChats(source);
        var sourceType = ChatSource.valueOf(importer.format().name());

        if (normalized.size() > properties.maxChats()) {
            throw new ImportException("Arquivo contem " + normalized.size()
                    + " conversas, acima do limite de " + properties.maxChats());
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        var failures = new java.util.ArrayList<String>();

        var now = Instant.now();
        for (int i = 0; i < normalized.size(); i++) {
            var chat = normalized.get(i);
            try {
                switch (persistOne(chat, sourceType, now)) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                var label = chat.title() == null ? "chat #" + i : chat.title();
                failures.add(label + ": " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " — " + e.getMessage()));
                log.warn("Importacao: chat [{}] falhou; os demais serao processados. motivo: {}",
                        label, e.getMessage());
            }
        }

        var result = new ImportResult(normalized.size(), created, updated, skipped, failed, failures);
        log.info("Importacao concluida em {} ms: formato={} total={} criados={} atualizados={} ignorados={} falhas={}",
                (System.nanoTime() - startedAt) / 1_000_000,
                importer.format(), result.total(), created, updated, skipped, failed);
        return result;
    }

    private Outcome persistOne(NormalizedChat normalized, ChatSource source, Instant now) {
        if (normalized.messages() == null || normalized.messages().isEmpty()) {
            throw new ImportException("conversa sem mensagens");
        }
        if (normalized.messages().size() > properties.maxMessagesPerChat()) {
            throw new ImportException("conversa com " + normalized.messages().size()
                    + " mensagens, acima do limite de " + properties.maxMessagesPerChat());
        }

        var domainMessages = toDomainMessages(normalized);
        var existing = locateExisting(normalized, source);

        if (existing.isEmpty()) {
            var created = Chat.create(source, normalized.externalId(), normalized.title(),
                    normalized.createdAt() != null ? normalized.createdAt() : now,
                    normalized.updatedAt(), now, domainMessages, normalized.tags());
            chatRepository.save(created);
            changeLogWriter.chatCreated(created.id(), created.contentVersion());
            return Outcome.CREATED;
        }

        var chat = existing.get();
        if (chat.applyImportedContent(normalized.title(), domainMessages, normalized.tags())) {
            chatRepository.save(chat);
            changeLogWriter.chatUpdated(chat.id(), chat.contentVersion());
            return Outcome.UPDATED;
        }
        return Outcome.SKIPPED;
    }

    /**
     * Identidade do chat. Prioriza externalId (estavel entre exports). Sem ele,
     * cai para contentHash, que torna a reimportacao do MESMO arquivo um no-op.
     */
    private java.util.Optional<Chat> locateExisting(NormalizedChat normalized, ChatSource source) {
        if (normalized.externalId() != null && !normalized.externalId().isBlank()) {
            var byExternal = chatRepository.findBySourceAndExternalId(source, normalized.externalId().strip());
            if (byExternal.isPresent()) {
                return byExternal;
            }
        }
        return chatRepository.findBySourceAndContentHash(source, contentHashOf(normalized));
    }

    private String contentHashOf(NormalizedChat normalized) {
        return com.example.chatreader.chat.domain.ContentHash.of(
                Chat.normalizeTitle(normalized.title()),
                toDomainMessages(normalized));
    }

    private List<Message> toDomainMessages(NormalizedChat normalized) {
        var ordered = normalized.messages().stream()
                .sorted(java.util.Comparator
                        .comparing((NormalizedChat.NormalizedMessage m) ->
                                m.createdAt() == null ? java.time.Instant.EPOCH : m.createdAt())
                        .thenComparing(m -> m.externalId() == null ? "" : m.externalId()))
                .toList();

        var result = new java.util.ArrayList<Message>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            var m = ordered.get(i);
            result.add(Message.of(UUID.randomUUID().toString(), m.role(), m.content(), i, m.createdAt()));
        }
        return result;
    }

    private enum Outcome {
        CREATED, UPDATED, SKIPPED
    }
}
