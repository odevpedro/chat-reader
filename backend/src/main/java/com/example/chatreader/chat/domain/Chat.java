package com.example.chatreader.chat.domain;

import com.example.chatreader.shared.TagNames;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Agregado raiz de uma conversa. Objeto de dominio puro (sem JPA, sem Spring).
 *
 * <p>Invariantes garantidos aqui (ver {@code ChatTest}):
 * <ol>
 *   <li>{@code title} nunca e nulo nem branco;
 *   <li>{@code sequence} das mensagens e unico e &gt;= 0;
 *   <li>{@code contentVersion} e estritamente crescente a cada mudanca de conteudo;
 *   <li>{@code contentHash} e deterministico para a mesma entrada;
 *   <li>{@code markDeleted()} e idempotente.
 * </ol>
 */
public class Chat {

    private final String id;
    private final ChatSource source;
    private final String externalId;
    private final Instant createdAt;
    private final Instant importedAt;
    private final List<Message> messages;

    private String title;
    private Instant updatedAt;
    private String contentHash;
    private long contentVersion;
    private boolean favorite;
    private boolean deleted;
    private int messageCount;
    private Set<String> tags;

    private Chat(String id, ChatSource source, String externalId, String title,
                 Instant createdAt, Instant updatedAt, Instant importedAt,
                 List<Message> messages, String contentHash, long contentVersion,
                 boolean favorite, boolean deleted, int messageCount, Collection<String> tags) {
        this.id = id;
        this.source = Objects.requireNonNull(source, "source nao pode ser nulo");
        this.externalId = normalize(externalId);
        this.title = requireTitle(title);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.importedAt = importedAt;
        this.messages = new ArrayList<>(messages);
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash nao pode ser nulo");
        this.contentVersion = contentVersion;
        this.favorite = favorite;
        this.deleted = deleted;
        this.messageCount = Math.max(messageCount, 0);
        this.tags = new LinkedHashSet<>(TagNames.normalize(tags));
        assertUniqueSequences(this.messages);
    }

    /** Cria uma conversa nova a partir de dados normalizados. */
    public static Chat create(ChatSource source, String externalId, String title,
                              Instant createdAt, Instant importedAt, List<Message> messages) {
        return create(source, externalId, title, createdAt, null, importedAt, messages, Set.of());
    }

    /**
     * Cria uma conversa preservando o {@code updatedAt} da origem como fallback.
     * Por dominio (docs/domain.md), {@code updatedAt} e a data da mensagem mais
     * recente na origem; so cai para o timestamp informado pelo exportador e, por
     * fim, para {@code importedAt} quando nao ha nenhuma referencia melhor.
     */
    public static Chat create(ChatSource source, String externalId, String title,
                              Instant createdAt, Instant sourceUpdatedAt, Instant importedAt,
                              List<Message> messages) {
        return create(source, externalId, title, createdAt, sourceUpdatedAt, importedAt, messages, Set.of());
    }

    /** Cria uma conversa nova, associando o conjunto inicial de tags. */
    public static Chat create(ChatSource source, String externalId, String title,
                              Instant createdAt, Instant sourceUpdatedAt, Instant importedAt,
                              List<Message> messages, Collection<String> tags) {
        var safeMessages = normalizeMessages(messages);
        var hash = ContentHash.of(requireTitle(title), safeMessages);
        var effectiveCreatedAt = createdAt == null ? importedAt : createdAt;
        var effectiveUpdatedAt = latestMessageInstant(safeMessages);
        if (effectiveUpdatedAt == null) {
            effectiveUpdatedAt = sourceUpdatedAt;
        }
        if (effectiveUpdatedAt == null) {
            effectiveUpdatedAt = effectiveCreatedAt;
        }
        return new Chat(UUID.randomUUID().toString(), source, externalId, title,
                effectiveCreatedAt, effectiveUpdatedAt, importedAt,
                safeMessages, hash, 1L, false, false, safeMessages.size(), TagNames.normalize(tags));
    }

    /**
     * Reconstroi um agregado ja persistido, sem recalcular hash nem versao.
     * Usado pelo repositorio na hidratacao.
     */
    public static Chat restore(String id, ChatSource source, String externalId, String title,
                               Instant createdAt, Instant updatedAt, Instant importedAt,
                               List<Message> messages, String contentHash, long contentVersion,
                               boolean favorite, boolean deleted, Collection<String> tags) {
        return new Chat(id, source, externalId, title, createdAt, updatedAt, importedAt,
                messages, contentHash, contentVersion, favorite, deleted, messages.size(), tags);
    }

