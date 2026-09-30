package com.example.chatreader.importer.domain;

import com.example.chatreader.chat.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RoleMapper — traducao de papel sem acoplar o dominio ao formato (secao 2.3)")
class RoleMapperTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "user, USER",
            "Human, USER",
            "PROMPT, USER",
            "assistant, ASSISTANT",
            "GPT, ASSISTANT",
            "ChatGPT, ASSISTANT",
            "claude, ASSISTANT",
            "system, SYSTEM",
            "Developer, SYSTEM",
            "tool, TOOL",
            "function, TOOL",
            "tool_call, TOOL"
    })
    void traduz_papeis_conhecidos(String raw, Role expected) {
        assertThat(RoleMapper.from(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"banana", "assistant2", "user_id", "  "})
    void papeis_desconhecidos_viram_unknown(String raw) {
        assertThat(RoleMapper.from(raw)).isEqualTo(Role.UNKNOWN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void papeis_ausentes_viram_unknown(String raw) {
        assertThat(RoleMapper.from(raw)).isEqualTo(Role.UNKNOWN);
    }

    @Test
    void nunca_retorna_null() {
        assertThat(RoleMapper.from(null)).isNotNull();
    }
}
