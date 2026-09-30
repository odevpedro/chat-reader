package com.example.chatreader.sync.domain;

import java.time.Instant;
import java.util.List;

/**
 * Resposta de {@code GET /api/sync}.
 *
 * <p>O campo chama-se {@code syncVersion} no dominio (e vira {@code syncToken} no
 * JSON) para nao ter um campo chamado "token" no dominio — regra de arquitetura
 * {@code domainHasNoCredentialFields}: segredo e token sao variavel de ambiente
 * ou dado de transporte, nunca estado do dominio.
 *
 * @param syncVersion        versao a ser gravada pelo cliente como token
 * @param hasMore            existem mudancas depois desta pagina
 * @param fullResyncRequired token antigo demais: o cliente deve fazer snapshot
 * @param serverTime         instante do servidor, so para diagnostico
 * @param changes            mudancas desta pagina
 */
public record SyncDelta(
        long syncVersion,
        boolean hasMore,
        boolean fullResyncRequired,
        Instant serverTime,
        List<SyncChange> changes
) {
}
