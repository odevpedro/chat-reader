package com.example.chatreader.tag.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Porta de persistencia do modulo tag. O modulo nao depende do dominio do chat:
 * as associacoes sao feitas por <b>id</b> de chat (String), e o modulo chat usa
 * esta porta para hidratar/reescrever as tags do agregado.
 */
public interface TagRepository {

    /** Catalogo completo, ordenado por nome (case-insensitive). */
    List<String> findAllNames();

    /** Catalogo completo com id, para o payload de sync. */
    List<TagRef> findAll();

    /** Quantidade de tags no catalogo. */
    long count();

    /** Cria a tag pela chave case-insensitive; devolve id e nome gravados. */
    TagRef create(String name);

    /** Procura pela chave case-insensitive do nome. */
    Optional<TagRef> findByName(String name);

    Optional<TagRef> findById(String id);

    /** Resolve varios nomes de uma vez; usado para montar o payload de sync. */
    List<TagRef> findByNames(Collection<String> names);

    /** Tags de um chat, ordenadas por nome. */
    Set<String> tagsOf(String chatId);

    /** Tags de varios chats em um unico round-trip (evita N+1 na listagem). */
    Map<String, Set<String>> tagsOf(Collection<String> chatIds);

    /** Reescreve as associacoes do chat; no-op se o conjunto nao mudou. */
    void replaceChatTags(String chatId, Collection<String> names);

    /** Ids de chats ativos com a tag, mais recentes primeiro (sync/busca). */
    List<String> chatIdsWithTag(String name);
}
