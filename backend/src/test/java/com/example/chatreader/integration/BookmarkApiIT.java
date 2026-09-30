package com.example.chatreader.integration;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Bookmarks de mensagens (secao 10)")
class BookmarkApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE bookmarks, chat_tags, tags, messages, chats CASCADE");
    }

    private String importChat(String externalId) throws Exception {
        var json = """
                {
                  "schemaVersion": 1,
                  "chats": [
                    {
                      "id": "%s",
                      "title": "Conversa bookmark",
                      "messages": [
                        { "role": "user", "content": "primeira pergunta" },
                        { "role": "assistant", "content": "resposta util" }
                      ]
                    }
                  ]
                }
                """.formatted(externalId);
        mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "chats.json", "application/json",
                                json.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM chats WHERE external_id = ?", String.class, externalId);
    }

    private String firstMessageId(String chatId) {
        return jdbc.queryForObject(
                "SELECT id FROM messages WHERE chat_id = ? ORDER BY sequence LIMIT 1", String.class, chatId);
    }

    @Test
    @DisplayName("POST /api/bookmarks cria e GET lista por chat")
    void createAndList() throws Exception {
        var chatId = importChat("c-1");
        var messageId = firstMessageId(chatId);

        mockMvc.perform(post("/api/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"" + chatId + "\",\"messageId\":\"" + messageId
                                + "\",\"note\":\"rever depois\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chatId").value(chatId))
                .andExpect(jsonPath("$.messageId").value(messageId))
                .andExpect(jsonPath("$.note").value("rever depois"));

        mockMvc.perform(get("/api/bookmarks").param("chatId", chatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].messageId").value(messageId));
    }

    @Test
    @DisplayName("bookmark duplicado para a mesma mensagem devolve 409")
    void duplicateReturns409() throws Exception {
        var chatId = importChat("c-1");
        var messageId = firstMessageId(chatId);
        var body = "{\"chatId\":\"" + chatId + "\",\"messageId\":\"" + messageId + "\"}";

        mockMvc.perform(post("/api/bookmarks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/bookmarks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("bookmark de mensagem inexistente devolve 404")
    void unknownMessageReturns404() throws Exception {
        var chatId = importChat("c-1");

        mockMvc.perform(post("/api/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"" + chatId + "\",\"messageId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("bookmark de chat inexistente devolve 404")
    void unknownChatReturns404() throws Exception {
        mockMvc.perform(post("/api/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"" + UUID.randomUUID() + "\",\"messageId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/bookmarks/{id} remove; id inexistente devolve 404")
    void deleteRemoves() throws Exception {
        var chatId = importChat("c-1");
        var messageId = firstMessageId(chatId);

        var created = mockMvc.perform(post("/api/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"" + chatId + "\",\"messageId\":\"" + messageId + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("id").asText();

        mockMvc.perform(delete("/api/bookmarks/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/bookmarks").param("chatId", chatId))
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(delete("/api/bookmarks/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deletar o chat remove os bookmarks dele")
    void deletingChatCascades() throws Exception {
        var chatId = importChat("c-1");
        var messageId = firstMessageId(chatId);
        mockMvc.perform(post("/api/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"" + chatId + "\",\"messageId\":\"" + messageId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/chats/{id}", chatId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/bookmarks").param("chatId", chatId))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("nota longa e truncada em 1000 caracteres")
    void longNoteIsTruncated() throws Exception {
        var chatId = importChat("c-1");
        var messageId = firstMessageId(chatId);
        var longNote = "n".repeat(1200);

        mockMvc.perform(post("/api/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"" + chatId + "\",\"messageId\":\"" + messageId
                                + "\",\"note\":\"" + longNote + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.hasLength(1000)));
    }
}
