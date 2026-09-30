package com.example.chatreader.importer.infrastructure;

import com.example.chatreader.chat.domain.Role;
import com.example.chatreader.importer.domain.ImportException;
import com.example.chatreader.importer.domain.ImportFormat;
import com.example.chatreader.importer.domain.ImportSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MarkdownChatImporter — adapter puro, sem banco (ADR-004)")
class MarkdownChatImporterTest {

    private final MarkdownChatImporter importer = new MarkdownChatImporter();

    private List<com.example.chatreader.importer.domain.NormalizedChat> parse(String markdown) {
        return importer.importChats(new ImportSource(markdown, ImportFormat.MARKDOWN, "test.md"));
    }

    @Test
    @DisplayName("declara seu formato")
    void declaresFormat() {
        assertThat(importer.format()).isEqualTo(ImportFormat.MARKDOWN);
    }

    @Test
    @DisplayName("le o schema documentado em docs/importers.md")
    void readsDocumentedSchema() {
        var markdown = """
                ---
                id: spring-transactional
                title: Spring Transactional
                createdAt: 2026-09-20T14:00:00Z
                updatedAt: 2026-09-27T18:30:00Z
                tags: [Java, Spring]
                ---

                ## VOCE

                Como funciona o `@Transactional`?

                <!-- 2026-09-20T14:00:00Z -->

                ## CHATGPT

                Ele delega ao `TransactionManager`.
                """;

        var result = parse(markdown);

        assertThat(result).hasSize(1);
        var chat = result.getFirst();
        assertThat(chat.externalId()).isEqualTo("spring-transactional");
        assertThat(chat.title()).isEqualTo("Spring Transactional");
        assertThat(chat.createdAt()).isEqualTo(Instant.parse("2026-09-20T14:00:00Z"));
        assertThat(chat.updatedAt()).isEqualTo(Instant.parse("2026-09-27T18:30:00Z"));
        assertThat(chat.tags()).containsExactlyInAnyOrder("Java", "Spring");
        assertThat(chat.messages()).hasSize(2);
        assertThat(chat.messages().getFirst().role()).isEqualTo(Role.USER);
        assertThat(chat.messages().getFirst().content()).isEqualTo("Como funciona o `@Transactional`?");
        assertThat(chat.messages().getFirst().createdAt())
                .isEqualTo(Instant.parse("2026-09-20T14:00:00Z"));
        assertThat(chat.messages().get(1).role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    @DisplayName("reconhece o arquivo pelo cabecalho e pelas secoes de papel")
    void detectsBySignature() {
        var markdown = """
                # Conversa

                ## VOCE

                Oi
                """;

        assertThat(importer.supports(new ImportSource(markdown, null, "a.md"))).isTrue();
    }

    @Test
    @DisplayName("nao sequestra JSON — quem cuida dele e' o JsonChatImporter")
    void doesNotClaimJson() {
        var json = """
                { "chats": [ { "title": "x", "messages": [ { "role": "user", "content": "oi" } ] } ] }
                """;

        assertThat(importer.supports(new ImportSource(json, null, "a.json"))).isFalse();
    }

    @Test
    @DisplayName("texto sem nenhuma secao de papel nao e' Markdown de conversa")
    void rejectsProseWithoutRoles() {
        var prose = "# Um doc\n\nSo um paragrafo solto, sem conversa.\n";

        assertThat(importer.supports(new ImportSource(prose, null, "doc.md"))).isFalse();
    }

    @Test
    @DisplayName("varias conversas no mesmo arquivo, separadas por '#'")
    void readsSeveralChats() {
        var markdown = """
                # Primeira

                ## VOCE

                primeira pergunta

                ## CHATGPT

                primeira resposta

                # Segunda

                ## VOCE

                segunda pergunta
                """;

        var result = parse(markdown);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).title()).isEqualTo("Primeira");
        assertThat(result.get(0).messages()).hasSize(2);
        assertThat(result.get(1).title()).isEqualTo("Segunda");
        assertThat(result.get(1).messages()).hasSize(1);
    }

