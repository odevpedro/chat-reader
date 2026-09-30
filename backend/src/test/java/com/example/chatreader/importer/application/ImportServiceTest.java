package com.example.chatreader.importer.application;

import com.example.chatreader.chat.domain.Chat;
import com.example.chatreader.chat.domain.ChatRepository;
import com.example.chatreader.chat.domain.ChatSource;
import com.example.chatreader.chat.domain.Message;
import com.example.chatreader.chat.domain.Role;
import com.example.chatreader.importer.domain.ImportFormat;
import com.example.chatreader.importer.domain.ImportSource;
import com.example.chatreader.importer.domain.NormalizedChat;
import com.example.chatreader.sync.application.ChangeLogWriter;
import com.example.chatreader.sync.domain.ChangeLog;
import com.example.chatreader.sync.domain.ChangeType;
import com.example.chatreader.sync.domain.SyncChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ImportService — idempotencia de importacao (secao 12)")
class ImportServiceTest {

    private final InMemoryChatRepository repository = new InMemoryChatRepository();
    private final InMemoryChangeLog changeLog = new InMemoryChangeLog();
    private final FakeImporter importer = new FakeImporter();
    private final ChatImporterRegistry registry = new ChatImporterRegistry(List.of(importer));
    private final ImportService service = new ImportService(registry, repository,
            new ImportProperties(1024, 100, 50), new ChangeLogWriter(changeLog));

