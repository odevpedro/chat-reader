-- V6: change log do sync (secoes 15/16; docs/sync-protocol.md).
-- O token de sincronizacao E a coluna version desta tabela: um BIGSERIAL global,
-- monotonico e sem lacunas. Nao usamos updated_at (colide em lote, nao sobrevive
-- a ajuste de relogio e nao registra remocao).
--
-- A tabela guarda apenas metadados da mudanca. O payload (chat, bookmark, tag) e
-- montado na leitura, a partir do estado atual: assim nao duplicamos conteudo de
-- mensagem e o cliente nunca recebe um snapshot congelado no passado.
--
-- A retencao (30 dias) e aplicada oportunisticamente em cada GET /api/sync.

CREATE TABLE sync_change_log (
    version         BIGSERIAL    PRIMARY KEY,
    change_type     VARCHAR(30)  NOT NULL,
    entity_type     VARCHAR(20)  NOT NULL,
    entity_id       VARCHAR(36)  NOT NULL,
    chat_id         VARCHAR(36),
    content_version BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_sync_change_type CHECK (change_type IN (
        'CHAT_CREATED', 'CHAT_UPDATED', 'CHAT_DELETED',
        'MESSAGE_CREATED', 'MESSAGE_UPDATED', 'MESSAGE_DELETED',
        'TAG_CREATED', 'TAG_UPDATED', 'TAG_DELETED',
        'BOOKMARK_CREATED', 'BOOKMARK_UPDATED', 'BOOKMARK_DELETED'
    )),
    CONSTRAINT ck_sync_entity_type CHECK (entity_type IN ('CHAT', 'MESSAGE', 'TAG', 'BOOKMARK'))
);

-- Consulta quente: "changes since X, ordenadas por version, limitado a N".
CREATE INDEX idx_sync_change_log_version ON sync_change_log (version);

-- Poda por data (retencao).
CREATE INDEX idx_sync_change_log_created ON sync_change_log (created_at);
