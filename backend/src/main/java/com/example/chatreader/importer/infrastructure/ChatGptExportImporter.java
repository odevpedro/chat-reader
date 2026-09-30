package com.example.chatreader.importer.infrastructure;

import com.example.chatreader.chat.domain.Role;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter do {@code conversations.json} que o ChatGPT entrega no pedido de export de
 * dados. E o unico ponto do sistema que conhece esse formato (ADR-004): o dominio ve
 * apenas {@link NormalizedChat}.
 *
 * <p>O arquivo e um <b>array na raiz</b>, com cada elemento no formato:
 * <pre>
 * [
 *   {
 *     "title": "Spring Transactional",
 *     "create_time": 1758400000.0,
 *     "update_time": 1758403600.0,
 *     "conversation_id": "6f1c...",
 *     "current_node": "a1b2...",
 *     "mapping": {
 *       "root":  { "id": "root", "message": null, "children": ["a1b2..."] },
 *       "a1b2":  { "id": "a1b2", "message": { "author": {"role": "user"},
 *                   "create_time": 1758400000.0,
 *                   "content": {"content_type": "text", "parts": ["..."]}}},
 *       "c3d4":  { "id": "c3d4", "message": { ... "author": {"role": "assistant"} ... }}
 *     }
 *   }
 * ]
 * </pre>
 *
 * <p>Decisoes que o formato nao resolve sozinho, e por que (docs/importers.md):
 * <ul>
 *   <li><b>{@code mapping} e uma arvore, nao uma lista.</b> Percorremos os nos que tem
 *       {@code message} e ordenamos por {@code create_time} — ordem cronologica e o
 *       que o leitor precisa. Nos de branching (a conversa reescrita) entram todas: o
 *       backend nao tem conceito de "ramo ativo" e o usuario nao perde texto.
 *   <li><b>Mensagens de sistema sao descartadas.</b> Todo export traz um no de sistema
 *       com o preambulo do modelo; ele nao e conversa e polui a leitura.
 *   <li><b>Nos visualmente ocultos sao descartados</b>
 *       ({@code metadata.is_visually_hidden_from_conversation}), mesma razao.
 *   <li><b>Conteudo nao textual</b> ({@code content_type} diferente de
 *       {@code text}/{@code multimodal_text}) vira o texto das partes, quando houver.
 *       Sem nenhum texto a mensagem e' descartada, como uma mensagem vazia — e' o mesmo
 *       criterio do adapter JSON, e ler uma mensagem sem texto nao acrescenta nada.
 * </ul>
 *
 * <p>Este adapter e <b>puro</b>: le bytes e devolve {@link NormalizedChat}.
 */
@Component
public class ChatGptExportImporter implements ChatImporter {

    private final ObjectMapper objectMapper;

    public ChatGptExportImporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ImportFormat format() {
        return ImportFormat.CHATGPT_EXPORT;
    }

    @Override
    public boolean supports(ImportSource source) {
        var text = source.asString().stripLeading();
        if (!text.startsWith("[")) {
            return false; // array na raiz e' a assinatura deste formato
        }
        try {
            var root = objectMapper.readTree(text);
            return root instanceof ArrayNode array && isChatGptNode(array.get(0));
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
            throw new ImportException("Export do ChatGPT invalido: " + e.getMessage(), e);
        }
        if (!(root instanceof ArrayNode array)) {
            throw new ImportException(
                    "O export do ChatGPT e' um array na raiz com as conversas. "
                            + "Se a raiz for um objeto, use o JSON generico (schema v1).");
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

    private static boolean isChatGptNode(JsonNode node) {
        return node instanceof ObjectNode obj
                && obj.has("mapping")
                && (obj.has("conversation_id") || obj.has("create_time") || obj.has("title"));
    }

    /** Devolve {@code null} para uma conversa invalida — o item e' pulado, nao derruba o lote. */
    private NormalizedChat tryParseChat(ObjectNode chatNode) {
        try {
            var messages = parseMapping(chatNode.get("mapping"));
            if (messages.isEmpty()) {
                return null;
            }
            var title = text(chatNode, "title");
            return new NormalizedChat(
                    text(chatNode, "conversation_id", "id", "conversationId"),
                    title != null ? title : "Conversa sem titulo",
                    instant(chatNode, "create_time", "created_at"),
                    instant(chatNode, "update_time", "updated_at"),
                    messages,
                    parseTags(chatNode));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<NormalizedChat.NormalizedMessage> parseMapping(JsonNode mapping) {
        if (!(mapping instanceof ObjectNode nodes)) {
            return List.of();
        }
        var collected = new ArrayList<Node>();
        for (var entry : nodes) {
            if (entry instanceof ObjectNode node) {
                var message = node.get("message");
                if (message instanceof ObjectNode payload) {
                    // message.id e' o id canonico da mensagem; o id do no serve so
                    // de reserva (no export real os dois coincidem, exceto na raiz).
                    var externalId = text(payload, "id", "message_id");
                    if (externalId == null) {
                        externalId = text(node, "id");
                    }
                    collected.add(new Node(instant(payload, "create_time", "created_at"),
                            externalId, payload));
                }
            }
        }
        // create_time nulo (mensagens sem data) vao para o fim, mantendo a ordem do mapa
        collected.sort(Comparator.comparing(n -> n.createdAt() == null ? Instant.MAX : n.createdAt()));

        var result = new ArrayList<NormalizedChat.NormalizedMessage>(collected.size());
        for (var node : collected) {
            var role = RoleMapper.from(extractRole(node.payload()));
            var content = extractContent(node.payload());
            if (isHidden(node.payload()) || role == Role.SYSTEM || content.isBlank()) {
                continue;
            }
            result.add(new NormalizedChat.NormalizedMessage(node.externalId(), role, content,
                    node.createdAt()));
        }
        return result;
    }

    private record Node(Instant createdAt, String externalId, ObjectNode payload) {
    }

    private static boolean isHidden(ObjectNode payload) {
        var metadata = payload.get("metadata");
        return metadata instanceof ObjectNode obj
                && obj.path("is_visually_hidden_from_conversation").asBoolean(false);
    }

    private static String extractRole(ObjectNode payload) {
        var author = payload.get("author");
        if (author instanceof ObjectNode obj && obj.hasNonNull("role")) {
            return obj.get("role").asText();
        }
        return payload.hasNonNull("role") ? payload.get("role").asText() : null;
    }

    private static String extractContent(ObjectNode payload) {
        var content = payload.get("content");
        if (content instanceof ObjectNode obj) {
            return joinParts(obj.get("parts"));
        }
        if (content instanceof ArrayNode array) {
            return joinParts(array);
        }
        if (payload.hasNonNull("content") && payload.get("content").isTextual()) {
            return payload.get("content").asText();
        }
        return "";
    }

    private static String joinParts(JsonNode parts) {
        if (parts == null || !parts.isArray()) {
            return "";
        }
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

    private static Set<String> parseTags(ObjectNode chatNode) {
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

    private static String text(ObjectNode node, String... keys) {
        for (var key : keys) {
            var value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static Instant instant(ObjectNode node, String... keys) {
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
}
