package com.example.chatreader.tag.infrastructure;

import com.example.chatreader.shared.TagNames;
import com.example.chatreader.tag.domain.TagRef;
import com.example.chatreader.tag.domain.TagRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter JDBC do modulo tag. As operacoes aqui sao pequenas e muito
 * ida-e-volta; JDBC direto evita mapear entidades JPA para a associacao N:N e
 * mantem o controle sobre o upsert case-insensitive.
 */
@Repository
public class TagRepositoryAdapter implements TagRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TagRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> findAllNames() {
        return jdbc.queryForList("SELECT name FROM tags ORDER BY lower(name), name", Map.of(), String.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TagRef> findAll() {
        return jdbc.query("SELECT id, name FROM tags ORDER BY lower(name), name", Map.of(),
                (rs, row) -> new TagRef(rs.getString("id"), rs.getString("name")));
    }

    @Override
    public long count() {
        var total = jdbc.queryForObject("SELECT COUNT(*) FROM tags", Map.of(), Long.class);
        return total == null ? 0 : total;
    }

    @Override
    @Transactional
    public TagRef create(String name) {
        var display = TagNames.require(name);
        var key = TagNames.key(display);
        var existing = findByName(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbc.update("INSERT INTO tags (id, name, normalized_name) VALUES (:id, :name, :key)",
                    Map.of("id", UUID.randomUUID().toString(), "name", display, "key", key));
        } catch (DuplicateKeyException race) {
            // outra transacao criou a mesma tag; o resultado e o mesmo
        }
        return findByName(key).orElseThrow(
                () -> new IllegalStateException("tag nao encontrada apos INSERT: " + key));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TagRef> findByName(String name) {
        return jdbc.query("SELECT id, name FROM tags WHERE normalized_name = :key",
                Map.of("key", TagNames.key(name)), (rs, row) -> new TagRef(rs.getString("id"), rs.getString("name")))
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TagRef> findByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        var keys = names.stream().map(TagNames::key).distinct().toList();
        return jdbc.query("SELECT id, name FROM tags WHERE normalized_name IN (:keys)",
                Map.of("keys", keys),
                (rs, row) -> new TagRef(rs.getString("id"), rs.getString("name")));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TagRef> findById(String id) {
        return jdbc.query("SELECT id, name FROM tags WHERE id = :id",
                Map.of("id", id), (rs, row) -> new TagRef(rs.getString("id"), rs.getString("name")))
                .stream().findFirst();
    }

    @Override
    public Set<String> tagsOf(String chatId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT t.name FROM chat_tags ct
                JOIN tags t ON t.id = ct.tag_id
                WHERE ct.chat_id = :chatId
                ORDER BY lower(t.name), t.name
                """, Map.of("chatId", chatId), String.class));
    }

    @Override
    public Map<String, Set<String>> tagsOf(Collection<String> chatIds) {
        if (chatIds == null || chatIds.isEmpty()) {
            return Map.of();
        }
        var order = new LinkedHashMap<String, Set<String>>();
        jdbc.query("""
                SELECT ct.chat_id, t.name FROM chat_tags ct
                JOIN tags t ON t.id = ct.tag_id
                WHERE ct.chat_id IN (:ids)
                ORDER BY ct.chat_id, lower(t.name), t.name
                """, Map.of("ids", chatIds), rs -> {
            var chatId = rs.getString("chat_id");
            order.computeIfAbsent(chatId, k -> new LinkedHashSet<>()).add(rs.getString("name"));
        });
        return order;
    }

    @Override
    @Transactional
    public void replaceChatTags(String chatId, Collection<String> names) {
        var normalized = TagNames.normalize(names);
        if (normalized.equals(tagsOf(chatId))) {
            return;
        }
        jdbc.update("DELETE FROM chat_tags WHERE chat_id = :chatId", Map.of("chatId", chatId));
        for (var name : normalized) {
            var tagId = ensureTagId(name);
            jdbc.update("""
                    INSERT INTO chat_tags (chat_id, tag_id) VALUES (:chatId, :tagId)
                    ON CONFLICT DO NOTHING
                    """, Map.of("chatId", chatId, "tagId", tagId));
        }
    }

    @Override
    public List<String> chatIdsWithTag(String name) {
        return jdbc.queryForList("""
                SELECT ct.chat_id FROM chat_tags ct
                JOIN tags t ON t.id = ct.tag_id
                JOIN chats c ON c.id = ct.chat_id
                WHERE c.deleted = false AND t.normalized_name = :key
                ORDER BY c.updated_at DESC NULLS LAST, c.id DESC
                """, Map.of("key", TagNames.key(name)), String.class);
    }

    private String ensureTagId(String name) {
        var key = TagNames.key(name);
        var ids = jdbc.queryForList(
                "SELECT id FROM tags WHERE normalized_name = :key", Map.of("key", key), String.class);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        ensureTag(name);
        return jdbc.queryForObject(
                "SELECT id FROM tags WHERE normalized_name = :key", Map.of("key", key), String.class);
    }

    private void ensureTag(String name) {
        try {
            jdbc.update("INSERT INTO tags (id, name, normalized_name) VALUES (:id, :name, :key)",
                    Map.of("id", UUID.randomUUID().toString(), "name", name, "key", TagNames.key(name)));
        } catch (DuplicateKeyException race) {
            // outra transacao criou a mesma tag; ignorar
        }
    }
}
