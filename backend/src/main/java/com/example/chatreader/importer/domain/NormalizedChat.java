package com.example.chatreader.importer.domain;

import com.example.chatreader.chat.domain.Role;

import java.time.Instant;
import java.util.Set;

/**
 * Resultado normalizado de UMA conversa, independente de formato de origem. E o unico
 * tipo que entra no dominio: o dominio nao conhece "ChatGPT" nem "conversations.json".
 *
 * <p>Ver ADR-004.
 */
public record NormalizedChat(
        String externalId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        java.util.List<NormalizedMessage> messages,
        Set<String> tags
) {
    public NormalizedChat {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    public record NormalizedMessage(
            String externalId,
            Role role,
            String content,
            Instant createdAt
    ) {
        public NormalizedMessage {
            role = role == null ? Role.UNKNOWN : role;
            content = content == null ? "" : content;
        }

        public static NormalizedMessage user(String content) {
            return new NormalizedMessage(null, Role.USER, content, null);
        }

        public static NormalizedMessage assistant(String content) {
            return new NormalizedMessage(null, Role.ASSISTANT, content, null);
        }

        @Override
        public String toString() {
            return "NormalizedMessage[role=" + role + ", chars=" + content.length() + "]";
        }
    }

    @Override
    public String toString() {
        return "NormalizedChat[externalId=" + externalId + ", title=" + title
                + ", messages=" + messages.size() + "]";
    }
}
