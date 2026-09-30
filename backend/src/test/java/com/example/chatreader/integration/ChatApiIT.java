package com.example.chatreader.integration;

import com.example.chatreader.importer.domain.ImportSource;
import com.example.chatreader.shared.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("API REST — listagem, detalhe, paginacao, importacao e remocao (secao 14)")
class ChatApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE messages, chats CASCADE");
    }

    private String importFileAndReturnChatId(String json) throws Exception {
        var response = mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "chats.json",
                                "application/json", json.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var body = objectMapper.readTree(response);
        assertThat(body.path("created").asInt()).isEqualTo(1);
        return jdbc.queryForObject("SELECT id FROM chats LIMIT 1", String.class);
    }

    @Test
    @DisplayName("POST /api/chats/import importa e devolve contadores")
    void importReturnsCounters() throws Exception {
        var json = sampleJson("c-1", "Como funciona o @Transactional?");

        mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "chats.json",
                                "application/json", json.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    @DisplayName("importar duas vezes responde 0 created (idempotencia na API)")
    void importTwiceIsIdempotentThroughApi() throws Exception {
        var json = sampleJson("c-1", "pergunta");
        var file = new MockMultipartFile("file", "chats.json", "application/json",
                json.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/chats/import").file(file))
                .andExpect(jsonPath("$.created").value(1));
        mockMvc.perform(multipart("/api/chats/import").file(new MockMultipartFile("file",
                        "chats.json", "application/json", json.getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(1));
    }

    @Test
    @DisplayName("GET /api/chats/{id} devolve a conversa com as mensagens")
    void getChatReturnsMessages() throws Exception {
        var id = importFileAndReturnChatId(sampleJson("c-1", "pergunta"));

        mockMvc.perform(get("/api/chats/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat.id").value(id))
                .andExpect(jsonPath("$.chat.title").value("Spring Transactional"))
                .andExpect(jsonPath("$.chat.messageCount").value(2))
                .andExpect(jsonPath("$.chat.favorite").value(false))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].sequence").value(0))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[1].sequence").value(1));
    }

    @Test
    @DisplayName("GET /api/chats/{id} com id inexistente devolve 404")
    void getUnknownChatReturns404() throws Exception {
        mockMvc.perform(get("/api/chats/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/chats pagina e informa o total")
    void listIsPaginated() throws Exception {
        for (int i = 0; i < 5; i++) {
            importFileAndReturnChatId(sampleJson("c-" + i, "pergunta " + i));
        }

        var response = mockMvc.perform(get("/api/chats").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.last").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var body = objectMapper.readTree(response);
        assertThat(body.path("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("a ultima pagina sinaliza last=true")
    void lastPageIsFlagged() throws Exception {
        for (int i = 0; i < 3; i++) {
            importFileAndReturnChatId(sampleJson("c-" + i, "p" + i));
        }

        mockMvc.perform(get("/api/chats").param("size", "10"))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("a listagem nao carrega o conteudo das mensagens")
    void listDoesNotIncludeMessages() throws Exception {
        importFileAndReturnChatId(sampleJson("c-1", "pergunta"));

        var response = mockMvc.perform(get("/api/chats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var body = objectMapper.readTree(response);
        var item = body.path("items").get(0);
        assertThat(item.has("messages")).isFalse();
        assertThat(item.path("messageCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("DELETE /api/chats/{id} marca o chat como removido (tombstone)")
    void deleteMarksChatAsDeleted() throws Exception {
        var id = importFileAndReturnChatId(sampleJson("c-1", "pergunta"));

        mockMvc.perform(delete("/api/chats/{id}", id)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/chats/{id}", id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/chats")).andExpect(jsonPath("$.total").value(0));
        // a linha e as mensagens ficam: e o que permite a um cliente atrasado
        // receber o CHAT_DELETED em vez de nao descobrir nada
        assertThat(jdbc.queryForObject("SELECT deleted FROM chats WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE chat_id = ?", Long.class, id))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("DELETE de chat inexistente devolve 404")
    void deleteUnknownReturns404() throws Exception {
        mockMvc.perform(delete("/api/chats/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("arquivo JSON invalido devolve 422 com motivo")
    void invalidJsonReturns422() throws Exception {
        mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "quebrado.json", "application/json",
                                "{ nao é json".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("formato nao suportado devolve 422 listando os formatos")
    void unknownFormatReturns422() throws Exception {
        mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf",
                                "%PDF-1.4".getBytes(StandardCharsets.UTF_8)))
                        .param("format", "PDF"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PDF")));
    }

    @Test
    @DisplayName("GET /api/chats/import/formats lista o que o import layer entende")
    void formatsEndpointListsAdapters() throws Exception {
        // a lista vem ordenada por nome (ChatImporterRegistry.supportedFormats), entao o
        // teste verifica o conjunto e nao a posicao: adicionar um adapter nao pode
        // quebrar o teste por causa de alfabetizacao
        mockMvc.perform(get("/api/chats/import/formats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats").isArray())
                .andExpect(jsonPath("$.formats",
                        org.hamcrest.Matchers.containsInAnyOrder("JSON", "MARKDOWN", "CHATGPT_EXPORT")));
    }

    @Test
    @DisplayName("size acima do maximo devolve 400")
    void sizeAboveLimitReturns400() throws Exception {
        mockMvc.perform(get("/api/chats").param("size", "5000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a API recusa escrever conteudo de mensagem (ADR-005)")
    void hasNoEndpointToWriteMessageContent() throws Exception {
        // Nao existe POST/PUT de mensagens; a documentacao OpenAPI confirma.
        var apiDocs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode paths = objectMapper.readTree(apiDocs).path("paths");
        var messageWrites = 0;
        var it = paths.fieldNames();
        while (it.hasNext()) {
            var path = it.next();
            if (path.contains("/messages")) {
                var methods = paths.get(path).fieldNames();
                while (methods.hasNext()) {
                    var m = methods.next();
                    if (m.equals("post") || m.equals("put") || m.equals("patch")) {
                        messageWrites++;
                    }
                }
            }
        }
        assertThat(messageWrites).isZero();
    }

    @Test
    @DisplayName("PageResponse expoe totalPages")
    void pageResponseHasTotalPages() {
        var page = new PageResponse<String>(java.util.List.of("a", "b"), 0, 2, 5, false);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    private static String sampleJson(String externalId, String question) {
        return """
                {
                  "schemaVersion": 1,
                  "chats": [
                    {
                      "id": "%s",
                      "title": "Spring Transactional",
                      "createdAt": "2026-09-20T14:00:00Z",
                      "updatedAt": "2026-09-27T18:30:00Z",
                      "tags": ["Java", "Spring"],
                      "messages": [
                        { "role": "user", "content": "%s", "createdAt": "2026-09-20T14:00:00Z" },
                        { "role": "assistant", "content": "Resposta com **markdown** e `codigo`.",
                          "createdAt": "2026-09-20T14:00:30Z" }
                      ]
                    }
                  ]
                }
                """.formatted(externalId, question);
    }
}
