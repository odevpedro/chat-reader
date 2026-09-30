package com.example.chatreader.sync.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites do delta sync (docs/sync-protocol.md, secao 9).
 *
 * @param defaultLimit             mudancas por requisicao quando o cliente nao manda limit
 * @param maxLimit                 teto aceito para limit
 * @param retentionDays            dias que o change log e mantido antes da poda
 * @param maxMessagesPerChange     acima disso a mudanca vem sem mensagens
 *                                 ({@code messagesOmitted}), e o cliente pagina
 * @param snapshotDefaultPageSize  chats por pagina no full snapshot
 * @param maxBookmarksInSnapshot   bookmarks embutidos no full snapshot
 * @param maxLogRows               teto de linhas do change log (segunda retencao)
 */
@ConfigurationProperties(prefix = "chatreader.sync")
public record SyncProperties(
        int defaultLimit,
        int maxLimit,
        int retentionDays,
        int maxMessagesPerChange,
        int snapshotDefaultPageSize,
        int maxBookmarksInSnapshot,
        int maxLogRows) {

    public SyncProperties {
        if (defaultLimit <= 0) {
            defaultLimit = 200;
        }
        if (maxLimit <= 0) {
            maxLimit = 1000;
        }
        if (defaultLimit > maxLimit) {
            defaultLimit = maxLimit;
        }
        if (retentionDays <= 0) {
            retentionDays = 30;
        }
        if (maxMessagesPerChange <= 0) {
            maxMessagesPerChange = 200;
        }
        if (snapshotDefaultPageSize <= 0) {
            snapshotDefaultPageSize = 50;
        }
        if (maxBookmarksInSnapshot <= 0) {
            maxBookmarksInSnapshot = 1000;
        }
        if (maxLogRows <= 0) {
            maxLogRows = 1_000_000;
        }
    }
}
