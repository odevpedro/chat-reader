package com.example.chatreader.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("API — documentacao, saude e autenticacao (secoes 26/27/36)")
class PlatformApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET /actuator/health responde ok")
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /v3/api-docs documenta os endpoints do MVP")
    void openApiDocumentsEndpoints() throws Exception {
        var response = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var paths = objectMapper.readTree(response).path("paths");
        assertThat(paths.has("/api/chats")).isTrue();
        assertThat(paths.has("/api/chats/{id}")).isTrue();
        assertThat(paths.has("/api/chats/import")).isTrue();
    }

    @Test
    @DisplayName("Swagger UI esta acessivel")
    void swaggerUiIsAvailable() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("com auth desligada, /api fica acessivel (modo dev)")
    void apiIsOpenWhenAuthDisabled() throws Exception {
        mockMvc.perform(get("/api/chats")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("as metricas do projeto estao registradas")
    void projectMetricsAreRegistered() throws Exception {
        // imports_total e chatreader.import_chats_total sao registrados no boot.
        mockMvc.perform(get("/actuator/metrics/chatreader.imports_total"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("chatreader.imports_total"));
    }
}