    @Test
    @DisplayName("primeira importacao cria; segunda nao duplica")
    void secondImportDoesNotDuplicate() {
        var source = source(chat("c-1", "Titulo", msg("user", "pergunta"), msg("assistant", "resposta")));

        var first = service.importSource(source);
        assertThat(first.created()).isEqualTo(1);
        assertThat(first.skipped()).isEqualTo(0);
        assertThat(repository.count()).isEqualTo(1);

        var second = service.importSource(source);
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("conteudo alterado gera atualizacao, nao duplicata")
    void changedContentUpdates() {
        service.importSource(source(chat("c-1", "Titulo", msg("user", "pergunta"))));

        var result = service.importSource(source(chat("c-1", "Titulo",
                msg("user", "pergunta"), msg("assistant", "resposta"))));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.created()).isZero();
        assertThat(repository.count()).isEqualTo(1);

        var stored = repository.all.getFirst();
        assertThat(stored.messageCount()).isEqualTo(2);
        assertThat(stored.contentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("reimportar apos alteracao volta a ser no-op")
    void reimportAfterUpdateIsNoop() {
        var source = source(chat("c-1", "Titulo", msg("user", "a")));
        service.importSource(source);
        service.importSource(source(chat("c-1", "Titulo", msg("user", "a"), msg("assistant", "b"))));

        var third = service.importSource(source(chat("c-1", "Titulo",
                msg("user", "a"), msg("assistant", "b"))));

        assertThat(third.skipped()).isEqualTo(1);
        assertThat(third.updated()).isZero();
        assertThat(repository.all.getFirst().contentVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("sem externalId, a identidade cai para o contentHash")
    void withoutExternalIdIdentityIsContentHash() {
        var source = source(chat(null, "Sem id", msg("user", "a"), msg("assistant", "b")));

        service.importSource(source);
        var second = service.importSource(source);

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("sem externalId, conteudo diferente cria um segundo chat")
    void withoutExternalIdDifferentContentCreatesNewChat() {
        service.importSource(source(chat(null, "Sem id", msg("user", "a"))));
        var result = service.importSource(source(chat(null, "Sem id", msg("user", "b"))));

        assertThat(result.created()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("mensagens sao ordenadas por sequence 0..N-1 na gravacao")
    void assignsSequentialOrder() {
        var normalized = chat("c-1", "Ordem",
                msg("user", "primeira"),
                msg("assistant", "segunda"),
                msg("user", "terceira"));

        service.importSource(source(normalized));

        var stored = repository.all.getFirst();
        assertThat(stored.messages()).extracting(Message::sequence).containsExactly(0, 1, 2);
        assertThat(stored.messages()).extracting(Message::content)
                .containsExactly("primeira", "segunda", "terceira");
    }

    @Test
    @DisplayName("lote com um chat invalido preserva os validos")
    void oneInvalidChatDoesNotBreakTheBatch() {
        var source = source(
                chat("c-1", "Bom", msg("user", "ok")),
                chat("c-2", "Sem mensagens"),
                chat("c-3", "Também bom", msg("assistant", "ok")));

        var result = service.importSource(source);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("conversa acima do limite de mensagens e rejeitada")
    void rejectsChatAboveMessageLimit() {
        var many = new java.util.ArrayList<NormalizedChat.NormalizedMessage>();
        for (int i = 0; i < 60; i++) {
            many.add(msg("user", "mensagem " + i));
        }
        var result = service.importSource(source(new NormalizedChat("c-1", "Grande", null, null, many, java.util.Set.of())));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures().getFirst()).contains("acima do limite");
    }

    @Test
    @DisplayName("cada gravacao de chat gera um evento de sync; o no-op nao gera")
    void emitsChangeLogEntriesOnlyWhenSomethingChanges() {
        var unchanged = source(chat("c-1", "Titulo", msg("user", "pergunta")));
        service.importSource(unchanged);
        assertThat(changeLog.entries).extracting(SyncChange::type)
                .containsExactly(ChangeType.CHAT_CREATED);

        service.importSource(unchanged);
        assertThat(changeLog.entries).hasSize(1);

        service.importSource(source(chat("c-1", "Titulo", msg("user", "pergunta"), msg("assistant", "b"))));
        assertThat(changeLog.entries).extracting(SyncChange::type)
                .containsExactly(ChangeType.CHAT_CREATED, ChangeType.CHAT_UPDATED);
        assertThat(changeLog.entries.getLast().contentVersion()).isEqualTo(2L);
    }

    // --- fixtures ----------------------------------------------------------

    private ImportSource source(NormalizedChat... chats) {
        importer.payload = List.of(chats);
        return new ImportSource("[]", ImportFormat.JSON, "test.json");
    }

    private static NormalizedChat chat(String externalId, String title, NormalizedChat.NormalizedMessage... messages) {
        return new NormalizedChat(externalId, title, null, null, List.of(messages), java.util.Set.of());
    }

    private static NormalizedChat.NormalizedMessage msg(String role, String content) {
        return new NormalizedChat.NormalizedMessage(null,
                role.equals("user") ? Role.USER : Role.ASSISTANT, content, null);
    }

    private static class FakeImporter implements com.example.chatreader.importer.domain.ChatImporter {
        private List<NormalizedChat> payload = List.of();

        @Override
        public ImportFormat format() {
            return ImportFormat.JSON;
        }

        @Override
        public boolean supports(ImportSource source) {
            return true;
        }

        @Override
        public List<NormalizedChat> importChats(ImportSource source) {
            return payload;
        }
    }

    private static class InMemoryChatRepository implements ChatRepository {
        private final List<Chat> all = new ArrayList<>();

        @Override
        public Optional<Chat> findById(String id) {
            return all.stream().filter(c -> c.id().equals(id)).findFirst();
        }

        @Override
        public boolean existsById(String id) {
            return all.stream().anyMatch(c -> c.id().equals(id));
        }

        @Override
        public Optional<Chat> findBySourceAndExternalId(ChatSource source, String externalId) {
            return all.stream()
                    .filter(c -> c.source() == source && externalId.equals(c.externalId()))
                    .findFirst();
        }

        @Override
        public Optional<Chat> findBySourceAndContentHash(ChatSource source, String contentHash) {
            return all.stream()
                    .filter(c -> c.source() == source && c.contentHash().equals(contentHash))
                    .findFirst();
        }

        @Override
        public void save(Chat chat) {
            all.removeIf(c -> c.id().equals(chat.id()));
            all.add(chat);
        }

        @Override
        public void saveAll(List<Chat> chats) {
            chats.forEach(this::save);
        }

        @Override
        public List<Chat> findAll(int page, int size) {
            return all.stream().skip((long) page * size).limit(size).toList();
        }

        @Override
        public List<Chat> findFavorites(int page, int size) {
            return all.stream().filter(Chat::isFavorite).skip((long) page * size).limit(size).toList();
        }

        @Override
        public long countFavorites() {
            return all.stream().filter(Chat::isFavorite).count();
        }

        @Override
        public List<Chat> findByTag(String tagName, int page, int size) {
            return all.stream()
                    .filter(c -> c.tags().stream().anyMatch(t -> t.equalsIgnoreCase(tagName)))
                    .skip((long) page * size).limit(size).toList();
        }

        @Override
        public long countByTag(String tagName) {
            return all.stream()
                    .filter(c -> c.tags().stream().anyMatch(t -> t.equalsIgnoreCase(tagName)))
                    .count();
        }

        @Override
        public long count() {
            return all.size();
        }

        @Override
        public Optional<Chat> findMetadataById(String id) {
            return findById(id);
        }

        @Override
        public List<Message> findMessages(String chatId, int page, int size) {
            return findById(chatId).map(Chat::messages)
                    .orElse(List.of())
                    .stream().skip((long) page * size).limit(size).toList();
        }

        @Override
        public void markDeleted(String id) {
            all.removeIf(c -> c.id().equals(id));
        }
    }

    /** Change log em memoria: o teste so precisa que a gravacao nao exploda. */
    private static class InMemoryChangeLog implements ChangeLog {

        private final List<SyncChange> entries = new ArrayList<>();

        @Override
        public SyncChange append(ChangeType type, String entityId, String chatId, Long contentVersion) {
            var change = new SyncChange(entries.size() + 1L, type, entityId, chatId, contentVersion, Instant.now());
            entries.add(change);
            return change;
        }

        @Override
        public List<SyncChange> findSince(long since, int limit) {
            return entries.stream().filter(c -> c.version() > since).limit(limit).toList();
        }

        @Override
        public long currentVersion() {
            return entries.size();
        }

        @Override
        public long reserveVersion() {
            return currentVersion();
        }

        @Override
        public long oldestRetainedVersion() {
            return entries.isEmpty() ? 0 : entries.get(0).version();
        }

        @Override
        public int deleteOlderThan(Instant cutoff) {
            var before = entries.size();
            entries.removeIf(c -> c.createdAt().isBefore(cutoff));
            return before - entries.size();
        }

        @Override
        public int keepLatest(int maxRows) {
            var before = entries.size();
            while (entries.size() > maxRows) {
                entries.removeFirst();
            }
            return before - entries.size();
        }
    }
}
