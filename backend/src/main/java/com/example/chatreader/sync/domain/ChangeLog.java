package com.example.chatreader.sync.domain;

import java.time.Instant;
import java.util.List;

/**
 * Porta do change log (docs/sync-protocol.md, secao 2).
 *
 * <p>O {@code version} e gerado pelo banco ({@code BIGSERIAL}) e nunca pelo
 * servidor Java: assim o token continua valido apos reinicio e nunca repete.
 */
public interface ChangeLog {

    /** Grava uma mudanca e devolve a linha com a versao ja atribuida. */
    SyncChange append(ChangeType type, String entityId, String chatId, Long contentVersion);

    /** Mudancas com {@code version > since}, ordenadas, no maximo {@code limit + 1}. */
    List<SyncChange> findSince(long since, int limit);

    /**
     * Maior versao ja emitida, inclusive as ja podadas (a sequence nao anda para
     * tras); {@code 0} quando nenhuma versao foi consumida.
     */
    long currentVersion();

    /**
     * Devolve uma versao que pode ser usada como token, reservando uma da
     * sequence se ainda nao houver nenhuma. Usado no snapshot para o token nunca
     * ser {@code 0} — senao o cliente novo repetiria o snapshot para sempre.
     */
    long reserveVersion();

    /** Menor versao ainda retida; {@code 0} quando o log esta vazio. */
    long oldestRetainedVersion();

    /** Remove mudancas anteriores ao instante informado (retencao). */
    int deleteOlderThan(Instant cutoff);

    /**
     * Mantem no maximo as {@code maxRows} linhas mais recentes. Segunda retencao:
     * 30 dias <b>ou</b> o teto de linhas, o que vier primeiro (secao 9).
     */
    int keepLatest(int maxRows);
}
