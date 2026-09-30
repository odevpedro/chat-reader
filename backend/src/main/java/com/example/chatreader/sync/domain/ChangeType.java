package com.example.chatreader.sync.domain;

/**
 * Tipos de mudanca do change log (secoes 15 e 15.1 da especificacao).
 *
 * <p>Os tipos {@code MESSAGE_*} existem desde ja no enum, mas o MVP nao os emite:
 * um chat alterado chega com o conjunto inteiro de mensagens, que e mais simples
 * e mais barato de aplicar no Kindle do que diffs parciais
 * (docs/sync-protocol.md, secao 4.2).
 */
public enum ChangeType {

    CHAT_CREATED,
    CHAT_UPDATED,
    CHAT_DELETED,

    MESSAGE_CREATED,
    MESSAGE_UPDATED,
    MESSAGE_DELETED,

    TAG_CREATED,
    TAG_UPDATED,
    TAG_DELETED,

    BOOKMARK_CREATED,
    BOOKMARK_UPDATED,
    BOOKMARK_DELETED;

    /** Tipo da entidade afetada, gravado em {@code sync_change_log.entity_type}. */
    public String entityType() {
        return name().substring(0, name().indexOf('_'));
    }

    /** O tipo carrega o estado completo da entidade (payload no change). */
    public boolean carriesPayload() {
        return this != CHAT_DELETED && this != MESSAGE_DELETED && this != TAG_DELETED;
    }
}
