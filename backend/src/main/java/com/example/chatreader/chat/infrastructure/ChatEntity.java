package com.example.chatreader.chat.infrastructure;

import com.example.chatreader.chat.domain.ChatSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Entidade JPA do agregado {@link com.example.chatreader.chat.domain.Chat}.
 *
 * <p>O agregado do dominio e a fonte; esta entidade e a representacao relacional.
 * A conversao acontece em {@link ChatMapper}. Mensagens ficam em
 * {@link MessageEntity} (tabela separada), carregadas sob demanda — nao ha
 * {@code @OneToMany} para evitar carregar milhares de mensagens por chat na listagem.
 */
@Entity
@Table(name = "chats")
public class ChatEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 40, nullable = false)
    private ChatSource source;

    @Column(name = "external_id", length = 200)
    private String externalId;

    @Column(name = "title", length = 500, nullable = false)
    private String title;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "imported_at")
    private Instant importedAt;

    @Column(name = "content_version", nullable = false)
    private long contentVersion;

    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash;

    @Column(name = "favorite", nullable = false)
    private boolean favorite;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    protected ChatEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ChatSource getSource() {
        return source;
    }

    public void setSource(ChatSource source) {
        this.source = source;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    public long getContentVersion() {
        return contentVersion;
    }

    public void setContentVersion(long contentVersion) {
        this.contentVersion = contentVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }
}
