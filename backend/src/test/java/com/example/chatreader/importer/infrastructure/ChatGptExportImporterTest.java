package com.example.chatreader.importer.infrastructure;

import com.example.chatreader.chat.domain.Role;
import com.example.chatreader.importer.domain.ImportException;
import com.example.chatreader.importer.domain.ImportFormat;
import com.example.chatreader.importer.domain.ImportSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChatGptExportImporter — conversations.json do export real (ADR-004)")
class ChatGptExportImporterTest {

    private final ChatGptExportImporter importer = new ChatGptExportImporter(new ObjectMapper());

    private List<com.example.chatreader.importer.domain.NormalizedChat> parse(String json) {
        return importer.importChats(new ImportSource(json, ImportFormat.CHATGPT_EXPORT, "conversations.json"));
    }

    /** Export minimo mas fiel: array na raiz, mapping em arvore, epoch fracionario. */
    private static final String EXPORT = """
            [
              {
                "title": "Spring Transactional",
                "create_time": 1758400000.0,
                "update_time": 1758403600.0,
                "conversation_id": "6f1c-abc",
                "current_node": "n3",
                "mapping": {
                  "root": { "id": "root", "message": null, "children": ["n1", "n2", "n3"] },
                  "n1": {
                    "id": "n1",
                    "message": {
                      "id": "m1",
                      "author": { "role": "system" },
                      "create_time": 1758400000.0,
                      "content": { "content_type": "text", "parts": ["Voce e um assistente."] }
                    }
                  },
                  "n2": {
                    "id": "n2",
                    "message": {
                      "id": "m2",
                      "author": { "role": "user" },
                      "create_time": 1758400100.0,
                      "content": { "content_type": "text", "parts": ["Como funciona o @Transactional?"] }
                    }
                  },
                  "n3": {
                    "id": "n3",
                    "message": {
                      "id": "m3",
                      "author": { "role": "assistant" },
                      "create_time": 1758400200.0,
                      "content": { "content_type": "text", "parts": ["Ele delega ao TransactionManager."] }
                    }
                  }
                }
              }
            ]
            """;

    @Test
    @DisplayName("declara seu formato")
    void declaresFormat() {
        assertThat(importer.format()).isEqualTo(ImportFormat.CHATGPT_EXPORT);
    }

