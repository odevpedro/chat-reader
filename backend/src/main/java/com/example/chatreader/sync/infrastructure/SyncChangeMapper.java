package com.example.chatreader.sync.infrastructure;

import com.example.chatreader.bookmark.domain.Bookmark;
import com.example.chatreader.bookmark.domain.BookmarkRepository;
import com.example.chatreader.chat.domain.Chat;
import com.example.chatreader.chat.domain.ChatRepository;
import com.example.chatreader.chat.domain.Message;
import com.example.chatreader.sync.application.SyncProperties;
import com.example.chatreader.sync.domain.ChangeType;
import com.example.chatreader.sync.domain.LocalChange;
import com.example.chatreader.sync.domain.SyncChange;
import com.example.chatreader.tag.domain.TagRef;
import com.example.chatreader.tag.domain.TagRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Monta o JSON do sync a partir do estado atual.
 *
 * <p>O change log guarda so metadados: quem mudou, quando. O payload e resolvido
 * aqui, na leitura. Assim o log nao duplica o conteudo das mensagens e o cliente
 * sempre recebe o estado mais recente, nunca um retrato congelado no passado
 * (docs/sync-protocol.md, secao 4.1).
 */
@Component
public class SyncChangeMapper {

    private final ChatRepository chatRepository;
    private final BookmarkRepository bookmarkRepository;
    private final TagRepository tagRepository;
    private final SyncProperties properties;

    public SyncChangeMapper(ChatRepository chatRepository, BookmarkRepository bookmarkRepository,
                            TagRepository tagRepository, SyncProperties properties) {
        this.chatRepository = chatRepository;
        this.bookmarkRepository = bookmarkRepository;
        this.tagRepository = tagRepository;
        this.properties = properties;
    }

    public SyncDtos.ChangeResponse toChange(SyncChange change) {
        SyncDtos.ChatPayload chat = null;
        SyncDtos.TagPayload tag = null;
        SyncDtos.BookmarkPayload bookmark = null;

        switch (change.type()) {
            case CHAT_CREATED, CHAT_UPDATED -> chat = chatPayload(change.entityId());
            case TAG_CREATED, TAG_UPDATED -> tag = tagRepository.findById(change.entityId())
                    .map(ref -> new SyncDtos.TagPayload(ref.id(), ref.name())).orElse(null);
            case BOOKMARK_CREATED, BOOKMARK_UPDATED -> bookmark = bookmarkRepository.findById(change.entityId())
                    .map(this::bookmarkPayload).orElse(null);
            default -> {
                // CHAT_DELETED / *_DELETED: entityId basta para o cliente remover
            }
        }

        return new SyncDtos.ChangeResponse(
                change.version(),
                change.type().name(),
                change.type().entityType(),
                change.entityId(),
                change.chatId(),
                change.contentVersion(),
                change.createdAt(),
                chat, tag, bookmark);
    }

    /**
     * Chat do delta: metadados sempre, mensagens ate o limite configurado.
     * Acima do limite, so metadados + {@code messagesOmitted}.
     */
    private SyncDtos.ChatPayload chatPayload(String chatId) {
        var metadata = chatRepository.findMetadataById(chatId).orElse(null);
        if (metadata == null) {
            return null;
        }
        var cap = properties.maxMessagesPerChange();
        var omitted = metadata.messageCount() > cap;
        var messages = omitted ? List.<Message>of() : chatRepository.findMessages(chatId, 0, cap);
        return toChatPayload(metadata, messages, omitted);
    }

    /**
     * Chat do snapshot. A decisao de omitir usa {@code messageCount} (sempre
     * correto, mesmo quando o agregado vem so com metadados) em vez do tamanho
     * da lista de mensagens.
     */
    public SyncDtos.ChatPayload toChatPayload(Chat chat) {
        var omitted = chat.messageCount() > properties.maxMessagesPerChange();
        return toChatPayload(chat, omitted ? List.<Message>of() : chat.messages(), omitted);
    }

    private SyncDtos.ChatPayload toChatPayload(Chat chat, List<Message> messages, boolean messagesOmitted) {
        return new SyncDtos.ChatPayload(
                chat.id(),
                chat.title(),
                chat.source().name(),
                chat.externalId(),
                chat.createdAt(),
                chat.updatedAt(),
                chat.importedAt(),
                chat.contentVersion(),
                chat.contentHash(),
                chat.isFavorite(),
                chat.messageCount(),
                tagPayloads(chat.tags()),
                // acima do limite o cliente recebe so metadados: um delta com
                // milhares de mensagens estouraria a resposta e o envio seria
                // nao-anonimo; a tela de leitura pagina sob demanda
                messagesOmitted ? List.of() : messages.stream().map(SyncChangeMapper::messagePayload).toList(),
                messagesOmitted);
    }

    public SyncDtos.BookmarkPayload bookmarkPayload(Bookmark bookmark) {
        return new SyncDtos.BookmarkPayload(bookmark.id(), bookmark.chatId(), bookmark.messageId(),
                bookmark.note(), bookmark.createdAt());
    }

    public SyncDtos.TagPayload tagPayload(TagRef ref) {
        return new SyncDtos.TagPayload(ref.id(), ref.name());
    }

    private List<SyncDtos.TagPayload> tagPayloads(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Map<String, SyncDtos.TagPayload> byName = new LinkedHashMap<>();
        tagRepository.findByNames(names).forEach(ref -> byName.put(ref.name(), tagPayload(ref)));
        // preserva a ordem do agregado (alfabetica) e nao repete consultas
        return names.stream().map(byName::get).filter(Objects::nonNull).toList();
    }

    private static SyncDtos.MessagePayload messagePayload(Message message) {
        return new SyncDtos.MessagePayload(message.id(), message.role().name(), message.sequence(),
                message.content(), message.createdAt(), message.contentHash());
    }

    /**
     * Converte o item do ACK no tipo fechado do dominio. Item malformado (tipo
     * desconhecido ou campo obrigatorio faltando) e erro da requisicao inteira,
     * com 400 — recusa por item existe para falhas de negocio, nao para JSON
     * invalido.
     */
    public LocalChange toLocalChange(SyncDtos.LocalChangeRequest request) {
        var type = request == null || request.type() == null ? "" : request.type().strip();
        var payload = request == null || request.payload() == null
                ? new SyncDtos.PayloadRequest(null, null, null, null, null, null)
                : request.payload();

        return switch (type) {
            case "BOOKMARK_CREATED" -> new LocalChange.BookmarkCreate(
                    required(payload.id(), "payload.id"),
                    required(payload.chatId(), "payload.chatId"),
                    required(payload.messageId(), "payload.messageId"),
                    payload.note());
            case "BOOKMARK_DELETED" -> new LocalChange.BookmarkDelete(
                    required(payload.id(), "payload.id"));
            case "CHAT_FAVORITE" -> new LocalChange.ChatFavorite(
                    required(payload.chatId(), "payload.chatId"),
                    requiredBoolean(payload.favorite(), "payload.favorite"));
            case "CHAT_CONTENT" -> new LocalChange.ContentWrite(
                    required(payload.chatId(), "payload.chatId"),
                    payload.motivo() == null ? "escrita de conteudo nao permitida" : payload.motivo());
            default -> throw new IllegalArgumentException("Tipo de mudanca local desconhecido: " + type);
        };
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Campo obrigatorio ausente: " + field);
        }
        return value.strip();
    }

    private static boolean requiredBoolean(Boolean value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Campo obrigatorio ausente: " + field);
        }
        return value;
    }
}
