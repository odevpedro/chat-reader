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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Sincronizacao incremental (secoes 15/16; docs/sync-protocol.md)")
class SyncApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // o change log tambem e zerado: cada teste comeca de um token 0
        jdbc.execute("TRUNCATE TABLE sync_change_log, bookmarks, chat_tags, tags, messages, chats CASCADE");
    }

    private String importChat(String externalId, String title, int messages) throws Exception {
        var body = new StringBuilder("""
                {
                  "schemaVersion": 1,
                  "chats": [
                    {
                      "id": "%s",
                      "title": "%s",
                      "tags": ["Java", "Spring"],
                      "messages": [""".formatted(externalId, title));
        for (int i = 0; i < messages; i++) {
            body.append(i == 0 ? "" : ",")
                    .append("""
                        { "role": %s, "content": "mensagem %d" }"""
                            .formatted(i % 2 == 0 ? "\"user\"" : "\"assistant\"", i));
        }
        body.append("""
                        ]
                    }
                  ]
                }
                """);
        mockMvc.perform(multipart("/api/chats/import")
                        .file(new MockMultipartFile("file", "chats.json", "application/json",
                                body.toString().getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM chats WHERE external_id = ?", String.class, externalId);
    }

    private long currentVersion() {
        // mesma definicao do servidor: maior linha do log ou ultima versao da sequence
        return jdbc.queryForObject("""
                SELECT GREATEST(COALESCE(MAX(version), 0),
                                COALESCE(pg_sequence_last_value('sync_change_log_version_seq'), 0))
                FROM sync_change_log""", Long.class);
    }

    private String firstMessageId(String chatId) {
        return jdbc.queryForObject(
                "SELECT id FROM messages WHERE chat_id = ? ORDER BY sequence LIMIT 1", String.class, chatId);
    }

    @Test
    @DisplayName("cliente sem token e mandado para o snapshot")
    void clientWithoutTokenGetsFullResync() throws Exception {
        importChat("c-1", "Primeira biblioteca", 1);

        mockMvc.perform(get("/api/sync").param("since", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullResyncRequired").value(true))
                .andExpect(jsonPath("$.changes.length()").value(0))
                .andExpect(jsonPath("$.syncToken").value(currentVersion()));

        mockMvc.perform(get("/api/sync/snapshot").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chats.length()").value(1))
                .andExpect(jsonPath("$.chats[0].title").value("Primeira biblioteca"))
                .andExpect(jsonPath("$.chats[0].messages.length()").value(1));
    }

    @Test
    @DisplayName("CHAT_CREATED entrega o chat inteiro: mensagens e tags")
    void createdChatArrivesWithMessagesAndTags() throws Exception {
        var token = currentVersion();
        var chatId = importChat("c-1", "Spring Transactional", 2);

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullResyncRequired").value(false))
                .andExpect(jsonPath("$.syncToken").value(currentVersion()))
                // tag criada junto com o chat nao gera evento proprio: o cliente
                // aprende dela no payload do chat (id + nome)
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].type").value("CHAT_CREATED"))
                .andExpect(jsonPath("$.changes[0].entityId").value(chatId))
                .andExpect(jsonPath("$.changes[0].entityType").value("CHAT"))
                .andExpect(jsonPath("$.changes[0].chat.title").value("Spring Transactional"))
                .andExpect(jsonPath("$.changes[0].chat.messages.length()").value(2))
                .andExpect(jsonPath("$.changes[0].chat.tags[0].id")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyOrNullString())))
                .andExpect(jsonPath("$.changes[0].chat.tags[0].name").value("Java"))
                .andExpect(jsonPath("$.changes[0].chat.tags[1].name").value("Spring"));
    }

    @Test
    @DisplayName("tag criada pela API vira TAG_CREATED no delta")
    void explicitTagAppearsInDelta() throws Exception {
        importChat("c-1", "Com catalogo", 1);
        var token = currentVersion();

        mockMvc.perform(post("/api/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Arquitetura\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.emptyOrNullString())));

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].type").value("TAG_CREATED"))
                .andExpect(jsonPath("$.changes[0].tag.name").value("Arquitetura"));
    }

    @Test
    @DisplayName("sync incremental com o token salvo nao devolve nada")
    void incrementalSyncIsEmptyWhenNothingChanged() throws Exception {
        importChat("c-1", "Sem mudancas", 1);
        var token = currentVersion();

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(0))
                .andExpect(jsonPath("$.syncToken").value(token))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("favoritar e tag geram CHAT_UPDATED com o estado novo")
    void favoriteAndTagsProduceChatUpdated() throws Exception {
        var chatId = importChat("c-1", "Organizacao", 1);
        var token = currentVersion();

        mockMvc.perform(put("/api/chats/{id}/favorite", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favorite\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/chats/{id}/tags", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"Arquitetura\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(jsonPath("$.changes.length()").value(2)) // favorito + tags
                .andExpect(jsonPath("$.changes[?(@.type=='CHAT_UPDATED')].chat.favorite")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.changes[?(@.type=='CHAT_UPDATED')].chat.tags[0].name")
                        .value(org.hamcrest.Matchers.hasItem("Arquitetura")));
    }

    @Test
    @DisplayName("remover chat gera CHAT_DELETED e some da listagem")
    void deleteEmitsChatDeleted() throws Exception {
        var chatId = importChat("c-1", "Sera removido", 2);
        var token = currentVersion();

        mockMvc.perform(delete("/api/chats/{id}", chatId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].type").value("CHAT_DELETED"))
                .andExpect(jsonPath("$.changes[0].entityId").value(chatId))
                .andExpect(jsonPath("$.changes[0].chat").doesNotExist());
    }

    @Test
    @DisplayName("bookmarks geram BOOKMARK_CREATED e BOOKMARK_DELETED")
    void bookmarkLifecycleAppearsInDelta() throws Exception {
        var chatId = importChat("c-1", "Com bookmark", 2);
        var messageId = firstMessageId(chatId);
        var token = currentVersion();

        var created = mockMvc.perform(post("/api/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chatId\":\"" + chatId + "\",\"messageId\":\"" + messageId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var bookmarkId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created).path("id").asText();

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(jsonPath("$.changes[?(@.type=='BOOKMARK_CREATED')].bookmark.messageId")
                        .value(org.hamcrest.Matchers.hasItem(messageId)))
                .andExpect(jsonPath("$.changes[?(@.type=='BOOKMARK_CREATED')].entityId")
                        .value(org.hamcrest.Matchers.hasItem(bookmarkId)));

        var afterCreate = currentVersion();
        mockMvc.perform(delete("/api/bookmarks/{id}", bookmarkId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(afterCreate)))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].type").value("BOOKMARK_DELETED"))
                .andExpect(jsonPath("$.changes[0].entityId").value(bookmarkId));
    }

    @Test
    @DisplayName("ACK cria bookmark com id do cliente e o delta seguinte confirma")
    void ackCreatesBookmarkAndDeltaConfirms() throws Exception {
        var chatId = importChat("c-1", "Offline", 2);
        var messageId = firstMessageId(chatId);
        var token = currentVersion();
        var clientId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/sync/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ackVersion": %d,
                                  "clientId": "kobo-clara",
                                  "localChanges": [
                                    { "type": "BOOKMARK_CREATED",
                                      "payload": { "id": "%s", "chatId": "%s", "messageId": "%s",
                                                   "note": "criado offline" } }
                                  ]
                                }
                                """.formatted(token, clientId, chatId, messageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.syncToken").value(currentVersion()));

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(jsonPath("$.changes[0].type").value("BOOKMARK_CREATED"))
                .andExpect(jsonPath("$.changes[0].entityId").value(clientId));

        // reenvio do mesmo item converge: o id do cliente evita duplicata
        mockMvc.perform(post("/api/sync/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ackVersion": %d, "localChanges": [
                                    { "type": "BOOKMARK_CREATED", "payload": { "id": "%s", "chatId": "%s",
                                        "messageId": "%s" } } ] }
                                """.formatted(token, clientId, chatId, messageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookmarks WHERE id = ?", Long.class, clientId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("ACK de favorito converge e de remocoes invalidas e recusado por indice")
    void ackFavoritesAndRejectsInvalidItems() throws Exception {
        var chatId = importChat("c-1", "Conflitos", 1);
        var token = currentVersion();

        mockMvc.perform(post("/api/sync/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ackVersion": %d, "localChanges": [
                                  { "type": "CHAT_FAVORITE", "payload": { "chatId": "%s", "favorite": true } },
                                  { "type": "CHAT_CONTENT", "payload": { "chatId": "%s",
                                      "motivo": "Kindle reescreveu mensagens" } },
                                  { "type": "BOOKMARK_CREATED", "payload": { "id": "%s", "chatId": "%s",
                                      "messageId": "mensagem-inexistente" } } ] }
                                """.formatted(token, chatId, chatId, UUID.randomUUID(), chatId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.rejections[0].index").value(1))
                .andExpect(jsonPath("$.rejections[0].reason").value("CONFLICT"))
                .andExpect(jsonPath("$.rejections[1].index").value(2))
                .andExpect(jsonPath("$.rejections[1].reason").value("NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT favorite FROM chats WHERE id = ?", Boolean.class, chatId))
                .isTrue();
    }

    @Test
    @DisplayName("ACK de remocao de bookmark ausente e aceito (idempotente)")
    void ackDeleteOfMissingBookmarkIsAccepted() throws Exception {
        var token = currentVersion();

        mockMvc.perform(post("/api/sync/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ackVersion": %d, "localChanges": [
                                  { "type": "BOOKMARK_DELETED", "payload": { "id": "%s" } } ] }
                                """.formatted(token, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejections.length()").value(0));
    }

    @Test
    @DisplayName("tipo de mudanca local desconhecido devolve 400")
    void ackWithUnknownTypeReturns400() throws Exception {
        mockMvc.perform(post("/api/sync/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ackVersion": 0, "localChanges": [
                                  { "type": "CHAT_RENAMED", "payload": { "chatId": "x" } } ] }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("limit pagina o delta e hasMore guia a proxima pagina")
    void limitPagesTheDelta() throws Exception {
        var start = currentVersion();
        var chatId = importChat("c-1", "Paginado", 1);
        mockMvc.perform(put("/api/chats/{id}/favorite", chatId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/chats/{id}/tags", chatId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"Delta\"]}"))
                .andExpect(status().isOk());
        assertThat(currentVersion()).isEqualTo(start + 3); // criacao + favorito + tags

        var page1 = mockMvc.perform(get("/api/sync")
                        .param("since", String.valueOf(start)).param("limit", "2"))
                .andExpect(jsonPath("$.changes.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var token1 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(page1).path("syncToken").asLong();
        assertThat(token1).isEqualTo(start + 2);

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token1)))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.syncToken").value(start + 3));
    }

    @Test
    @DisplayName("token anterior a janela retida pede full resync; o snapshot devolve o estado")
    void staleTokenRequiresFullResyncAndSnapshotRecovers() throws Exception {
        var start = currentVersion();
        var chatId = importChat("c-1", "Recuperado", 2);
        mockMvc.perform(put("/api/chats/{id}/favorite", chatId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/chats/{id}/tags", chatId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[\"Delta\"]}"))
                .andExpect(status().isOk());
        // a retencao (30 dias) deixa o log so com o que e recente: as duas
        // primeiras mudancas saem, e o cliente com o token antigo nunca as vera
        jdbc.execute("DELETE FROM sync_change_log WHERE version < "
                + "(SELECT MAX(version) FROM sync_change_log)");

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(start)))
                .andExpect(jsonPath("$.fullResyncRequired").value(true))
                .andExpect(jsonPath("$.changes.length()").value(0));

        var snapshot = mockMvc.perform(get("/api/sync/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.tags.length()").value(3))
                .andExpect(jsonPath("$.chats[0].id").value(chatId))
                .andExpect(jsonPath("$.chats[0].messages.length()").value(2))
                .andExpect(jsonPath("$.bookmarksOmitted").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // depois do snapshot o cliente esta em dia: o token novo nao que ta na
        // janela retida, entao o delta responde vazio em vez de pedir tudo de novo
        var snapshotToken = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(snapshot).path("syncToken").asLong();
        mockMvc.perform(get("/api/sync").param("since", String.valueOf(snapshotToken)))
                .andExpect(jsonPath("$.fullResyncRequired").value(false))
                .andExpect(jsonPath("$.changes.length()").value(0));
    }

    @Test
    @DisplayName("snapshot sem nenhuma mudanca devolve token utilizavel (nunca 0)")
    void snapshotOnEmptyLogReservesAToken() throws Exception {
        var snapshot = mockMvc.perform(get("/api/sync/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot).path("syncToken").asLong();
        assertThat(token).isPositive();

        // sem isso o cliente ficaria preso pedindo snapshot a cada ciclo
        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(jsonPath("$.fullResyncRequired").value(false))
                .andExpect(jsonPath("$.changes.length()").value(0));
    }

    @Test
    @DisplayName("snapshot ignora chat removido e pagina por chat")
    void snapshotSkipsDeletedChats() throws Exception {
        var keep = importChat("c-1", "Fica", 1);
        var drop = importChat("c-2", "Some", 1);
        mockMvc.perform(delete("/api/chats/{id}", drop)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/sync/snapshot").param("size", "1"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.chats[0].id").value(keep));
    }

    @Test
    @DisplayName("conversa grande vem sem mensagens e com messagesOmitted")
    void largeChatOmitsMessages() throws Exception {
        // acima do padrao chatreader.sync.max-messages-per-change (200)
        var token = currentVersion();
        var chatId = importChat("c-1", "Grande", 201);

        mockMvc.perform(get("/api/sync").param("since", String.valueOf(token)))
                .andExpect(jsonPath("$.changes[?(@.type=='CHAT_CREATED')].chat.messageCount")
                        .value(org.hamcrest.Matchers.hasItem(201)))
                .andExpect(jsonPath("$.changes[?(@.type=='CHAT_CREATED')].chat.messagesOmitted")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.changes[?(@.type=='CHAT_CREATED')].chat.messages.length()")
                        .value(org.hamcrest.Matchers.hasItem(0)));

        // o leitor busca as mensagens sob demanda
        mockMvc.perform(get("/api/chats/{id}/messages", chatId).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(201))
                .andExpect(jsonPath("$.last").value(false));
    }
}
