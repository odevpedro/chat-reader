package com.example.chatreader.chat.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Chat — invariantes de dominio (secao 2.6 de docs/domain.md)")
class ChatTest {

    private static final Instant T0 = Instant.parse("2026-09-20T14:00:00Z");

    private static List<Message> messages(String... contents) {
        var list = new ArrayList<Message>();
        for (int i = 0; i < contents.length; i++) {
            list.add(Message.of("m" + i, Role.USER, contents[i], i, T0.plusSeconds(i)));
        }
        return list;
    }

    private static Chat chat(String title, String... contents) {
        return Chat.create(ChatSource.JSON, "ext-1", title, T0, T0, messages(contents));
    }

    @Nested
    @DisplayName("Invariante 1: title nunca e nulo nem branco")
    class TitleInvariant {

        @Test
        void substitui_titulo_vazio_por_padrao() {
            assertThat(chat(null, "a").title()).isEqualTo("Conversa sem titulo");
            assertThat(chat("   ", "a").title()).isEqualTo("Conversa sem titulo");
            assertThat(chat("", "a").title()).isEqualTo("Conversa sem titulo");
        }

        @Test
        void aplica_fallback_tambem_em_conteudo_reimportado() {
            var c = chat("Titulo valido", "a");
            assertThat(c.applyImportedContent("  ", messages("a"))).isTrue();
            assertThat(c.title()).isEqualTo("Conversa sem titulo");
        }

        @Test
        void trunca_titulo_acima_de_500_caracteres() {
            var longo = "x".repeat(600);
            assertThat(chat(longo, "a").title()).hasSize(500);
        }
    }

    @Nested
    @DisplayName("Invariante 2: sequence e unico e >= 0")
    class SequenceInvariant {

        @Test
        void ordena_mensagens_por_sequence() {
            var foraDeOrdem = List.of(
                    Message.of("a", Role.ASSISTANT, "resposta", 1, T0.plusSeconds(1)),
                    Message.of("b", Role.USER, "pergunta", 0, T0));
            var c = Chat.create(ChatSource.JSON, "x", "t", T0, T0, foraDeOrdem);

            assertThat(c.messages()).extracting(Message::sequence).containsExactly(0, 1);
            assertThat(c.messages()).extracting(Message::role)
                    .containsExactly(Role.USER, Role.ASSISTANT);
        }

        @Test
        void recusa_sequence_duplicada() {
            var duplicadas = List.of(
                    Message.of("a", Role.USER, "1", 0, T0),
                    Message.of("b", Role.USER, "2", 0, T0));
            assertThatThrownBy(() -> Chat.create(ChatSource.JSON, "x", "t", T0, T0, duplicadas))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sequence");
        }

