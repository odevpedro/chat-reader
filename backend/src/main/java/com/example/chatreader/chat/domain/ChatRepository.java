package com.example.chatreader.chat.domain;

import java.util.List;
import java.util.Optional;

/**
 * Porta de persistencia do agregado {@link Chat}. Implementada em
 * {@code chat.infrastructure} (ADR-001: modulos nao se referenciam diretamente;
 * a dependencia e contra esta interface).
 */
public interface ChatRepository {

    Optional<Chat> findById(String id);

    /** Igual a {@link #findById}, mas sem carregar as mensagens. */
    Optional<Chat> findMetadataById(String id);

    boolean existsById(String id);

    /** Busca por identidade externa, base da idempotencia de importacao. */
    Optional<Chat> findBySourceAndExternalId(ChatSource source, String externalId);

    /** Busca por hash de conteudo, para chats sem externalId (ex.: Markdown). */
    Optional<Chat> findBySourceAndContentHash(ChatSource source, String contentHash);

    void save(Chat chat);

    void saveAll(List<Chat> chats);

    /** Listagem paginada de chats ativos, mais recentes primeiro. */
    List<Chat> findAll(int page, int size);

    /** Listagem paginada apenas dos favoritos. */
    List<Chat> findFavorites(int page, int size);

    long countFavorites();

    /** Listagem paginada dos chats que possuem a tag (por nome case-insensitive). */
    List<Chat> findByTag(String tagName, int page, int size);

    long countByTag(String tagName);

    long count();

    /**
     * Pagina de mensagens de um chat, sem carregar a conversa inteira — usado
     * pelo leitor do Kindle e por {@code messagesOmitted} no sync.
     */
    List<Message> findMessages(String chatId, int page, int size);

    /**
     * Tombstone: marca o chat como removido, sem apagar a linha. O cliente
     * precisa aprender que o chat sumiu (docs/sync-protocol.md, secao 6), e as
     * mensagens continuam em disco ate a poda.
     */
    void markDeleted(String id);
}
