-- V5: indices de busca full-text (secao 13; risco R6).
-- Usamos a configuracao 'simple' (sem stemming) para funcionar em portugues e
-- ingles sem depender de extensoes. Colunas geradas STORED + GIN: o tsvector
-- acompanha title/content automaticamente em INSERT/UPDATE.
-- Observacao: V5 e a busca; o sync (Etapa 4) sera V6 (numeracao sequencial —
-- a versao prevista no plano de V6 para busca criaria uma migration fora de ordem).

ALTER TABLE chats ADD COLUMN title_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, coalesce(title, ''))) STORED;

CREATE INDEX idx_chats_title_tsv ON chats USING GIN (title_tsv);

ALTER TABLE messages ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, coalesce(content, ''))) STORED;

CREATE INDEX idx_messages_content_tsv ON messages USING GIN (content_tsv);
