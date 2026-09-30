-- V3: tags e a associacao N:N com chats (secao 8 da especificacao).
-- O nome canonico e exibido como o usuario digitou; normalized_name e a chave
-- case-insensitive usada para deduplicar ("Java" == "java").
-- A associacao e reescrita a cada save do agregado (poucas tags por chat).

CREATE TABLE tags (
    id              VARCHAR(36)  PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    normalized_name VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tags_normalized   UNIQUE (normalized_name),
    CONSTRAINT ck_tags_name_not_blank CHECK (length(btrim(name)) > 0)
);

CREATE TABLE chat_tags (
    chat_id VARCHAR(36) NOT NULL,
    tag_id  VARCHAR(36) NOT NULL,
    PRIMARY KEY (chat_id, tag_id),
    CONSTRAINT fk_chat_tags_chat FOREIGN KEY (chat_id) REFERENCES chats (id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_tags_tag  FOREIGN KEY (tag_id)  REFERENCES tags (id)  ON DELETE CASCADE
);

CREATE INDEX idx_chat_tags_tag ON chat_tags (tag_id);
