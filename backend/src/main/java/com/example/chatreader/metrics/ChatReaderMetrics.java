package com.example.chatreader.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Metricas basicas da secao 31. Nomes expostos:
 * {@code chatreader.imports_total}, {@code chatreader.import_chats_total},
 * {@code chatreader.sync_requests_total}, {@code chatreader.sync_changes_total},
 * {@code chatreader.search_requests_total}.
 *
 * <p>Contadores de chat usam sufixo por desfecho para permitir somar depois.
 */
@Component
public class ChatReaderMetrics {

    private final Counter imports;
    private final Counter chatsCreated;
    private final Counter chatsUpdated;
    private final Counter chatsSkipped;
    private final Counter chatsFailed;
    private final Counter syncRequests;
    private final Counter syncChanges;
    private final Counter searchRequests;
    private final Timer importTimer;

    public ChatReaderMetrics(MeterRegistry registry) {
        this.imports = Counter.builder("chatreader.imports_total")
                .description("Importacoes executadas").register(registry);
        this.chatsCreated = Counter.builder("chatreader.import_chats_total")
                .tag("outcome", "created").register(registry);
        this.chatsUpdated = Counter.builder("chatreader.import_chats_total")
                .tag("outcome", "updated").register(registry);
        this.chatsSkipped = Counter.builder("chatreader.import_chats_total")
                .tag("outcome", "skipped").register(registry);
        this.chatsFailed = Counter.builder("chatreader.import_chats_total")
                .tag("outcome", "failed").register(registry);
        this.syncRequests = Counter.builder("chatreader.sync_requests_total")
                .description("Requisicoes de delta sync").register(registry);
        this.syncChanges = Counter.builder("chatreader.sync_changes_total")
                .description("Mudancas devolvidas pelo sync").register(registry);
        this.searchRequests = Counter.builder("chatreader.search_requests_total")
                .description("Requisicoes de busca").register(registry);
        this.importTimer = Timer.builder("chatreader.import_duration")
                .description("Duracao de uma importacao").register(registry);
    }

    /**
     * Recebe contagens como primitivas, e nao um {@code ImportResult}: assim o pacote
     * {@code metrics} nao depende de {@code importer}, quebrando o ciclo
     * importer -&gt; metrics -&gt; importer (ArchUnit: ArchitectureTest).
     */
    public void recordImport(int created, int updated, int skipped, int failed) {
        imports.increment();
        addBy(created, chatsCreated);
        addBy(updated, chatsUpdated);
        addBy(skipped, chatsSkipped);
        addBy(failed, chatsFailed);
    }

    public void recordImportDuration(java.time.Duration duration) {
        importTimer.record(duration);
    }

    public void recordSyncRequest(int changesReturned) {
        syncRequests.increment();
        syncChanges.increment(changesReturned);
    }

    public void recordSearch() {
        searchRequests.increment();
    }

    private void addBy(int amount, Counter counter) {
        if (amount > 0) {
            counter.increment(amount);
        }
    }
}
