package com.example.chatreader.bookmark.infrastructure;

import com.example.chatreader.bookmark.domain.Bookmark;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** DTOs da API de bookmarks. */
public final class BookmarkDtos {

    private BookmarkDtos() {
    }

    @Schema(description = "Bookmark criado")
    public record BookmarkResponse(
            String id,
            String chatId,
            String messageId,
            String note,
            Instant createdAt
    ) {
        public static BookmarkResponse from(Bookmark bookmark) {
            return new BookmarkResponse(bookmark.id(), bookmark.chatId(), bookmark.messageId(),
                    bookmark.note(), bookmark.createdAt());
        }
    }

    @Schema(description = "Dados para criar um bookmark")
    public record CreateBookmarkRequest(
            @NotBlank String chatId,
            @NotBlank String messageId,
            String note
    ) {
    }
}
