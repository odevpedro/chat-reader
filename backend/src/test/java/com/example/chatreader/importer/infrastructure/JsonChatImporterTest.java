package com.example.chatreader.importer.infrastructure;

import com.example.chatreader.chat.domain.Role;
import com.example.chatreader.importer.domain.ImportException;
import com.example.chatreader.importer.domain.ImportFormat;
import com.example.chatreader.importer.domain.ImportSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonChatImporter — adapter puro, sem banco (ADR-004)")
class JsonChatImporterTest {

    private final JsonChatImporter importer = new JsonChatImporter(new ObjectMapper());

    private java.util.List<com.example.chatreader.importer.domain.NormalizedChat> parse(String json) {
        return importer.importChats(new ImportSource(json, ImportFormat.JSON, "test.json"));
    }

    @Test
    @DisplayName("declara seu formato")
    void declaresFormat() {
        assertThat(importer.format()).isEqualTo(ImportFormat.JSON);
    }

    @Test
    @DisplayName("le o schema documentado em docs/importers.md")
    void readsDocumentedSchema() {
        var json = """
                {
                  "schemaVersion": 1,
                  "chats": [
                    {
                      "id": "c-1",
                      "title": "Spring Transactional",
                      "createdAt": "2026-09-20T14:00:00Z",
                      "updatedAt": "2026-09-27T18:30:00Z",
                      "tags": ["Java", "Spring"],
                      "messages": [
                        { "role": "user", "content": "Como funciona o @Transactional?",
                          "createdAt": "2026-09-20T14:00:00Z" },
                        { "role": "assistant", "content": "Ele delega ao TransactionManager.",
                          "createdAt": "2026-09-20T14:00:30Z" }
                      ]
                    }
                  ]
                }
                """;

        var result = parse(json);

        assertThat(result).hasSize(1);
        var chat = result.getFirst();
        assertThat(chat.externalId()).isEqualTo("c-1");
        assertThat(chat.title()).isEqualTo("Spring Transactional");
        assertThat(chat.createdAt()).isEqualTo(Instant.parse("2026-09-20T14:00:00Z"));
        assertThat(chat.updatedAt()).isEqualTo(Instant.parse("2026-09-27T18:30:00Z"));
        assertThat(chat.tags()).containsExactlyInAnyOrder("Java", "Spring");
        assertThat(chat.messages()).hasSize(2);
        assertThat(chat.messages().get(0).role()).isEqualTo(Role.USER);
        assertThat(chat.messages().get(1).role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    @DisplayName("aceita a chave 'conversations' como alias")
    void acceptsAlias() {
        var result = parse("""
                { "conversations": [ { "title": "T", "messages": [
                    { "role": "user", "content": "oi" } ] } ] }
                """);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("aceita timestamps em epoch (segundos)")
    void acceptsEpochTimestamps() {
        var result = parse("""
                { "chats": [ { "title": "T", "createdAt": 1789000000,
                    "messages": [ { "role": "user", "content": "oi" } ] } ] }
                """);
        assertThat(result.getFirst().createdAt())
                .isEqualTo(Instant.ofEpochMilli(1789000000L * 1000));
    }

    @Test
    @DisplayName("lê o formato aninhado { message: { author: { role }, content: { parts } } }")
    void readsNestedMessageShape() {
        var result = parse("""
                {
                  "chats": [
                    {
                      "title": "Aninhado",
                      "messages": [
                        { "message": { "author": { "role": "assistant" },
                                       "content": { "parts": ["linha 1", "linha 2"] } } }
                      ]
                    }
                  ]
                }
                """);

        var message = result.getFirst().messages().getFirst();
        assertThat(message.role()).isEqualTo(Role.ASSISTANT);
        assertThat(message.content()).isEqualTo("linha 1\nlinha 2");
    }

    @Test
    @DisplayName("lê o formato 'mapping' (objeto, nao array)")
    void readsMappingObject() {
        var result = parse("""
                {
                  "chats": [
                    {
                      "title": "Com mapping",
                      "mapping": {
                        "a": { "message": { "author": { "role": "user" },
                                            "content": { "parts": ["pergunta"] } } },
                        "b": { "message": { "author": { "role": "assistant" },
                                            "content": { "parts": ["resposta"] } } }
                      }
                    }
                  ]
                }
                """);
        assertThat(result.getFirst().messages()).hasSize(2);
    }

    @Test
    @DisplayName("papel desconhecido vira UNKNOWN, sem falhar")
    void unknownRoleBecomesUnknown() {
        var result = parse("""
                { "chats": [ { "title": "T", "messages": [
                    { "role": "xivid", "content": "?" } ] } ] }
                """);
        assertThat(result.getFirst().messages().getFirst().role()).isEqualTo(Role.UNKNOWN);
    }

    @Test
    @DisplayName("chat sem mensagens e pulado, sem derrubar o lote")
    void chatWithoutMessagesIsSkipped() {
        var result = parse("""
                { "chats": [
                    { "title": "Vazia", "messages": [] },
                    { "title": "Boa", "messages": [ { "role": "user", "content": "oi" } ] }
                ] }
                """);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Boa");
    }

    @Test
    @DisplayName("conteudo ausente vira string vazia, nunca null")
    void missingContentBecomesEmptyString() {
        var result = parse("""
                { "chats": [ { "title": "T", "messages": [ { "role": "user" } ] } ] }
                """);
        assertThat(result.getFirst().messages().getFirst().content()).isEmpty();
    }

    @Test
    @DisplayName("titulo ausente usa a primeira linha do conteudo")
    void titleFallsBackToFirstLine() {
        var result = parse("""
                { "chats": [ { "content": "Um titulo\\ncorpo do texto",
                    "messages": [ { "role": "user", "content": "corpo do texto" } ] } ] }
                """);
        assertThat(result.getFirst().title()).isEqualTo("Um titulo");
    }

    @Test
    @DisplayName("rejeita schemaVersion acima do suportado")
    void rejectsFutureSchemaVersion() {
        assertThatThrownBy(() -> parse("""
                { "schemaVersion": 99, "chats": [] }
                """))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("schemaVersion 99");
    }

    @Test
    @DisplayName("rejeita JSON invalido")
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parse("{ isso nao e json "))
                .isInstanceOf(ImportException.class);
    }

    @Test
    @DisplayName("rejeita raiz que nao e objeto com chats")
    void rejectsRootWithoutChats() {
        assertThatThrownBy(() -> parse("{ \"outro\": [] }"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Nenhum array de chats");
    }

    @Test
    @DisplayName("supports() so aceita JSON com array de chats")
    void supportsOnlyRecognizableJson() {
        assertThat(importer.supports(new ImportSource("{\"chats\":[]}", null, "a.json"))).isTrue();
        assertThat(importer.supports(new ImportSource("{\"conversations\":[]}", null, "a.json"))).isTrue();

        assertThat(importer.supports(new ImportSource("{}", null, "a.json"))).isFalse();
        assertThat(importer.supports(new ImportSource("[]", null, "a.json"))).isFalse();
        assertThat(importer.supports(new ImportSource("nao e json", null, "a.txt"))).isFalse();
        assertThat(importer.supports(new ImportSource("", null, "a.txt"))).isFalse();
    }

    @Test
    @DisplayName("supports() nunca lanca, mesmo com lixo")
    void supportsNeverThrows() {
        assertThat(importer.supports(new ImportSource("{\"chats\": \"quebrado\"}", null, "x"))).isTrue();
        assertThat(importer.supports(new ImportSource("\u0000\u0001binario", null, "x"))).isFalse();
    }
}
