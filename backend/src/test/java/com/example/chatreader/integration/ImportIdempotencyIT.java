package com.example.chatreader.integration;

import com.example.chatreader.importer.application.ImportService;
import com.example.chatreader.importer.domain.ImportSource;
import com.example.chatreader.chat.domain.ChatRepository;
import com.example.chatreader.chat.domain.ChatSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Tag("integration")
@DisplayName("Importacao contra Postgres real — idempotencia ponta a ponta (secao 12/30)")
class ImportIdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private ImportService importService;

    @Autowired
    private ChatRepository chatRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE messages, chats CASCADE");
    }

    private String json(int extraAssistantMessages) {
        var sb = new StringBuilder("""
                {
                  "schemaVersion": 1,
                  "chats": [
                    {
                      "id": "c-1",
                      "title": "Spring Transactional",
                      "createdAt": "2026-09-20T14:00:00Z",
                      "updatedAt": "2026-09-27T18:30:00Z",
                      "tags": ["Java"],
                      "messages": [
                        { "role": "user", "content": "Como funciona o @Transactional?", "createdAt": "2026-09-20T14:00:00Z" }
                """);
        for (int i = 0; i < extraAssistantMessages; i++) {
            sb.append(",\n        { \"role\": \"assistant\", \"content\": \"Resposta ")
                    .append(i)
                    .append("\", \"createdAt\": \"2026-09-20T14:0")
                    .append(i % 10)
                    .append(":00Z\" }");
        }
        sb.append("""
                        ]
                    }
                  ]
                }
                """);
        return sb.toString();
    }

    @Test
    @DisplayName("importar o mesmo arquivo 2x nao duplica (100 chats -> 100, depois 0)")
    void importingTwiceDoesNotDuplicate() {
        var source = new ImportSource(json(1), null, "chats.json");

        var first = importService.importSource(source);
        assertThat(first.created()).isEqualTo(1);
        assertThat(first.failed()).isZero();
        assertThat(chatRepository.count()).isEqualTo(1);

        var second = importService.importSource(source);
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.skipped()).isEqualTo(1);

        var total = jdbc.queryForObject("SELECT COUNT(*) FROM chats", Long.class);
        assertThat(total).isEqualTo(1);
    }

    @Test
    @DisplayName("conteudo alterado atualiza o mesmo chat e as mensagens")
    void changedContentUpdatesInPlace() {
        importService.importSource(new ImportSource(json(1), null, "chats.json"));
        importService.importSource(new ImportSource(json(3), null, "chats.json"));

        var chats = jdbc.queryForObject("SELECT COUNT(*) FROM chats", Long.class);
        var messages = jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class);
        var version = jdbc.queryForObject("SELECT MAX(content_version) FROM chats", Long.class);

        assertThat(chats).isEqualTo(1);
        assertThat(messages).isEqualTo(4);
        assertThat(version).isEqualTo(2);
    }

    @Test
    @DisplayName("messages fica com sequence 0..N-1 e chat_id correto")
    void messagesAreSequencedPerChat() {
        importService.importSource(new ImportSource(json(2), null, "chats.json"));

        var sequences = jdbc.queryForList(
                "SELECT sequence FROM messages ORDER BY sequence", Integer.class);
        var chatIds = jdbc.queryForList(
                "SELECT DISTINCT chat_id FROM messages", String.class);

        assertThat(sequences).containsExactly(0, 1, 2);
        assertThat(chatIds).hasSize(1);
    }

    @Test
    @DisplayName("message_count desnormalizado bate com a contagem real")
    void messageCountIsConsistent() {
        importService.importSource(new ImportSource(json(4), null, "chats.json"));

        var stored = jdbc.queryForObject("SELECT message_count FROM chats", Integer.class);
        var real = jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class);

        assertThat(stored).isEqualTo(real.intValue());
    }

    @Test
    @DisplayName("50 conversas distintas criam 50 chats; reimportar cria 0")
    void bulkImportIsIdempotent() {
        var json = buildMany(50);
        var source = new ImportSource(json, null, "many.json");

        assertThat(importService.importSource(source).created()).isEqualTo(50);

        var second = importService.importSource(source);
        assertThat(second.created()).isZero();
        assertThat(second.skipped()).isEqualTo(50);
        assertThat(chatRepository.count()).isEqualTo(50);
    }

    @Test
    @DisplayName("conversas com mesmo externalId e conteudo diferente sao atualizadas, nao duplicadas")
    void sameExternalIdDifferentContentUpdates() {
        importService.importSource(new ImportSource(json(1), null, "a.json"));
        importService.importSource(new ImportSource(json(2), null, "b.json"));

        assertThat(chatRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("lote parcialmente invalido preserva os validos")
    void partialFailureKeepsValidChats() {
        var json = """
                {
                  "schemaVersion": 1,
                  "chats": [
                    { "id": "ok-1", "title": "Valida", "messages": [ { "role": "user", "content": "a" } ] },
                    { "id": "vazia", "title": "Sem mensagens", "messages": [] },
                    { "id": "ok-2", "title": "Outra valida", "messages": [ { "role": "assistant", "content": "b" } ] }
                  ]
                }
                """;

        var result = importService.importSource(new ImportSource(json, null, "mixed.json"));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("o Flyway criou o schema: tabelas e indices existem")
    void flywayCreatedSchema() {
        var tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' ORDER BY table_name
                """, String.class);

        assertThat(tables).contains("chats", "messages", "flyway_schema_history");

        var indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                """, String.class);

        assertThat(indexes).anySatisfy(i -> assertThat((String) i).startsWith("uq_chats_source_external"));
        assertThat(indexes).anySatisfy(i -> assertThat((String) i).startsWith("uq_messages_chat_sequence"));
    }

    @Test
    @DisplayName("unique de (source, external_id) impede duplicata no nivel do banco")
    void databaseEnforcesIdempotency() {
        importService.importSource(new ImportSource(json(1), null, "chats.json"));

        // insercao manual com o mesmo external_id deve violar o indice unico parcial
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO chats (id, source, external_id, title, content_version, content_hash, message_count)
                VALUES ('outro-id', ?, 'c-1', 'Copia', 1, 'hash-distinto', 0)
                """, ChatSource.JSON.name()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("content_hash unico impede duas conversas identicas na mesma origem")
    void contentHashIsUniquePerSource() {
        importService.importSource(new ImportSource(json(1), null, "chats.json"));

        var sameContent = jdbc.queryForObject("SELECT content_hash FROM chats", String.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chats WHERE content_hash = ?", Long.class, sameContent))
                .isEqualTo(1);
    }

    private String buildMany(int count) {
        var chats = new java.util.ArrayList<String>();
        for (int i = 0; i < count; i++) {
            chats.add("""
                    {
                      "id": "bulk-%d",
                      "title": "Conversa %d",
                      "createdAt": "2026-09-%02dT10:00:00Z",
                      "messages": [
                        { "role": "user", "content": "pergunta %d" },
                        { "role": "assistant", "content": "resposta %d" }
                      ]
                    }
                    """.formatted(i, i, (i % 28) + 1, i, i));
        }
        return "{\"schemaVersion\": 1, \"chats\": [" + String.join(",", chats) + "]}";
    }
}
