package com.example.chatreader.chat.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * Hash deterministico de conteudo, usado para idempotencia de importacao e para
 * detectar se uma conversa realmente mudou.
 *
 * <p>A canonicalizacao ordena as mensagens por {@code sequence} (nao por timestamp),
 * porque exports reais costumam ter timestamps ausentes ou duplicados.
 */
public final class ContentHash {

    private static final char SEP = '\0';

    private ContentHash() {
    }

    /** Hash canonico de uma conversa: titulo + sequencia de (sequence, role, hash da mensagem). */
    public static String of(String title, List<Message> messages) {
        var sb = new StringBuilder();
        sb.append(title == null ? "" : title);
        messages.stream()
                .sorted(java.util.Comparator.comparingInt(Message::sequence))
                .forEach(m -> {
                    sb.append(SEP).append(m.sequence());
                    sb.append(SEP).append(m.role().name());
                    sb.append(SEP).append(m.contentHash());
                });
        return sha256(sb.toString());
    }

    /** Hash de uma mensagem isolada: role + conteudo. */
    public static String ofMessage(Role role, String content) {
        return sha256(role.name() + SEP + (content == null ? "" : content));
    }

    public static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nao disponivel nesta JVM", e);
        }
    }
}
