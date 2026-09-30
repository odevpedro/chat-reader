package com.example.chatreader.tag.domain;

/**
 * Tag do catalogo com o id do banco.
 *
 * <p>O id importa para o sync: o payload {@code TAG_CREATED} carrega
 * {@code {id, name}} (docs/sync-protocol.md, secao 4.1).
 */
public record TagRef(String id, String name) {
}
