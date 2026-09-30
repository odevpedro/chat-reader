-- V1: conversas.
-- O schema e sempre criado por migration; ddl-auto nunca 'create'/'update'
-- (secao 29). Nenhuma credencial ou dado de usuario aqui.

CREATE TABLE chats (
    id              VARCHAR(36)  PRIMARY KEY,
    source          VARCHAR(40)  NOT NULL,
    external_id     VARCHAR(200),
    title           VARCHAR(500) NOT NULL,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    imported_at     TIMESTAMPTZ,
    content_version BIGINT       NOT NULL DEFAULT 1,
    content_hash    VARCHAR(64)  NOT NULL,
    favorite        BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    message_count   INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT ck_chats_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_chats_content_version CHECK (content_version > 0),
    CONSTRAINT ck_chats_message_count CHECK (message_count >= 0)
);

-- Idempotencia por identidade externa (secao 12).
-- Parcial: chats sem external_id (ex.: Markdown) nao colidem, e um external_id
-- reaproveitado apos o chat ser deletado pode ser reutilizado.
CREATE UNIQUE INDEX uq_chats_source_external
    ON chats (source, external_id)
    WHERE external_id IS NOT NULL AND deleted = FALSE;

-- Idempotencia por conteudo, para chats sem identidade externa.
CREATE UNIQUE INDEX uq_chats_source_content_hash
    ON chats (source, content_hash)
    WHERE deleted = FALSE;

CREATE INDEX idx_chats_updated_at ON chats (updated_at DESC NULLS LAST);
CREATE INDEX idx_chats_favorite    ON chats (favorite) WHERE favorite = TRUE AND deleted = FALSE;
CREATE INDEX idx_chats_deleted     ON chats (deleted);
