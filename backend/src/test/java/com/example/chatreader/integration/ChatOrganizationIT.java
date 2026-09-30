package com.example.chatreader.integration;

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
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Organizacao — favoritos e tags (secoes 8 e 9)")
class ChatOrganizationIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE bookmarks, chat_tags, tags, messages, chats CASCADE");
    }

    private String importChat(String externalId, String question, String tags) throws Exception {
        var json = """
                {
                  "schemaVersion": 1,
                  "chats": [
                    {
                      "id": "%s",
                      "title": "Conversa %s",
                      "updatedAt": "2026-09-27T18:30:00Z",
                      "tags": [%s],
                      "messages": [
                        { "role": "user", "content": "%s" },
                        { "role": "assistant", "content": "resposta" }
                      ]
                    }
                  ]
                }
                """.formatted(externalId, externalId, tags, question);
        mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "chats.json", "application/json",
                                json.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM chats WHERE external_id = ?", String.class, externalId);
    }

    @Test
    @DisplayName("tags importadas aparecem normalizadas no detalhe e na listagem")
    void importedTagsAppearNormalized() throws Exception {
        var id = importChat("c-1", "pergunta", "\"Spring\", \"java\"");

        mockMvc.perform(get("/api/chats/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chat.tags", contains("java", "Spring")));

        mockMvc.perform(get("/api/chats"))
                .andExpect(jsonPath("$.items[0].tags", contains("java", "Spring")));
    }

    @Test
    @DisplayName("PUT /api/chats/{id}/favorite marca e desmarca, e filtra a listagem")
    void favoriteIsToggledAndFiltered() throws Exception {
        var id = importChat("c-1", "pergunta", "\"java\"");

        mockMvc.perform(put("/api/chats/{id}/favorite", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favorite\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(true));

        mockMvc.perform(get("/api/chats").param("favorite", "true"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));

        mockMvc.perform(put("/api/chats/{id}/favorite", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favorite\":false}"))
                .andExpect(jsonPath("$.favorite").value(false));

        mockMvc.perform(get("/api/chats").param("favorite", "true"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("GET /api/chats?tag= filtra por tag case-insensitive")
    void listFiltersByTagCaseInsensitive() throws Exception {
        importChat("c-1", "com tag", "\"java\"");
        importChat("c-2", "sem tag", "");

        mockMvc.perform(get("/api/chats").param("tag", "JAVA"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].tags", contains("java")));
    }

    @Test
    @DisplayName("PUT /api/chats/{id}/tags substitui o conjunto atual")
    void putTagsReplacesSet() throws Exception {
        var id = importChat("c-1", "pergunta", "\"java\"");

        mockMvc.perform(put("/api/chats/{id}/tags", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"Kotlin\",\"java\",\"kotlin\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", contains("java", "Kotlin")));

        mockMvc.perform(get("/api/chats/{id}", id))
                .andExpect(jsonPath("$.chat.tags", contains("java", "Kotlin")));
    }

    @Test
    @DisplayName("PUT tags em chat inexistente devolve 404")
    void putTagsUnknownChatReturns404() throws Exception {
        mockMvc.perform(put("/api/chats/{id}/tags", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"java\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("catalogo de tags lista as tags criadas e o POST e idempotente")
    void tagCatalogIsIdempotent() throws Exception {
        importChat("c-1", "pergunta", "\"Spring\", \"java\"");

        mockMvc.perform(get("/api/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", contains("java", "Spring")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Go\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Go"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"go\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Go"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tags WHERE normalized_name = 'go'", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /api/tags com nome em branco devolve 400")
    void createTagBlankReturns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reimportar mantendo tags nao duplica tags (idempotencia)")
    void reimportKeepsTagsIdempotent() throws Exception {
        importChat("c-1", "pergunta", "\"java\"");
        importChat("c-1", "pergunta", "\"java\"");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_tags", Long.class)).isEqualTo(1);
    }
}
