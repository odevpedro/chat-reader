package com.example.chatreader.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Autenticacao ligada de verdade (secao 26). A classe base desliga a auth, entao
 * esta declara as proprias propriedades.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("API — autenticacao por token Bearer (secao 26)")
class AuthApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "senha-de-teste-123";
    private static final String PASSWORD_HASH =
            "$2b$10$r06Ejn4Bp0rC0qUnqb72QetW.G9fMLwh8nDrYn04i8QP1Q23/oO4y";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("chatreader.auth.enabled", () -> "true");
        registry.add("chatreader.auth.username", () -> "reader");
        registry.add("chatreader.auth.password-hash", () -> PASSWORD_HASH);
        registry.add("chatreader.auth.jwt-secret",
                () -> "segredo-de-teste-com-tamanho-suficiente-para-hs256-abcdef");
    }

    private String login() throws Exception {
        var response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("username", "reader", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }

    @Test
    @DisplayName("login com credenciais corretas devolve um token")
    void loginReturnsToken() throws Exception {
        var token = login();
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("senha errada devolve 401 e nao emite token")
    void wrongPasswordReturns401() throws Exception {
        var response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("username", "reader", "password", "errada"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("token");
    }

    @Test
    @DisplayName("usuario errado devolve 401")
    void wrongUsernameReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("username", "outro", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sem header Authorization, /api/chats devolve 401")
    void withoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/chats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("missing_token"));
    }

    @Test
    @DisplayName("com header malformado, devolve 401")
    void withMalformedHeaderReturns401() throws Exception {
        mockMvc.perform(get("/api/chats").header("Authorization", "Token abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("com token invalido, devolve 401")
    void withInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/chats").header("Authorization", "Bearer nao.e.um.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("com token valido, /api/chats responde 200")
    void withValidTokenReturns200() throws Exception {
        mockMvc.perform(get("/api/chats").header("Authorization", "Bearer " + login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("a resposta 401 nunca ecoa o token recebido")
    void unauthorizedResponseDoesNotEchoToken() throws Exception {
        var secretToken = "Bearer token-secreto-que-nao-pode-vazar";
        var response = mockMvc.perform(get("/api/chats").header("Authorization", secretToken))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("token-secreto-que-nao-pode-vazar");
    }

    @Test
    @DisplayName("/actuator/health continua publico com auth ligada")
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
