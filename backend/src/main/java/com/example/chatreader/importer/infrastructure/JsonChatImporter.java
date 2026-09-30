package com.example.chatreader.importer.infrastructure;

import com.example.chatreader.importer.domain.ChatImporter;
import com.example.chatreader.importer.domain.DateTimes;
import com.example.chatreader.importer.domain.ImportException;
import com.example.chatreader.importer.domain.ImportFormat;
import com.example.chatreader.importer.domain.ImportSource;
import com.example.chatreader.importer.domain.NormalizedChat;
import com.example.chatreader.importer.domain.RoleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter do JSON generico do projeto.
 *
 * <p>Schema (v1), documentado em {@code docs/importers.md}:
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "chats": [
 *     {
 *       "id": "opcional-estavel",
 *       "title": "...",
 *       "createdAt": "2026-09-20T14:00:00Z",
 *       "updatedAt": "2026-09-27T18:30:00Z",
 *       "tags": ["Java", "Spring"],
 *       "messages": [
 *         { "role": "user", "content": "Markdown...", "createdAt": "..." }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>Este adapter e <b>puro</b>: le bytes e devolve {@link NormalizedChat}. Nao toca no
 * banco, em JPA ou no dominio de {@code chat} alem de {@link com.example.chatreader.chat.domain.Role}.
 */
@Component
public class JsonChatImporter implements ChatImporter {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Set<String> CHAT_KEYS = Set.of("chats", "conversations", "conversations_list");

    private final ObjectMapper objectMapper;

    public JsonChatImporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportFormat format() {
        return ImportFormat.JSON;
    }

    @Override
    public boolean supports(ImportSource source) {
        var text = source.asString();
        if (!text.stripLeading().startsWith("{")) {
            return false;
        }
        try {
            var root = objectMapper.readTree(text);
            if (!(root instanceof ObjectNode obj)) {
                return false;
            }
            // assinatura: tem um array de chats
            return CHAT_KEYS.stream().anyMatch(obj::has);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<NormalizedChat> importChats(ImportSource source) {
        JsonNode root;
        try {
            root = objectMapper.readTree(source.asString());
        } catch (Exception e) {
            throw new ImportException("JSON invalido: " + e.getMessage(), e);
        }
        if (!(root instanceof ObjectNode obj)) {
            throw new ImportException("A raiz do JSON deve ser um objeto com um array de chats");
        }

        var schemaVersion = obj.path("schemaVersion").asInt(SUPPORTED_SCHEMA_VERSION);
        if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            throw new ImportException("schemaVersion " + schemaVersion
                    + " nao suportado (maximo suportado: " + SUPPORTED_SCHEMA_VERSION + ")");
        }

        var array = firstArray(obj);
        if (array == null) {
            throw new ImportException("Nenhum array de chats encontrado. Esperado uma das chaves: "
                    + CHAT_KEYS.stream().sorted().toList());
        }

        var result = new ArrayList<NormalizedChat>(array.size());
        for (var node : array) {
            if (node instanceof ObjectNode chatNode) {
                var normalized = tryParseChat(chatNode);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
        }
        return result;
    }

    private ArrayNode firstArray(ObjectNode obj) {
        for (var key : CHAT_KEYS) {
            if (obj.get(key) instanceof ArrayNode array) {
                return array;
            }
        }
        return null;
    }

    /** Devolve {@code null} para um chat invalido — o item e pulado, nao derruba o lote. */
    private NormalizedChat tryParseChat(ObjectNode chatNode) {
        try {
            var title = text(chatNode, "title");
            if (title == null) {
                title = firstLine(text(chatNode, "content"));
            }

            var createdAt = instant(chatNode, "createdAt", "create_time", "created_at");
            var updatedAt = instant(chatNode, "updatedAt", "update_time", "updated_at");

            var messagesNode = chatNode.get("messages");
            if (messagesNode == null) {
                messagesNode = chatNode.get("mapping") != null ? chatNode.get("mapping") : chatNode.get("conversation");
            }

            var messages = new ArrayList<NormalizedChat.NormalizedMessage>();
            if (messagesNode instanceof ArrayNode arr) {
                for (var m : arr) {
                    if (m instanceof ObjectNode mn) {
                        messages.add(parseMessage(mn));
                    }
                }
            } else if (messagesNode instanceof ObjectNode obj) {
                // formato "mapping" (estilo ChatGPT) — iteramos valores
                for (var m : obj) {
                    if (m instanceof ObjectNode mn) {
                        messages.add(parseMessage(mn));
                    }
                }
            }

            if (messages.isEmpty()) {
                return null;
            }

            return new NormalizedChat(
                    text(chatNode, "id", "externalId", "conversation_id", "conversationId"),
                    title,
                    createdAt,
                    updatedAt,
                    messages,
                    parseTags(chatNode));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private NormalizedChat.NormalizedMessage parseMessage(ObjectNode node) {
        // formato aninhado { message: { author: { role }, content: { parts } } }
        var payload = node.has("message") && node.get("message") instanceof ObjectNode nested
                ? nested
                : node;

        var role = RoleMapper.from(extractRole(payload, node));
        var content = extractContent(payload);
        var createdAt = instant(payload, "createdAt", "create_time", "created_at");
        if (createdAt == null) {
            createdAt = instant(node, "createdAt", "create_time", "created_at");
        }
        return new NormalizedChat.NormalizedMessage(
                text(node, "id", "externalId", "message_id", "messageId"), role, content, createdAt);
    }

    private String extractRole(ObjectNode payload, ObjectNode original) {
        if (payload.hasNonNull("role")) {
            return payload.get("role").asText();
        }
        if (payload.path("author").isObject() && payload.path("author").hasNonNull("role")) {
            return payload.path("author").get("role").asText();
        }
        return text(original, "role", "author", "sender");
    }

    private String extractContent(ObjectNode payload) {
        if (payload.hasNonNull("content") && payload.get("content").isTextual()) {
            return payload.get("content").asText();
        }
        if (payload.path("content").isObject() && payload.path("content").has("parts")) {
            return joinParts(payload.path("content").get("parts"));
        }
        if (payload.path("content").isArray()) {
            return joinParts(payload.path("content"));
        }
        if (payload.hasNonNull("text")) {
            return payload.get("text").asText();
        }
        return "";
    }

    private String joinParts(JsonNode parts) {
        var sb = new StringBuilder();
        for (var part : parts) {
            if (part.isTextual()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(part.asText());
            }
        }
        return sb.toString();
    }

    private Set<String> parseTags(ObjectNode chatNode) {
        var tags = new LinkedHashSet<String>();
        var node = chatNode.get("tags");
        if (node instanceof ArrayNode arr) {
            for (var t : arr) {
                if (t.isTextual() && !t.asText().isBlank()) {
                    tags.add(t.asText().strip());
                }
            }
        }
        return tags;
    }

    private String text(ObjectNode node, String... keys) {
        for (var key : keys) {
            var value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private Instant instant(ObjectNode node, String... keys) {
        for (var key : keys) {
            var value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return DateTimes.fromEpoch(value.asDouble());
            }
            if (value.isTextual()) {
                var parsed = DateTimes.parse(value.asText());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private String firstLine(String value) {
        if (value == null) {
            return null;
        }
        var stripped = value.strip();
        var newline = stripped.indexOf('\n');
        return newline > 0 ? stripped.substring(0, newline) : stripped;
    }
}
