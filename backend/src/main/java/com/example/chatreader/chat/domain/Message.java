package com.example.chatreader.chat.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Mensagem de uma conversa. Objeto de dominio puro (sem JPA). O conteudo e
 * armazenado CRU (Markdown preservado); o render e responsabilidade do cliente.
 */
public class Message {

    private final String id;
    private final Role role;
    private final String content;
    private final int sequence;
    private final Instant createdAt;
    private final String contentHash;

    private Message(String id, Role role, String content, int sequence, Instant createdAt, String contentHash) {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence deve ser >= 0, recebido: " + sequence);
        }
        this.id = id;
        this.role = Objects.requireNonNull(role, "role nao pode ser nulo");
        this.content = content == null ? "" : content;
        this.sequence = sequence;
        this.createdAt = createdAt;
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash nao pode ser nulo");
    }

    public static Message of(String id, Role role, String content, int sequence, Instant createdAt) {
        var safeRole = role == null ? Role.UNKNOWN : role;
        var safeContent = content == null ? "" : content;
        return new Message(id, safeRole, safeContent, sequence, createdAt,
                ContentHash.ofMessage(safeRole, safeContent));
    }

    public String id() {
        return id;
    }

    public Role role() {
        return role;
    }

    public String content() {
        return content;
    }

    public int sequence() {
        return sequence;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String contentHash() {
        return contentHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message other)) {
            return false;
        }
        return sequence == other.sequence
                && role == other.role
                && contentHash.equals(other.contentHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, role, contentHash);
    }

    @Override
    public String toString() {
        return "Message[seq=" + sequence + ", role=" + role + ", chars=" + content.length() + "]";
    }
}
