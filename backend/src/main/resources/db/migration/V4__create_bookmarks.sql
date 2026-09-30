-- V4: bookmarks de mensagens (secao 10 da especificacao).
-- Um bookmark aponta para uma mensagem especifica de uma conversa. O indice
-- unico torna o POST idempotente por (chat, mensagem).

CREATE TABLE bookmarks (
    id         VARCHAR(36)   PRIMARY KEY,
    chat_id    VARCHAR(36)   NOT NULL,
    message_id VARCHAR(36)   NOT NULL,
    note       VARCHAR(1000),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_bookmarks_chat    FOREIGN KEY (chat_id)    REFERENCES chats (id)    ON DELETE CASCADE,
    CONSTRAINT fk_bookmarks_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
    CONSTRAINT uq_bookmarks_chat_message UNIQUE (chat_id, message_id)
);

CREATE INDEX idx_bookmarks_chat    ON bookmarks (chat_id);
CREATE INDEX idx_bookmarks_created ON bookmarks (created_at DESC);
