# API REST

Base local: `http://localhost:8080`. Documentação interativa: `/swagger-ui.html`;
OpenAPI em `/v3/api-docs`.

## Autenticação

Com `AUTH_ENABLED=true` (padrão), todos os endpoints sob `/api` exigem:

```
Authorization: Bearer <JWT>
```

O token é obtido no login e expira em `JWT_EXPIRATION_DAYS` dias (padrão 30).
`/actuator/health`, `/actuator/info` e a própria documentação continuam públicos.

### `POST /api/auth/login`

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"reader","password":"sua-senha"}'
```

```json
{ "token": "eyJhbGciOiJIUzI1NiJ9...", "expiresAt": "2026-10-29T12:00:00Z" }
```

Credenciais erradas devolvem **401**. A resposta nunca ecoa o token recebido.

## Conversas

### `GET /api/chats`

Lista conversas, mais recentes primeiro. Não carrega o conteúdo das mensagens.

| Parâmetro | Padrão | Limites |
|-----------|--------|---------|
| `page` | `0` | `>= 0` |
| `size` | `20` | `1..200` |
| `favorite` | — | `true` lista apenas favoritos |
| `tag` | — | filtra por tag (case-insensitive) |

```json
{
  "items": [
    {
      "id": "0f9c1c9a-1b2c-4d3e-8f10-aabbccddeeff",
      "title": "Spring @Transactional na prática",
      "source": "JSON",
      "externalId": "sample-spring-transactional",
      "createdAt": "2026-09-20T14:00:00Z",
      "updatedAt": "2026-09-20T14:06:00Z",
      "importedAt": "2026-09-29T12:00:00Z",
      "contentVersion": 1,
      "contentHash": "…",
      "favorite": false,
      "messageCount": 4,
      "tags": ["java", "spring"]
    }
  ],
  "page": 0,
  "size": 20,
  "total": 3,
  "last": true,
  "totalPages": 1
}
```

### `GET /api/chats/{id}`

Devolve a conversa completa, com as mensagens na ordem (`sequence` 0..N-1).

```json
{
  "chat": { "…": "mesmos campos do resumo" },
  "messages": [
    { "id": "…", "role": "USER", "sequence": 0, "content": "Pergunta **Markdown**", "createdAt": "…", "contentHash": "…" }
  ]
}
```

Id inexistente → **404**.

### `GET /api/chats/{id}/messages`

Pagina as mensagens de uma conversa (`page` de 0, `size` de 1 a 500, padrão 20).
É o que o leitor do Kindle usa para não carregar a conversa inteira.

```json
{
  "items": [{ "id": "…", "role": "USER", "sequence": 0, "content": "…", "createdAt": "…", "contentHash": "…" }],
  "page": 0, "size": 20, "total": 87, "last": false, "totalPages": 5
}
```

Id inexistente → **404**.

### `DELETE /api/chats/{id}`

Marca a conversa como removida (tombstone) e apaga os bookmarks dela. A linha e as
mensagens permanecem no banco, protegidas pela retenção, para que um cliente que
sincronize depois ainda receba o evento `CHAT_DELETED`. Responde **204**.
Id inexistente → **404**.

### `PUT /api/chats/{id}/favorite`

Marca/desmarca a conversa como favorita (LWW — ADR-005).

```bash
curl -X PUT http://localhost:8080/api/chats/0f9c1c9a-.../favorite \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"favorite": true}'
```

Responde o resumo atualizado. Id inexistente → **404**.

### `PUT /api/chats/{id}/tags`

Substitui o conjunto de tags da conversa. Nomes são normalizados (trim, sem
duplicatas case-insensitive, ordenados; máx. 64 tags de 100 caracteres).

```bash
curl -X PUT http://localhost:8080/api/chats/0f9c1c9a-.../tags \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tags": ["java", "spring"]}'
```

Id inexistente → **404**.

> Não existe endpoint para **escrever** conteúdo de mensagem: o backend é a fonte
> da verdade do conteúdo (ADR-005). Tags e favoritos são metadados editáveis;
> o conteúdo só chega por importação.

## Tags

### `GET /api/tags`

```json
{ "tags": ["java", "spring"] }
```

### `POST /api/tags`

Cria uma tag no catálogo. É **idempotente** por nome case-insensitive: criar
`go` quando `Go` existe devolve o nome já existente.

```bash
curl -X POST http://localhost:8080/api/tags \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name": "Go"}'
```

```json
{ "name": "Go" }
```

Responde **201**. Nome em branco → **400**.

## Busca

### `GET /api/chats/search`

Busca full-text em **título**, **conteúdo das mensagens** e **tags** (seção 13).
Usa a configuração `simple` (sem stemming), adequada a português e inglês.

| Parâmetro | Padrão | Limites |
|-----------|--------|---------|
| `q` | — | obrigatório |
| `page` | `0` | `>= 0` |
| `size` | `20` | `1..200` |

```bash
curl "http://localhost:8080/api/chats/search?q=transactional&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "query": "transactional",
  "results": [
    {
      "chatId": "0f9c1c9a-...",
      "title": "Spring @Transactional na prática",
      "messageId": "a1b2c3...",
      "role": "ASSISTANT",
      "snippet": "…TransactionManager2…",
      "createdAt": "2026-09-20T14:04:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1,
  "last": true
}
```

- Casamento por conteúdo: cada mensagem correspondente vira um resultado (com
  `snippet` em texto puro).
- Casamento apenas por título/tag: um único resultado por conversa, usando a
  primeira mensagem como `messageId`.
- A configuração `simple` **não** remove acentos: buscar por `indice` não casa
  com `índice`. Busque com o acento ou use a tag.
- `q` ausente → **400**.

## Bookmarks

### `GET /api/bookmarks`

Lista bookmarks, mais recentes primeiro. Aceita `page`, `size` e o filtro
opcional `chatId`.

### `POST /api/bookmarks`

Cria um bookmark para uma mensagem específica.

```bash
curl -X POST http://localhost:8080/api/bookmarks \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chatId":"0f9c1c9a-...","messageId":"a1b2c3...","note":"rever"}'
```

```json
{
  "id": "b00k...",
  "chatId": "0f9c1c9a-...",
  "messageId": "a1b2c3...",
  "note": "rever",
  "createdAt": "2026-09-29T12:00:00Z"
}
```

Responde **201**. Chat/mensagem inexistente → **404**; bookmark já existente
para a mesma mensagem → **409**. A nota é truncada em 1000 caracteres.

### `DELETE /api/bookmarks/{id}`

Remove o bookmark. Responde **204**. Id inexistente → **404**.

> Ao remover a conversa, seus bookmarks são apagados. Uma reimportação que altere o
> conteúdo recria as mensagens (novos ids), então bookmarks daquela conversa podem
> ser perdidos — limitação conhecida e documentada em [sync-protocol](sync-protocol.md) §7.

## Sincronização

Contrato completo em [sync-protocol](sync-protocol.md). Resumo:

| Endpoint | Para quê |
|----------|----------|
| `GET /api/sync` | delta incremental a partir de um token |
| `GET /api/sync/snapshot` | estado completo, paginado por chat |
| `POST /api/sync/ack` | confirma o token e envia escritas offline |

### `GET /api/sync`

| Parâmetro | Padrão | Limite |
|-----------|--------|--------|
| `since` | `0` | token anterior |
| `limit` | `200` | 1000 |

```bash
curl "http://localhost:8080/api/sync?since=1844&limit=200" -H "Authorization: Bearer $TOKEN"
```

```json
{
  "syncToken": 2310,
  "hasMore": false,
  "fullResyncRequired": false,
  "serverTime": "2026-09-29T15:10:00Z",
  "changes": [
    {
      "version": 2305,
      "type": "CHAT_UPDATED",
      "entityType": "CHAT",
      "entityId": "0f9c1c9a-...",
      "chatId": "0f9c1c9a-...",
      "contentVersion": 8,
      "createdAt": "2026-09-29T15:09:00Z",
      "chat": {
        "id": "0f9c1c9a-...",
        "title": "Spring Transactional",
        "source": "CHATGPT_EXPORT",
        "messageCount": 87,
        "tags": [{ "id": "…", "name": "Java" }],
        "messages": [{ "id": "…", "role": "USER", "sequence": 0, "content": "…", "createdAt": "…", "contentHash": "…" }],
        "messagesOmitted": false
      }
    }
  ]
}
```

Tipos de evento: `CHAT_CREATED`, `CHAT_UPDATED`, `CHAT_DELETED`, `TAG_CREATED`,
`BOOKMARK_CREATED`, `BOOKMARK_DELETED`. Os campos `chat`, `tag` e `bookmark` vêm
preenchidos conforme o tipo (os demais são omitidos do JSON); em remoções, `entityId`
basta.

`fullResyncRequired: true` significa **"o delta não entrega o estado inteiro"** — chame o
snapshot. Acontece em dois casos: `since=0` (cliente ainda não tem token) e token
anterior à janela de 30 dias do log. Ver [sync-protocol §4.4](sync-protocol.md).

### `GET /api/sync/snapshot`

| Parâmetro | Padrão | Limite |
|-----------|--------|--------|
| `page` | `0` | — |
| `size` | `50` | 500 |

```json
{
  "syncToken": 5120,
  "page": 0, "size": 50, "total": 3, "last": true,
  "tags": [{ "id": "…", "name": "Java" }],
  "chats": [{ "id": "…", "title": "…", "messages": [], "messagesOmitted": false }],
  "bookmarks": [],
  "bookmarksOmitted": false
}
```

O cliente **apaga** suas tabelas locais, aplica o snapshot e grava o `syncToken`.
Conversas acima de `chatreader.sync.max-messages-per-change` (200) vêm só com
metadados (`messagesOmitted: true`) — a leitura usa
`GET /api/chats/{id}/messages`. Se sobrarem bookmarks, `bookmarksOmitted` manda
buscar o resto em `GET /api/bookmarks`.

### `POST /api/sync/ack`

```json
{
  "ackVersion": 2310,
  "clientId": "kobo-clara-2f9a",
  "localChanges": [
    { "type": "BOOKMARK_CREATED", "payload": { "id": "b1f0...", "chatId": "0f9c...", "messageId": "m-42" } },
    { "type": "CHAT_FAVORITE", "payload": { "chatId": "0f9c...", "favorite": true } }
  ]
}
```

```json
{ "ackVersion": 2310, "syncToken": 2312, "accepted": 2, "rejected": 0, "rejections": [] }
```

Aceita `BOOKMARK_CREATED`, `BOOKMARK_DELETED` e `CHAT_FAVORITE` — tudo
idempotente, então reenviar é seguro. `CHAT_CONTENT` (escrita de conteúdo) é
sempre recusado com `CONFLICT`; o Kindle nunca escreve conteúdo (ADR-005).
Falhas de negócio voltam em `rejections` com o índice do item; JSON inválido ou
tipo desconhecido → **400** para a requisição inteira.

## Importação

### `POST /api/chats/import` (multipart)

```bash
curl -X POST http://localhost:8080/api/chats/import \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@sample-data/chats.json"
```

### `POST /api/chats/import` (`application/json`)

Para payloads menores, envie o conteúdo no corpo:

```json
{ "content": "{ \"schemaVersion\": 1, \"chats\": [ … ] }", "filename": "chats.json" }
```

Parâmetro opcional `format` (`JSON`) força o adapter; sem ele, o formato é
detectado. Resposta:

```json
{ "total": 3, "created": 3, "updated": 0, "skipped": 0, "failed": 0, "failures": [] }
```

Reimportar o mesmo arquivo devolve `created: 0` e `skipped: 3` (idempotência).

### `GET /api/chats/import/formats`

```json
{ "formats": ["JSON"] }
```

## Erros

Erros seguem o formato [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807)
(`ProblemDetail`):

```json
{
  "type": "about:blank",
  "title": "Recurso nao encontrado",
  "status": 404,
  "detail": "Chat nao encontrado: 123e4567-...",
  "instance": "/api/chats/123e4567-...",
  "timestamp": "2026-09-29T12:00:00Z"
}
```

| Status | Quando |
|--------|--------|
| `400` | parâmetro fora dos limites / requisição inválida |
| `401` | token ausente ou inválido |
| `404` | recurso inexistente |
| `409` | conflito (ex.: bookmark duplicado para a mesma mensagem) |
| `413` | arquivo acima de `IMPORT_MAX_BYTES` |
| `422` | formato desconhecido ou arquivo de importação inválido |
| `500` | erro inesperado (detalhes ficam nos logs, nunca no corpo) |

No sync, `409` não aparece: um conflito de conteúdo vira `rejections[].reason =
"CONFLICT"` dentro do **200** do ACK, para que as demais escritas do lote sejam
aplicadas.

## Observabilidade

| Endpoint | Conteúdo |
|----------|----------|
| `/actuator/health` | saúde (inclui `liveness`/`readiness`) |
| `/actuator/info` | build + contagem de conversas/mensagens |
| `/actuator/metrics` | métricas, incl. `chatreader.imports_total`, `chatreader.import_chats_total` (tag `outcome`), `chatreader.import_duration`, `chatreader.sync_requests_total`, `chatreader.sync_changes_total` e `chatreader.search_requests_total` |
