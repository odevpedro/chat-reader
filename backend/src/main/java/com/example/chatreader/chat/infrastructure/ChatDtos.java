package com.example.chatreader.chat.infrastructure;

import com.example.chatreader.chat.domain.Chat;
import com.example.chatreader.chat.domain.Message;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** DTOs da API de conversas. Separados das entidades JPA para isolar o contrato. */
public final class ChatDtos {

    private ChatDtos() {
    }

    @Schema(description = "Metadados de uma conversa. Nao inclui as mensagens.")
    public record ChatSummaryResponse(
            @Schema(example = "0f9c1c9a-1b2c-4d3e-8f10-aabbccddeeff") String id,
            String title,
            @Schema(example = "CHATGPT_EXPORT") String source,
            String externalId,
            Instant createdAt,
            Instant updatedAt,
            Instant importedAt,
            @Schema(description = "Versao monotonica do conteudo (LWW)") long contentVersion,
            String contentHash,
            boolean favorite,
            @Schema(description = "Numero de mensagens, sem carregar o conteudo") int messageCount,
            @Schema(description = "Tags do chat, ordenadas") List<String> tags
    ) {
        public static ChatSummaryResponse from(Chat chat) {
            return new ChatSummaryResponse(
                    chat.id(), chat.title(), chat.source().name(), chat.externalId(),
                    chat.createdAt(), chat.updatedAt(), chat.importedAt(),
                    chat.contentVersion(), chat.contentHash(), chat.isFavorite(), chat.messageCount(),
                    List.copyOf(chat.tags()));
        }
    }

    @Schema(description = "Conversa completa, com todas as mensagens.")
    public record ChatDetailResponse(
            ChatSummaryResponse chat,
            List<MessageResponse> messages
    ) {
        public static ChatDetailResponse from(Chat chat) {
            return new ChatDetailResponse(
                    ChatSummaryResponse.from(chat),
                    chat.messages().stream().map(MessageResponse::from).toList());
        }
    }

    @Schema(description = "Mensagem com conteudo Markdown CRU (o render e do cliente).")
    public record MessageResponse(
            String id,
            @Schema(example = "USER") String role,
            int sequence,
            String content,
            Instant createdAt,
            String contentHash
    ) {
        public static MessageResponse from(Message message) {
            return new MessageResponse(
                    message.id(), message.role().name(), message.sequence(),
                    message.content(), message.createdAt(), message.contentHash());
        }
    }

    @Schema(description = "Estado de favorito desejado")
    public record FavoriteRequest(boolean favorite) {
    }

    @Schema(description = "Novo conjunto de tags do chat (substitui o atual)")
    public record SetTagsRequest(List<String> tags) {
    }
}
