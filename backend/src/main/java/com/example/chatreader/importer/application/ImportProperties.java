package com.example.chatreader.importer.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites de importacao (secao 27: limites de tamanho, protecao contra importacao
 * malformada).
 *
 * @param maxBytes       tamanho maximo do arquivo aceito, em bytes
 * @param maxChats       maximo de conversas por arquivo
 * @param maxMessagesPerChat maximo de mensagens por conversa
 */
@ConfigurationProperties(prefix = "chatreader.import")
public record ImportProperties(long maxBytes, int maxChats, int maxMessagesPerChat) {

    public ImportProperties {
        if (maxBytes <= 0) {
            maxBytes = 10L * 1024 * 1024;
        }
        if (maxChats <= 0) {
            maxChats = 10_000;
        }
        if (maxMessagesPerChat <= 0) {
            maxMessagesPerChat = 5_000;
        }
    }
}