    /**
     * Reconstroi apenas os metadados (listagens), preservando a contagem de mensagens
     * denormalizada na coluna {@code message_count} sem carregar o conteudo.
     */
    public static Chat restoreMetadata(String id, ChatSource source, String externalId, String title,
                                       Instant createdAt, Instant updatedAt, Instant importedAt,
                                       String contentHash, long contentVersion,
                                       boolean favorite, boolean deleted, int messageCount,
                                       Collection<String> tags) {
        return new Chat(id, source, externalId, title, createdAt, updatedAt, importedAt,
                List.of(), contentHash, contentVersion, favorite, deleted, messageCount, tags);
    }

    /**
     * Aplica conteudo reimportado preservando as tags atuais. Se o hash canonico
     * nao mudou, e um no-op (idempotencia de reimportacao: a versao NAO e
     * incrementada).
     *
     * @return {@code true} se algo mudou de fato
     */
    public boolean applyImportedContent(String newTitle, List<Message> newMessages) {
        return applyImportedContent(newTitle, newMessages, this.tags);
    }

    /**
     * Aplica conteudo + tags reimportados. Um unico incremento de versao cobre
     * mudanca de conteudo e/ou de tags.
     *
     * @return {@code true} se algo mudou de fato
     */
    public boolean applyImportedContent(String newTitle, List<Message> newMessages,
                                        Collection<String> newTags) {
        var safeTitle = requireTitle(newTitle);
        var safeMessages = normalizeMessages(newMessages);
        var newHash = ContentHash.of(safeTitle, safeMessages);
        var safeTags = TagNames.normalize(newTags);
        if (newHash.equals(contentHash) && safeTags.equals(this.tags)) {
            return false;
        }
        this.title = safeTitle;
        this.messages.clear();
        this.messages.addAll(safeMessages);
        this.messageCount = safeMessages.size();
        this.contentHash = newHash;
        this.tags = new LinkedHashSet<>(safeTags);
        this.contentVersion++;
        this.updatedAt = maxInstant(latestMessageInstant(safeMessages), this.updatedAt);
        return true;
    }

    /**
     * Substitui o conjunto de tags (LWW — ADR-005). Retorna {@code true} se mudou.
     * Nao altera {@code updatedAt}: tags sao metadados, nao conteudo.
     */
    public boolean setTags(Collection<String> newTags) {
        var safeTags = TagNames.normalize(newTags);
        if (safeTags.equals(this.tags)) {
            return false;
        }
        this.tags = new LinkedHashSet<>(safeTags);
        this.contentVersion++;
        return true;
    }

    /** Marca como removida. Idempotente e nunca decrementa a versao. */
    public void markDeleted() {
        this.deleted = true;
    }

    /**
     * Marca/desmarca favorito (flag bidirecional, LWW — ADR-005).
     * Retorna {@code true} se o valor mudou de fato.
     */
    public boolean setFavorite(boolean newValue) {
        if (this.favorite == newValue) {
            return false;
        }
        this.favorite = newValue;
        this.contentVersion++;
        return true;
    }

    // --- getters ------------------------------------------------------------

    public String id() {
        return id;
    }

    public ChatSource source() {
        return source;
    }

    public String externalId() {
        return externalId;
    }

    public String title() {
        return title;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant importedAt() {
        return importedAt;
    }

    public String contentHash() {
        return contentHash;
    }

    public long contentVersion() {
        return contentVersion;
    }

    public boolean deleted() {
        return deleted;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }

    public int messageCount() {
        return messages.isEmpty() ? messageCount : messages.size();
    }

    /** Tags normalizadas (ordenadas, sem duplicatas case-insensitive). */
    public Set<String> tags() {
        return Collections.unmodifiableSet(tags);
    }

    public boolean hasSameContentAs(String newTitle, List<Message> newMessages) {
        return ContentHash.of(requireTitle(newTitle), normalizeMessages(newMessages)).equals(contentHash);
    }

    // --- helpers ------------------------------------------------------------

    private static List<Message> normalizeMessages(List<Message> messages) {
        var result = new ArrayList<>(messages == null ? List.<Message>of() : messages);
        result.sort(java.util.Comparator.comparingInt(Message::sequence));
        assertUniqueSequences(result);
        return result;
    }

    private static void assertUniqueSequences(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).sequence() != i) {
                throw new IllegalArgumentException(
                        "sequences devem ser 0..N-1 sem buracos; indice " + i + " tem sequence "
                                + messages.get(i).sequence());
            }
        }
    }

    private static String requireTitle(String title) {
        return normalizeTitle(title);
    }

    /**
     * Normalizacao canonica de titulo. Publica para que o {@code ImportService} calcule
     * o mesmo hash de conteudo que {@link #create} produziria, sem depender de ids.
     */
    public static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Conversa sem titulo";
        }
        var trimmed = title.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    private static Instant latestMessageInstant(List<Message> messages) {
        return messages.stream()
                .map(Message::createdAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    @Override
    public String toString() {
        return "Chat[id=" + id + ", source=" + source + ", title=" + title
                + ", messages=" + messages.size() + ", v=" + contentVersion + "]";
    }
}