    @Test
    @DisplayName("le o export real: ordem cronologica, sem o no de sistema")
    void readsRealExport() {
        var result = parse(EXPORT);

        assertThat(result).hasSize(1);
        var chat = result.getFirst();
        assertThat(chat.externalId()).isEqualTo("6f1c-abc");
        assertThat(chat.title()).isEqualTo("Spring Transactional");
        assertThat(chat.createdAt()).isEqualTo(Instant.ofEpochMilli(1758400000_000L));
        assertThat(chat.updatedAt()).isEqualTo(Instant.ofEpochMilli(1758403600_000L));

        // o no de sistema (preambulo do modelo) nao e' conversa
        assertThat(chat.messages()).hasSize(2);
        assertThat(chat.messages().get(0).role()).isEqualTo(Role.USER);
        assertThat(chat.messages().get(0).externalId()).isEqualTo("m2");
        assertThat(chat.messages().get(0).createdAt())
                .isEqualTo(Instant.ofEpochMilli(1758400100_000L));
        assertThat(chat.messages().get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(chat.messages().get(1).content())
                .isEqualTo("Ele delega ao TransactionManager.");
    }

    @Test
    @DisplayName("reconhece pelo array na raiz com 'mapping'")
    void detectsBySignature() {
        assertThat(importer.supports(new ImportSource(EXPORT, null, "conversations.json"))).isTrue();
    }

    @Test
    @DisplayName("nao sequestra o JSON generico, que tem objeto na raiz")
    void doesNotClaimGenericJson() {
        var json = """
                { "chats": [ { "title": "x", "messages": [ { "role": "user", "content": "oi" } ] } ] }
                """;

        assertThat(importer.supports(new ImportSource(json, null, "chats.json"))).isFalse();
    }

    @Test
    @DisplayName("ordena por create_time, e nao pela ordem das chaves do mapa")
    void sortsByCreateTimeNotMapOrder() {
        var export = """
                [
                  {
                    "title": "Desordenada",
                    "conversation_id": "c1",
                    "mapping": {
                      "z": { "id": "z", "message": { "author": { "role": "assistant" },
                            "create_time": 200.0, "content": { "parts": ["segunda"] } } },
                      "a": { "id": "a", "message": { "author": { "role": "user" },
                            "create_time": 100.0, "content": { "parts": ["primeira"] } } }
                    }
                  }
                ]
                """;

        var messages = parse(export).getFirst().messages();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo("primeira");
        assertThat(messages.get(1).content()).isEqualTo("segunda");
    }

    @Test
    @DisplayName("nos visualmente ocultos saem; os demais ramos entram")
    void dropsHiddenNodesKeepsBranches() {
        var export = """
                [
                  {
                    "title": "Com ramo",
                    "conversation_id": "c2",
                    "mapping": {
                      "h": { "id": "h", "message": { "author": { "role": "user" },
                            "create_time": 100.0,
                            "metadata": { "is_visually_hidden_from_conversation": true },
                            "content": { "parts": ["oculta"] } } },
                      "u": { "id": "u", "message": { "author": { "role": "user" },
                            "create_time": 200.0, "content": { "parts": ["pergunta"] } } },
                      "b1": { "id": "b1", "message": { "author": { "role": "assistant" },
                            "create_time": 300.0, "content": { "parts": ["resposta A"] } } },
                      "b2": { "id": "b2", "message": { "author": { "role": "assistant" },
                            "create_time": 400.0, "content": { "parts": ["resposta B"] } } }
                    }
                  }
                ]
                """;

        var messages = parse(export).getFirst().messages();

        assertThat(messages).extracting(m -> m.content())
                .containsExactly("pergunta", "resposta A", "resposta B");
    }

    @Test
    @DisplayName("conteudo nao textual vira o texto das partes")
    void readsNonTextContentAsParts() {
        var export = """
                [
                  {
                    "title": "Codigo",
                    "conversation_id": "c3",
                    "mapping": {
                      "n": { "id": "n", "message": { "author": { "role": "assistant" },
                            "create_time": 100.0,
                            "content": { "content_type": "code",
                                         "text": "def run(): pass",
                                         "parts": ["def run(): pass"] } } }
                    }
                  }
                ]
                """;

        assertThat(parse(export).getFirst().messages().getFirst().content())
                .isEqualTo("def run(): pass");
    }

    @Test
    @DisplayName("varias conversas no array, uma ruim nao derruba o lote")
    void severalChatsAndOneBroken() {
        var export = """
                [
                  {
                    "title": "Boa",
                    "conversation_id": "ok1",
                    "mapping": {
                      "n": { "id": "n", "message": { "author": { "role": "user" },
                            "create_time": 100.0, "content": { "parts": ["oi"] } } }
                    }
                  },
                  { "title": "Vazia", "conversation_id": "vazia", "mapping": {} },
                  {
                    "title": "Sem mapping",
                    "conversation_id": "sem"
                  },
                  {
                    "title": "Outra boa",
                    "conversation_id": "ok2",
                    "mapping": {
                      "n": { "id": "n", "message": { "author": { "role": "user" },
                            "create_time": 200.0, "content": { "parts": ["ola"] } } }
                    }
                  }
                ]
                """;

        var result = parse(export);

        assertThat(result).extracting(c -> c.externalId()).containsExactly("ok1", "ok2");
    }

    @Test
    @DisplayName("sem titulo usa 'Conversa sem titulo'")
    void defaultsTitle() {
        var export = """
                [
                  {
                    "conversation_id": "s- titulo",
                    "mapping": {
                      "n": { "id": "n", "message": { "author": { "role": "user" },
                            "create_time": 100.0, "content": { "parts": ["oi"] } } }
                    }
                  }
                ]
                """;

        assertThat(parse(export).getFirst().title()).isEqualTo("Conversa sem titulo");
    }

    @Test
    @DisplayName("objeto na raiz da ImportException apontando o JSON generico")
    void objectRootFailsWithPointer() {
        var json = "{ \"chats\": [] }";

        assertThatThrownBy(() -> parse(json))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("JSON generico");
    }
}
