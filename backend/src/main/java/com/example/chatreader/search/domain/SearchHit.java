package com.example.chatreader.search.domain;

import java.time.Instant;

/** Trecho de mensagem que casou com a busca full-text (secao 13). */
public record SearchHit(
        String chatId,
        String title,
        String messageId,
        String role,
        String snippet,
        Instant createdAt
) {
}