    @Test
    @DisplayName("front-matter ausente ainda funciona: titulo vem do '#'")
    void worksWithoutFrontMatter() {
        var markdown = """
                # Refatoracao de legado

                ## VOCE

                Comouco com codigo legado?

                ## CHATGPT

                Comece pelos testes.
                """;

        var chat = parse(markdown).getFirst();

        assertThat(chat.title()).isEqualTo("Refatoracao de legado");
        assertThat(chat.externalId()).isNull();
        assertThat(chat.createdAt()).isNull();
        assertThat(chat.tags()).isEmpty();
    }

    @Test
    @DisplayName("sem titulo, usa a primeira linha da primeira mensagem")
    void fallsBackToFirstMessageLine() {
        var markdown = """
                ## VOCE

                Como funciona o lock otimista?

                ## CHATGPT

                Com versionamento na escrita.
                """;

        var chat = parse(markdown).getFirst();

        assertThat(chat.title()).isEqualTo("Como funciona o lock otimista?");
    }

    @Test
    @DisplayName("papel desconhecido vira UNKNOWN e a importacao nao falha")
    void unknownRoleBecomesUnknown() {
        var markdown = """
                # Curiosa

                ## system

                Eu sou um sistema.

                ## CRITICO

                E eu critico.
                """;

        var chat = parse(markdown).getFirst();

        assertThat(chat.messages()).hasSize(2);
        assertThat(chat.messages().get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(chat.messages().get(1).role()).isEqualTo(Role.UNKNOWN);
    }

    @Test
    @DisplayName("Markdown interno e' preservado cru (o backend nao renderiza)")
    void keepsMarkdownRaw() {
        var markdown = """
                # Codigo

                ## VOCE

                ## CHATGPT

                ```java
                @Transactional
                public void run() {}
                ```

                - item um
                - item dois
                """;

        var content = parse(markdown).getFirst().messages().getFirst().content();

        assertThat(content).contains("@Transactional", "```java", "- item um", "- item dois");
    }

    @Test
    @DisplayName("tags aceitam [a, b] e a, b")
    void readsBothTagStyles() {
        var markdown = """
                # Com colchetes
                ---
                tags: [Java, Spring Boot]
                ---

                ## VOCE

                oi

                # Sem colchetes
                ---
                tags: Java, Spring Boot
                ---

                ## VOCE

                oi
                """;

        var result = parse(markdown);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).tags()).containsExactlyInAnyOrder("Java", "Spring Boot");
        assertThat(result.get(1).tags()).containsExactlyInAnyOrder("Java", "Spring Boot");
    }

    @Test
    @DisplayName("'---' no meio do corpo e' regua horizontal, nao novo front-matter")
    void thematicBreakIsNotFrontMatter() {
        var markdown = """
                ---
                title: Com regua
                ---

                ## VOCE

                oi

                ---

                ## CHATGPT

                ola
                """;

        var result = parse(markdown);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().messages()).hasSize(2);
        assertThat(result.getFirst().messages().getFirst().content()).contains("---");
    }

    @Test
    @DisplayName("arquivo sem nenhuma conversa da ImportException com o formato esperado")
    void failsWithClearMessage() {
        assertThatThrownBy(() -> parse("apenas um texto solto\n"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("'## PAPEL'");
    }

    @Test
    @DisplayName("data local e epoch em string viram o mesmo instante")
    void readsBothDateStyles() {
        var markdown = """
                ---
                title: Datas
                createdAt: 2026-09-20
                updatedAt: 1758400000
                ---

                ## VOCE

                oi
                """;

        var chat = parse(markdown).getFirst();

        assertThat(chat.createdAt()).isEqualTo(Instant.parse("2026-09-20T00:00:00Z"));
        assertThat(chat.updatedAt()).isEqualTo(Instant.ofEpochMilli(1758400000_000L));
    }
}
