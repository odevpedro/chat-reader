package com.example.chatreader.shared;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalizacao canonica de nomes de tag, compartilhada entre o dominio do chat e
 * o modulo tag. Nao depende de Spring/JPA/Jackson.
 *
 * <p>Regras: remove espacos nas pontas, ignora vazios, limita o tamanho, deduplica
 * de forma case-insensitive ("Java" e "java" sao a mesma tag) preservando a grafia
 * da primeira ocorrencia e devolve em ordem alfabetica estavel.
 */
public final class TagNames {

    public static final int MAX_LENGTH = 100;
    public static final int MAX_COUNT = 64;

    private TagNames() {
    }

    public static Set<String> normalize(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Map<String, String> byKey = new LinkedHashMap<>();
        for (var candidate : raw) {
            if (candidate == null) {
                continue;
            }
            var name = trimToMax(candidate);
            if (name.isEmpty()) {
                continue;
            }
            byKey.putIfAbsent(key(name), name);
            if (byKey.size() >= MAX_COUNT) {
                break;
            }
        }
        // ordena por chave (lowercase) mantendo a grafia original
        var ordered = new LinkedHashSet<String>();
        byKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> ordered.add(e.getValue()));
        return Collections.unmodifiableSet(ordered);
    }

    /** Valida e normaliza uma unica tag; usado no POST /api/tags. */
    public static String require(String name) {
        var normalized = trimToMax(name == null ? "" : name);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Nome de tag nao pode ser vazio");
        }
        return normalized;
    }

    /** Chave case-insensitive usada para deduplicar/persistir. */
    public static String key(String name) {
        return trimToMax(name).toLowerCase(Locale.ROOT);
    }

    private static String trimToMax(String value) {
        var trimmed = value.strip();
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH).strip();
        }
        return trimmed;
    }
}
