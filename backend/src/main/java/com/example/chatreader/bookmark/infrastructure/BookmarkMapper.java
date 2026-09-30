package com.example.chatreader.bookmark.infrastructure;

import com.example.chatreader.bookmark.domain.Bookmark;
import org.springframework.stereotype.Component;

/** Conversao entre {@link Bookmark} e {@link BookmarkEntity}. */
@Component
public class BookmarkMapper {

    public Bookmark toDomain(BookmarkEntity entity) {
        return new Bookmark(entity.getId(), entity.getChatId(), entity.getMessageId(),
                entity.getNote(), entity.getCreatedAt());
    }

    public BookmarkEntity toEntity(Bookmark bookmark) {
        var entity = new BookmarkEntity();
        entity.setId(bookmark.id());
        entity.setChatId(bookmark.chatId());
        entity.setMessageId(bookmark.messageId());
        entity.setNote(bookmark.note());
        entity.setCreatedAt(bookmark.createdAt());
        return entity;
    }
}
