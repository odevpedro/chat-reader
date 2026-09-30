package com.example.chatreader.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TagNames — normalizacao canonica de tags (secao 8)")
class TagNamesTest {

    @Test
    @DisplayName("normaliza lista nula/vazia para conjunto vazio")
    void normalizesEmpty() {
        assertThat(TagNames.normalize(null)).isEmpty();
        assertThat(TagNames.normalize(List.of())).isEmpty();
        assertThat(TagNames.normalize(List.of("", "   "))).isEmpty();
    }

    @Test
    @DisplayName("remove espacos nas pontas e ignora candidatos nulos")
    void trimsAndSkipsNulls() {
        var result = TagNames.normalize(java.util.Arrays.asList("  Java  ", null, " "));
        assertThat(result).containsExactly("Java");
    }

    @Test
    @DisplayName("deduplica case-insensitive preservando a grafia da primeira ocorrencia")
    void deduplicatesCaseInsensitive() {
        var result = TagNames.normalize(List.of("Java", "JAVA", "java"));
        assertThat(result).containsExactly("Java");
    }

    @Test
    @DisplayName("ordena por chave lowercase de forma estavel")
    void sortsByLowercaseKey() {
        var result = TagNames.normalize(List.of("zebra", "Abacate", "banana"));
        assertThat(result).containsExactly("Abacate", "banana", "zebra");
    }

    @Test
    @DisplayName("trunca nomes acima de MAX_LENGTH")
    void truncatesLongNames() {
        var longName = "x".repeat(TagNames.MAX_LENGTH + 25);
        var result = TagNames.normalize(List.of(longName));
        assertThat(result).hasSize(1);
        assertThat(result.iterator().next()).hasSize(TagNames.MAX_LENGTH);
    }

    @Test
    @DisplayName("limita a quantidade a MAX_COUNT")
    void limitsCount() {
        var many = java.util.stream.IntStream.range(0, TagNames.MAX_COUNT + 10)
                .mapToObj(i -> "tag-" + i)
                .toList();
        assertThat(TagNames.normalize(many)).hasSize(TagNames.MAX_COUNT);
    }

    @Test
    @DisplayName("o conjunto devolvido e imutavel")
    void resultIsImmutable() {
        Set<String> result = TagNames.normalize(List.of("Java"));
        assertThatThrownBy(() -> result.add("outra")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("require valida e normaliza uma unica tag")
    void requireNormalizesSingleTag() {
        assertThat(TagNames.require("  Java ")).isEqualTo("Java");
        assertThatThrownBy(() -> TagNames.require("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagNames.require(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("key devolve a chave case-insensitive")
    void keyIsLowercase() {
        assertThat(TagNames.key("  Java ")).isEqualTo("java");
    }
}
