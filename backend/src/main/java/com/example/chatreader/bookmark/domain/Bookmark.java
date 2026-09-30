package com.example.chatreader.bookmark.domain;

import java.time.Instant;

/** Bookmark de uma mensagem especifica (secao 10). */
public record Bookmark(String id, String chatId, String messageId, String note, Instant createdAt) {
}
