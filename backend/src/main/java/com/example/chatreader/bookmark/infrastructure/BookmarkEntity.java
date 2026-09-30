package com.example.chatreader.bookmark.infrastructure;

import com.example.chatreader.bookmark.domain.Bookmark;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Entidade JPA de {@link Bookmark}. */
@Entity
@Table(name = "bookmarks")
public class BookmarkEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "chat_id", length = 36, nullable = false)
    private String chatId;

    @Column(name = "message_id", length = 36, nullable = false)
    private String messageId;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BookmarkEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
