# ADR-006 — Busca full-text no PostgreSQL, sem Elasticsearch

- **Status**: aceito
- **Data**: 2026-09-29

## Contexto

O §13 exige busca por título, conteúdo das mensagens e tags, com snippet. O §38 exige
suporte a 10.000 chats e 100.000+ mensagens. O §41 proíbe Elasticsearch, Kafka e Redis.

## Decisão

Busca full-text nativa do PostgreSQL 16:

```sql
-- V6
CREATE EXTENSION IF NOT EXISTS pg_trgm;
ALTER TABLE messages
  ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('portuguese', coalesce(content, ''))) STORED;
CREATE INDEX idx_messages_search_vector ON messages USING GIN (search_vector);
CREATE INDEX idx_chats_title_trgm ON chats USING gin (title gin_trgm_ops);
```

Consulta de busca:

```sql
WITH hits AS (
  SELECT m.chat_id,
         ts_rank(m.search_vector, q) AS rank,
         ts_headline('portuguese', m.content, q,
             'MaxFragments=1,MaxWords=25,MinWords=10,StartSel=<mark>,StopSel=</mark>') AS snippet
  FROM messages m, websearch_to_tsquery('portuguese', :q) q
  WHERE m.search_vector @@ q
  ORDER BY rank DESC
  LIMIT 500
)
SELECT DISTINCT ON (c.id) c.id, c.title, h.snippet, h.rank
FROM hits h JOIN chats c ON c.id = h.chat_id
WHERE c.deleted = false
ORDER BY c.id, h.rank DESC;
```

Título via `ILIKE` + trigram; tags via `EXISTS` sobre `chat_tags`. Paginação por **keyset**
(`LIMIT/OFFSET` degrada em 100k+).

`websearch_to_tsquery` aceita a entrada do usuário como está, sem erro de sintaxe, o que
elimina uma classe inteira de bugs de busca.

## Consequências

**Positivas**: zero infraestrutura nova; transacional com os dados; snippet nativo;
highlight pronto; mantém o compose em dois serviços como exige o §28.

**Negativas**: sem sinônimo, sem stemming customizado além do dicionário `portuguese`;
`ts_rank` é mais pobre que um motor dedicado; relevance tuning exige know-how de Postgres.

**Mitigações**: `ANALYZE` após import; índice GIN mantém o custo estável em 100k+ linhas;
se a busca virar insuficiente, o `SearchService` é a **única** classe a reescrever —
Elasticsearch pode ser adicionado atrás da mesma interface sem tocar API nem Kindle.

## Alternativas rejeitadas

- **`ILIKE` sem tsvector**: rejeitado — `%termo%` não usa índice e é O(n) na tabela
  inteira de mensagens.
- **Busca só no SQLite do Kindle**: rejeitado — o backend também precisa buscar (§13, §38).
- **Elasticsearch**: rejeitado pelo §41 e por custo operacional desproporcional.
- **Busca no app (scan em Java)**: rejeitado — sem índice, usa memória do servidor.
