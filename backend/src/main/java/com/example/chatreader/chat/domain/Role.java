package com.example.chatreader.chat.domain;

/**
 * Papel de uma mensagem. O dominio nao conhece nenhum formato de exportador; e o
 * {@code RoleMapper} de cada adapter que traduz o texto da origem para este enum.
 *
 * <p>{@link #UNKNOWN} e terminal: um papel desconhecido nunca falha a importacao.
 */
public enum Role {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
    UNKNOWN
}
