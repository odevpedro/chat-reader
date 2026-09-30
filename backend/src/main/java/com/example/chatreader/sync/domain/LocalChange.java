package com.example.chatreader.sync.domain;

/**
 * Mudanca escrita pelo Kindle enquanto estava offline, enviada no ACK
 * (docs/sync-protocol.md, secao 5).
 *
 * <p>Tipo fechado de proposito: o servidor so aceita o que ele sabe aplicar.
 * Conteudo de conversa <b>nao</b> entra aqui — o backend e a fonte da verdade do
 * conteudo (ADR-005); se o cliente enviar, a resposta e um rejeicao.
 */
public sealed interface LocalChange {

    String type();

    /** Cria (ou confirma) um bookmark. O {@code id} vem do cliente: reenvio e no-op. */
    record BookmarkCreate(String id, String chatId, String messageId, String note) implements LocalChange {
        @Override
        public String type() {
            return "BOOKMARK_CREATED";
        }
    }

    /** Remove um bookmark. Idempotente: item ja ausente e aceito. */
    record BookmarkDelete(String id) implements LocalChange {
        @Override
        public String type() {
            return "BOOKMARK_DELETED";
        }
    }

    /** Marca/desmarca favorito. Valor absoluto: reenviar converge (LWW — ADR-005). */
    record ChatFavorite(String chatId, boolean favorite) implements LocalChange {
        @Override
        public String type() {
            return "CHAT_FAVORITE";
        }
    }

    /** Tentativa de escrever conteudo de conversa. Sempre rejeitada. */
    record ContentWrite(String chatId, String motivo) implements LocalChange {
        @Override
        public String type() {
            return "CHAT_CONTENT";
        }
    }
}
