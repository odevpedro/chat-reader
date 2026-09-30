package com.example.chatreader.importer.domain;

import com.example.chatreader.chat.domain.Role;

import java.util.Locale;
import java.util.Map;

/**
 * Traducao de "papel na origem" para {@link Role}. O dominio nao conhece os nomes
 * usados por exportadores; cada adapter usa esta tabela.
 *
 * <p>Qualquer valor desconhecido (ou ausente) vira {@link Role#UNKNOWN}: a
 * especificacao exige que a importacao nunca falhe por papel desconhecido.
 */
public final class RoleMapper {

    private static final Map<String, Role> ROLES = Map.ofEntries(
            Map.entry("user", Role.USER),
            Map.entry("human", Role.USER),
            Map.entry("prompt", Role.USER),
            Map.entry("voce", Role.USER),
            Map.entry("você", Role.USER),
            Map.entry("me", Role.USER),
            Map.entry("assistant", Role.ASSISTANT),
            Map.entry("gpt", Role.ASSISTANT),
            Map.entry("model", Role.ASSISTANT),
            Map.entry("bot", Role.ASSISTANT),
            Map.entry("chatgpt", Role.ASSISTANT),
            Map.entry("claude", Role.ASSISTANT),
            Map.entry("gemini", Role.ASSISTANT),
            Map.entry("system", Role.SYSTEM),
            Map.entry("developer", Role.SYSTEM),
            Map.entry("tool", Role.TOOL),
            Map.entry("function", Role.TOOL),
            Map.entry("tool_call", Role.TOOL)
    );

    private RoleMapper() {
    }

    public static Role from(String raw) {
        if (raw == null || raw.isBlank()) {
            return Role.UNKNOWN;
        }
        var key = raw.strip().toLowerCase(Locale.ROOT);
        return ROLES.getOrDefault(key, Role.UNKNOWN);
    }
}
