package com.example.chatreader.chat.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Entidade JPA de {@link com.example.chatreader.chat.domain.Message}. */
@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_chat_sequence", columnList = "chat_id, sequence", unique = true)
})
public class MessageEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "chat_id", length = 36, nullable = false)
    private String chatId;

    @Column(name = "external_id", length = 200)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private com.example.chatreader.chat.domain.Role role;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    protected MessageEntity() {
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

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public com.example.chatreader.chat.domain.Role getRole() {
        return role;
    }

    public void setRole(com.example.chatreader.chat.domain.Role role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
