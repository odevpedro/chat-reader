package com.example.chatreader.importer.domain;

import java.util.Objects;

/**
 * Entrada crua de uma importacao: os bytes do arquivo e o formato declarado
 * (opcional — quando ausente, o {@code ChatImporterRegistry} detecta por assinatura).
 */
public record ImportSource(byte[] content, ImportFormat declaredFormat, String filename) {

    public ImportSource {
        Objects.requireNonNull(content, "content nao pode ser nulo");
    }

    public ImportSource(String content, ImportFormat declaredFormat, String filename) {
        this(content.getBytes(java.nio.charset.StandardCharsets.UTF_8), declaredFormat, filename);
    }

    public String asString() {
        return new String(content, java.nio.charset.StandardCharsets.UTF_8);
    }
}
