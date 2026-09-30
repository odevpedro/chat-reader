package com.example.chatreader.sync.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Entidade JPA da linha do change log. A versao vem do {@code BIGSERIAL} do banco. */
@Entity
@Table(name = "sync_change_log")
public class ChangeLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "change_type", length = 30, nullable = false)
    private String changeType;

    @Column(name = "entity_type", length = 20, nullable = false)
    private String entityType;

    @Column(name = "entity_id", length = 36, nullable = false)
    private String entityId;

    @Column(name = "chat_id", length = 36)
    private String chatId;

    @Column(name = "content_version")
    private Long contentVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChangeLogEntity() {
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public Long getContentVersion() {
        return contentVersion;
    }

    public void setContentVersion(Long contentVersion) {
        this.contentVersion = contentVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
