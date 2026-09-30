package com.example.chatreader.sync.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** DTOs do protocolo de sincronizacao (docs/sync-protocol.md). */
public final class SyncDtos {

    private SyncDtos() {
    }

    @Schema(description = "Mensagem dentro do payload de um chat")
    public record MessagePayload(
            String id,
            @Schema(example = "USER") String role,
            int sequence,
            String content,
            Instant createdAt,
            String contentHash
    ) {
    }

    @Schema(description = "Tag dentro do payload de um chat")
    public record TagPayload(String id, String name) {
    }

    @Schema(description = "Estado atual de um chat")
    public record ChatPayload(
            String id,
            String title,
            String source,
            String externalId,
            Instant createdAt,
            Instant updatedAt,
            Instant importedAt,
            long contentVersion,
            String contentHash,
            boolean favorite,
            int messageCount,
            List<TagPayload> tags,
            List<MessagePayload> messages,
            @Schema(description = "true quando messages veio vazio por causa do limite de tamanho")
            boolean messagesOmitted
    ) {
    }

    @Schema(description = "Estado atual de um bookmark")
    public record BookmarkPayload(
            String id,
            String chatId,
            String messageId,
            String note,
            Instant createdAt
    ) {
    }

    /**
     * Uma mudanca do delta. {@code type} diz o que acontece com a entidade; os
     * campos {@code chat}, {@code tag} e {@code bookmark} vem preenchidos conforme
     * o tipo (secao 4.1). {@code entityId} sempre identifica a entidade afetada,
     * inclusive nas remocoes.
     */
    @Schema(description = "Mudanca do change log")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChangeResponse(
            long version,
            String type,
            String entityType,
            String entityId,
            String chatId,
            Long contentVersion,
            Instant createdAt,
            ChatPayload chat,
            TagPayload tag,
            BookmarkPayload bookmark
    ) {
    }

    @Schema(description = "Resposta de GET /api/sync")
    public record SyncDeltaResponse(
            @Schema(description = "token a ser enviado no proximo since (ou 0, se nada mudou)")
            @JsonProperty("syncToken") long syncVersion,
            @Schema(description = "ha mais mudancas depois desta pagina; repetir com since=syncToken")
            boolean hasMore,
            @Schema(description = "o delta nao entrega o estado inteiro: since=0 (cliente sem token) "
                    + "ou token anterior a janela retida. Chamar GET /api/sync/snapshot "
                    + "(docs/sync-protocol.md, secao 4.4)")
            boolean fullResyncRequired,
            Instant serverTime,
            List<ChangeResponse> changes
    ) {
    }

    @Schema(description = "Resposta de GET /api/sync/snapshot (paginado por chat)")
    public record SyncSnapshotResponse(
            @Schema(description = "token gravado pelo cliente; sempre maior que zero, "
                    + "mesmo quando o log esta vazio")
            @JsonProperty("syncToken") long syncVersion,
            int page,
            int size,
            long total,
            boolean last,
            List<TagPayload> tags,
            List<ChatPayload> chats,
            List<BookmarkPayload> bookmarks,
            @Schema(description = "true se nem todos os bookmarks couberam; buscar em GET /api/bookmarks")
            boolean bookmarksOmitted
    ) {
    }

    @Schema(description = "Escrita feita pelo Kindle enquanto estava offline")
    public record LocalChangeRequest(
            @Schema(example = "BOOKMARK_CREATED") String type,
            @Schema(description = "conteudo da mudanca, depende do type") PayloadRequest payload
    ) {
    }

    /** Campos aceitos dentro de {@code payload}. Todos opcionais: o que falta vira 400. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayloadRequest(
            String id,
            String chatId,
            String messageId,
            String note,
            Boolean favorite,
            String motivo
    ) {
    }

    @Schema(description = "Corpo do ACK")
    public record AckRequest(
            @Schema(description = "token que o cliente diz ter aplicado (informativo)") long ackVersion,
            @Schema(description = "identificador do dispositivo, so para observabilidade") String clientId,
            @Schema(maximum = "200") List<LocalChangeRequest> localChanges
    ) {
    }

    @Schema(description = "Resposta do ACK")
    public record AckResponse(
            long ackVersion,
            @JsonProperty("syncToken") long newSyncVersion,
            int accepted,
            int rejected,
            List<RejectionResponse> rejections
    ) {
    }

    @Schema(description = "Mudanca recusada; index volta a posicao em localChanges")
    public record RejectionResponse(int index, String reason, String message) {
    }
}
