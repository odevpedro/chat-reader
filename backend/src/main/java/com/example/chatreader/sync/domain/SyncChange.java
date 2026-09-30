package com.example.chatreader.sync.domain;

import java.time.Instant;

/**
 * Uma linha do change log: metadados da mudanca, sem payload.
 *
 * <p>O payload (chat, bookmark, tag) e montado na leitura, a partir do estado
 * atual — assim o log nao duplica conteudo de mensagem e o cliente nunca recebe
 * um retrato congelado no passado.
 *
 * @param version        token monotonico global; e o {@code syncToken} do cliente
 * @param type           o que aconteceu
 * @param entityId       id da entidade afetada
 * @param chatId         chat relacionado, quando houver
 * @param contentVersion versao do chat no momento da mudanca
 * @param createdAt      quando o evento foi gravado
 */
public record SyncChange(
        long version,
        ChangeType type,
        String entityId,
        String chatId,
        Long contentVersion,
        Instant createdAt
) {

    public static SyncChange chat(ChangeType type, String chatId, long contentVersion) {
        return new SyncChange(0L, type, chatId, chatId, contentVersion, null);
    }

    public static SyncChange bookmark(ChangeType type, String bookmarkId, String chatId) {
        return new SyncChange(0L, type, bookmarkId, chatId, null, null);
    }

    public static SyncChange tag(ChangeType type, String tagId) {
        return new SyncChange(0L, type, tagId, null, null, null);
    }
}
