package com.example.chatreader.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Busca full-text (secao 13)")
class SearchApiIT extends AbstractIntegrationTest {

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

    private void importChat(String externalId, String title, String question) throws Exception {
        var json = """
                {
                  "schemaVersion": 1,
                  "chats": [
                    {
                      "id": "%s",
                      "title": "%s",
                      "messages": [
                        { "role": "user", "content": "%s" },
                        { "role": "assistant", "content": "resposta comum" }
                      ]
                    }
                  ]
                }
                """.formatted(externalId, title, question);
        mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "chats.json", "application/json",
                                json.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());
    }

    private void importChatWithTag(String externalId, String title, String question, String tag)
            throws Exception {
        var json = """
                {
                  "schemaVersion": 1,
                  "chats": [
                    {
                      "id": "%s",
                      "title": "%s",
                      "tags": ["%s"],
                      "messages": [
                        { "role": "user", "content": "%s" }
                      ]
                    }
                  ]
                }
                """.formatted(externalId, title, tag, question);
        mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "chats.json", "application/json",
                                json.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/chats/search casa conteudo e devolve snippet em texto puro")
    void searchesContent() throws Exception {
        importChat("c-1", "Kotlin basico", "aprendendo coroutines em kotlin");
        importChat("c-2", "Java basico", "aprendendo threads em java");

        mockMvc.perform(get("/api/chats/search").param("q", "coroutines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("coroutines"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].snippet", containsString("coroutines")))
                .andExpect(jsonPath("$.results[0].snippet", not(containsString("<mark>"))));
    }

    @Test
    @DisplayName("a busca tambem casa pelo titulo do chat")
    void searchesTitle() throws Exception {
        importChat("c-1", "Receita de bolo", "ingredientes e preparo");

        mockMvc.perform(get("/api/chats/search").param("q", "receita"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Receita de bolo"));
    }

    @Test
    @DisplayName("a busca tambem casa por tag (secao 13)")
    void searchesTag() throws Exception {
        importChatWithTag("c-1", "Conversa qualquer", "conteudo sem relacao", "receitas");

        mockMvc.perform(get("/api/chats/search").param("q", "receitas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].title").value("Conversa qualquer"));
    }

    @Test
    @DisplayName("chat com match no conteudo aparece uma unica vez por mensagem")
    void contentMatchIsPerMessage() throws Exception {
        importChat("c-1", "Repetido", "kotlin kotlin");

        mockMvc.perform(get("/api/chats/search").param("q", "kotlin"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    @DisplayName("sem correspondencia devolve total 0 e lista vazia")
    void noMatchReturnsEmpty() throws Exception {
        importChat("c-1", "Kotlin", "aprendendo coroutines");

        mockMvc.perform(get("/api/chats/search").param("q", "zzzznaoexiste"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    @DisplayName("parametro q obrigatorio: ausente devolve 400")
    void missingQueryReturns400() throws Exception {
        mockMvc.perform(get("/api/chats/search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a rota /search nao e capturada por /api/chats/{id}")
    void searchRouteIsNotShadowed() throws Exception {
        var response = mockMvc.perform(get("/api/chats/search").param("q", "x"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(objectMapper.readTree(response).has("query")).isTrue();
    }

    @Test
    @DisplayName("a busca ignora chats removidos")
    void ignoresDeletedChats() throws Exception {
        importChat("c-1", "Kotlin", "aprendendo coroutines");
        var id = jdbc.queryForObject("SELECT id FROM chats WHERE external_id = 'c-1'", String.class);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/chats/{id}", id)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/chats/search").param("q", "coroutines"))
                .andExpect(jsonPath("$.total").value(0));
    }
}
