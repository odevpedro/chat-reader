package com.example.chatreader.chat.infrastructure;

import com.example.chatreader.chat.domain.Chat;
import com.example.chatreader.chat.domain.Message;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Conversao entre o agregado do dominio (puro) e as entidades JPA. E o unico lugar
 * que conhece os dois lados.
 */
@Component
public class ChatMapper {

    public Chat toDomain(ChatEntity entity, List<MessageEntity> messages, Collection<String> tags) {
        var domainMessages = messages.stream()
                .map(this::toDomain)
                .toList();
        return Chat.restore(
                entity.getId(),
                entity.getSource(),
                entity.getExternalId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getImportedAt(),
                domainMessages,
                entity.getContentHash(),
                entity.getContentVersion(),
                entity.isFavorite(),
                entity.isDeleted(),
                tags);
    }

    public Message toDomain(MessageEntity entity) {
        return Message.of(entity.getId(), entity.getRole(), entity.getContent(),
                entity.getSequence(), entity.getCreatedAt());
    }

    /** Metadados do chat, sem carregar mensagens (listagens). */
    public Chat toDomainMetadata(ChatEntity entity, Collection<String> tags) {
        return Chat.restoreMetadata(
                entity.getId(),
                entity.getSource(),
                entity.getExternalId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getImportedAt(),
                entity.getContentHash(),
                entity.getContentVersion(),
                entity.isFavorite(),
                entity.isDeleted(),
                entity.getMessageCount(),
                tags);
    }

    public ChatEntity toEntity(Chat chat) {
        var entity = new ChatEntity();
        applyChat(chat, entity);
        return entity;
    }

    public void applyChat(Chat chat, ChatEntity entity) {
        entity.setId(chat.id());
        entity.setSource(chat.source());
        entity.setExternalId(chat.externalId());
        entity.setTitle(chat.title());
        entity.setCreatedAt(chat.createdAt());
        entity.setUpdatedAt(chat.updatedAt());
        entity.setImportedAt(chat.importedAt());
        entity.setContentHash(chat.contentHash());
        entity.setContentVersion(chat.contentVersion());
        entity.setFavorite(chat.isFavorite());
        entity.setDeleted(chat.deleted());
        entity.setMessageCount(chat.messageCount());
    }

    public MessageEntity toEntity(Message message, String chatId) {
        var entity = new MessageEntity();
        entity.setId(message.id());
        entity.setChatId(chatId);
        entity.setRole(message.role());
        entity.setContent(message.content());
        entity.setSequence(message.sequence());
        entity.setCreatedAt(message.createdAt());
        entity.setContentHash(message.contentHash());
        return entity;
    }
}
