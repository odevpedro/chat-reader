-- V2: mensagens.
-- Conteudo CRU (Markdown preservado): o render e do cliente Kindle, o backend
-- apenas armazena, faz full-text e gera snippet (docs/domain.md secao 2.2).

CREATE TABLE messages (
    id           VARCHAR(36) PRIMARY KEY,
    chat_id      VARCHAR(36) NOT NULL,
    external_id  VARCHAR(200),
    role         VARCHAR(20) NOT NULL,
    content      TEXT         NOT NULL,
    sequence     INTEGER      NOT NULL,
    created_at   TIMESTAMPTZ,
    content_hash VARCHAR(64)  NOT NULL,
    CONSTRAINT fk_messages_chat
        FOREIGN KEY (chat_id) REFERENCES chats (id) ON DELETE CASCADE,
    CONSTRAINT ck_messages_role
        CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL', 'UNKNOWN')),
    CONSTRAINT ck_messages_sequence CHECK (sequence >= 0)
);

-- sequence unico por chat: base da paginacao incremental do leitor (secao 24).
CREATE UNIQUE INDEX uq_messages_chat_sequence ON messages (chat_id, sequence);

CREATE INDEX idx_messages_chat_created ON messages (chat_id, created_at);
CREATE INDEX idx_messages_role        ON messages (role);