        @Test
        void recusa_sequence_negativa() {
            assertThatThrownBy(() -> Message.of("a", Role.USER, "x", -1, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Invariante 3: contentVersion e estritamente crescente")
    class VersionInvariant {

        @Test
        void comeca_em_1() {
            assertThat(chat("t", "a").contentVersion()).isEqualTo(1);
        }

        @Test
        void incrementa_quando_o_conteudo_muda() {
            var c = chat("t", "a", "b");
            assertThat(c.applyImportedContent("t", messages("a", "b", "c"))).isTrue();
            assertThat(c.contentVersion()).isEqualTo(2);
        }

        @Test
        void nao_incrementa_quando_o_conteudo_e_identico() {
            var c = chat("t", "a", "b");
            assertThat(c.applyImportedContent("t", messages("a", "b"))).isFalse();
            assertThat(c.contentVersion()).isEqualTo(1);
        }

        @Test
        void nao_incrementa_quando_apenas_o_ordem_das_chamadas_muda() {
            var c = chat("t", "a", "b");
            var invertido = List.of(
                    Message.of("z", Role.USER, "b", 1, T0.plusSeconds(1)),
                    Message.of("y", Role.USER, "a", 0, T0));
            assertThat(c.applyImportedContent("t", invertido)).isFalse();
            assertThat(c.contentVersion()).isEqualTo(1);
        }

        @Test
        void marcar_removido_e_idempotente_e_nao_altera_versao() {
            var c = chat("t", "a");
            var v = c.contentVersion();

            c.markDeleted();
            c.markDeleted();

            assertThat(c.deleted()).isTrue();
            assertThat(c.contentVersion()).isEqualTo(v);
        }
    }

    @Nested
    @DisplayName("Invariante 4: contentHash e deterministico")
    class HashInvariant {

        @Test
        void mesmo_conteudo_gera_o_mesmo_hash() {
            assertThat(chat("t", "a", "b").contentHash())
                    .isEqualTo(chat("t", "a", "b").contentHash());
        }

        @Test
        void conteudo_diferente_gera_hash_diferente() {
            assertThat(chat("t", "a").contentHash())
                    .isNotEqualTo(chat("t", "b").contentHash());
        }

        @Test
        void hash_ignora_a_ordem_das_chamadas() {
            var invertido = List.of(
                    Message.of("z", Role.USER, "b", 1, T0),
                    Message.of("y", Role.USER, "a", 0, T0));
            assertThat(chat("t", "a", "b").contentHash())
                    .isEqualTo(Chat.create(ChatSource.JSON, "e", "t", T0, T0, invertido).contentHash());
        }

        @Test
        void hash_tem_64_caracteres_hexadecimal() {
            assertThat(chat("t", "a").contentHash())
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        void messageHashChangesWithRole() {
            assertThat(ContentHash.ofMessage(Role.USER, "mesmo texto"))
                    .isNotEqualTo(ContentHash.ofMessage(Role.ASSISTANT, "mesmo texto"));
        }
    }

    @Nested
    @DisplayName("Invariante 5: hasSameContentAs e consistente com applyImportedContent")
    class SameContentInvariant {

        @Test
        void reconhece_conteudo_igual() {
            var c = chat("t", "a", "b");
            assertThat(c.hasSameContentAs("t", messages("a", "b"))).isTrue();
        }

        @Test
        void nao_reconhece_conteudo_diferente() {
            var c = chat("t", "a", "b");
            assertThat(c.hasSameContentAs("t", messages("a", "z"))).isFalse();
        }

        @Test
        void hasSameContentConsidersTitle() {
            var c = chat("t", "a");
            assertThat(c.hasSameContentAs("outro", messages("a"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Favoritos — flag bidirecional (ADR-005)")
    class FavoriteInvariant {

        @Test
        void alterna_favorito_e_incrementa_a_versao() {
            var c = chat("t", "a");
            assertThat(c.setFavorite(true)).isTrue();
            assertThat(c.isFavorite()).isTrue();
            assertThat(c.contentVersion()).isEqualTo(2);
        }

        @Test
        void repetir_o_mesmo_valor_nao_altera_a_versao() {
            var c = chat("t", "a");
            c.setFavorite(true);
            assertThat(c.setFavorite(true)).isFalse();
            assertThat(c.contentVersion()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Metadados")
    class Metadata {

        @Test
        void external_id_em_branco_vira_nulo() {
            assertThat(Chat.create(ChatSource.MARKDOWN, "   ", "t", T0, T0, messages("a")).externalId())
                    .isNull();
        }

        @Test
        void messageCountReflectsTotal() {
            assertThat(chat("t", "a", "b", "c").messageCount()).isEqualTo(3);
        }

        @Test
        void createdAtFallsBackToImportedAt() {
            var c = Chat.create(ChatSource.JSON, "e", "t", null, T0, messages("a"));
            assertThat(c.createdAt()).isEqualTo(T0);
        }

        @Test
        void updatedAtUsesLatestMessage() {
            var c = chat("t", "a");
            c.applyImportedContent("t", List.of(
                    Message.of("a", Role.USER, "antiga", 0, T0),
                    Message.of("b", Role.ASSISTANT, "recente", 1, T0.plusSeconds(600))));
            assertThat(c.updatedAt()).isEqualTo(T0.plusSeconds(600));
        }

        @Test
        void createdUpdatedAtUsesLatestMessage() {
            var c = chat("t", "a", "b");
            assertThat(c.updatedAt()).isEqualTo(T0.plusSeconds(1));
        }

        @Test
        void createdUpdatedAtFallsBackToSourceUpdatedAt() {
            var semTempo = List.of(Message.of("a", Role.USER, "x", 0, null));
            var sourceUpdatedAt = T0.plusSeconds(500);
            var c = Chat.create(ChatSource.JSON, "e", "t", T0, sourceUpdatedAt, T0, semTempo);
            assertThat(c.updatedAt()).isEqualTo(sourceUpdatedAt);
        }

        @Test
        void createdUpdatedAtFallsBackToCreatedAtWhenNothingElse() {
            var semTempo = List.of(Message.of("a", Role.USER, "x", 0, null));
            var c = Chat.create(ChatSource.JSON, "e", "t", T0, null, T0, semTempo);
            assertThat(c.updatedAt()).isEqualTo(T0);
        }

        @Test
        void messagesIsUnmodifiable() {
            var c = chat("t", "a");
            assertThatThrownBy(() -> c.messages().add(Message.of("z", Role.USER, "x", 9, T0)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
