package com.example.chatreader.chat.infrastructure;

import com.example.chatreader.bookmark.infrastructure.BookmarkJpaRepository;
import com.example.chatreader.chat.domain.Chat;
import com.example.chatreader.chat.domain.ChatRepository;
import com.example.chatreader.chat.domain.ChatSource;
import com.example.chatreader.chat.domain.Message;
import com.example.chatreader.tag.domain.TagRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Implementacao JPA da porta {@link ChatRepository}.
 *
 * <p>Escreve o agregado inteiro (chat + mensagens) de forma transacional: em uma
 * reimportacao que altera conteudo, as mensagens antigas sao removidas e as novas
 * inseridas na mesma transacao (secao 4 de docs/domain.md). As tags sao
 * persistidas pelo modulo tag, via {@link TagRepository}.
 */
@Repository
public class ChatRepositoryAdapter implements ChatRepository {

    private final ChatJpaRepository chats;
    private final MessageJpaRepository messages;
    private final TagRepository tags;
    private final BookmarkJpaRepository bookmarks;
    private final ChatMapper mapper;

    public ChatRepositoryAdapter(ChatJpaRepository chats, MessageJpaRepository messages,
                                 TagRepository tags, BookmarkJpaRepository bookmarks, ChatMapper mapper) {
        this.chats = chats;
        this.messages = messages;
        this.tags = tags;
        this.bookmarks = bookmarks;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Chat> findById(String id) {
        return chats.findActiveById(id).map(entity -> {
            var messageEntities = messages.findByChatIdOrderBySequenceAsc(id);
            return mapper.toDomain(entity, messageEntities, tags.tagsOf(id));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Chat> findMetadataById(String id) {
        return chats.findActiveById(id)
                .map(entity -> mapper.toDomainMetadata(entity, tags.tagsOf(entity.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(String id) {
        return chats.findActiveById(id).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Chat> findBySourceAndExternalId(ChatSource source, String externalId) {
        return chats.findBySourceAndExternalIdAndDeletedFalse(source, externalId)
                .map(entity -> mapper.toDomainMetadata(entity, tags.tagsOf(entity.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Chat> findBySourceAndContentHash(ChatSource source, String contentHash) {
        return chats.findBySourceAndContentHashAndDeletedFalse(source, contentHash)
                .map(entity -> mapper.toDomainMetadata(entity, tags.tagsOf(entity.getId())));
    }

    @Override
    @Transactional
    public void save(Chat chat) {
        var entity = chats.findById(chat.id()).orElseGet(ChatEntity::new);
        var wasExisting = entity.getId() != null;
        var previousHash = entity.getContentHash();

        mapper.applyChat(chat, entity);
        chats.save(entity);

        // Reescreve as mensagens quando o chat e novo ou quando o conteudo mudou.
        // Comparar o contentHash (e nao a contagem) cobre edicoes que mantem o
        // mesmo numero de mensagens; atualizacoes de apenas tags/favorito nao
        // tocam nas mensagens, preservando os ids (e os bookmarks existentes).
        if (!wasExisting || !Objects.equals(previousHash, chat.contentHash())) {
            messages.deleteByChatId(chat.id());
            messages.flush();
            messages.saveAll(chat.messages().stream()
                    .map(m -> mapper.toEntity(m, chat.id()))
                    .toList());
        }

        tags.replaceChatTags(chat.id(), chat.tags());
    }

    @Override
    @Transactional
    public void saveAll(List<Chat> chatList) {
        chatList.forEach(this::save);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chat> findAll(int page, int size) {
        var entities = chats.findActive(pageable(page, size));
        return withTags(entities);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chat> findFavorites(int page, int size) {
        var entities = chats.findFavorites(pageable(page, size));
        return withTags(entities);
    }

    @Override
    @Transactional(readOnly = true)
    public long countFavorites() {
        return chats.countByDeletedFalseAndFavoriteTrue();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chat> findByTag(String tagName, int page, int size) {
        var ids = tags.chatIdsWithTag(tagName);
        var window = slice(ids, page, size);
        if (window.isEmpty()) {
            return List.of();
        }
        var byId = new LinkedHashMap<String, ChatEntity>();
        chats.findActiveByIdIn(window).forEach(e -> byId.put(e.getId(), e));
        var tagIndex = tags.tagsOf(window);
        var result = new ArrayList<Chat>(window.size());
        for (var id : window) {
            var entity = byId.get(id);
            if (entity != null) {
                result.add(mapper.toDomainMetadata(entity, tagIndex.getOrDefault(id, Set.of())));
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTag(String tagName) {
        return tags.chatIdsWithTag(tagName).size();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return chats.countByDeletedFalse();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> findMessages(String chatId, int page, int size) {
        return messages.findByChatId(chatId, pageable(page, size)).stream()
                .map(mapper::toDomain)
                .toList();
    }

    /**
     * Tombstone em vez de DELETE fisico. O chat some das listagens, mas a linha
     * fica: e o que permite saber, depois, que aquele id ja foi removido.
     *
     * <p>Os <b>bookmarks</b> sao apagados porem. Eles apontam para uma mensagem
     * que ninguem mais alcanca, e o cliente descarta os dele ao aplicar o
     * CHAT_DELETED — sobra-los so criaria orfaos na listagem. As mensagens ficam
     * em disco, protegidas pela retencao.
     */
    @Override
    @Transactional
    public void markDeleted(String id) {
        bookmarks.deleteByChatId(id);
        chats.findById(id).ifPresent(entity -> {
            entity.setDeleted(true);
            chats.save(entity);
        });
    }

    /** Soma de mensagens de todos os chats ativos — usado no actuator/info. */
    @Transactional(readOnly = true)
    public long countMessages() {
        return chats.countActiveMessages();
    }

    private List<Chat> withTags(List<ChatEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        var tagIndex = tags.tagsOf(entities.stream().map(ChatEntity::getId).toList());
        return entities.stream()
                .map(entity -> mapper.toDomainMetadata(entity, tagIndex.getOrDefault(entity.getId(), Set.of())))
                .toList();
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.max(size, 1));
    }

    private static <T> List<T> slice(List<T> values, int page, int size) {
        var safeSize = Math.max(size, 1);
        var from = Math.max(page, 0) * safeSize;
        if (from >= values.size()) {
            return List.of();
        }
        var to = Math.min(from + safeSize, values.size());
        return values.subList(from, to);
    }

    /** Utilitario de infraestrutura para montar o agregado a partir de entidades. */
    public static List<Message> toDomainMessages(List<MessageEntity> entities, ChatMapper mapper) {
        return entities.stream().map(mapper::toDomain).toList();
    }
}
