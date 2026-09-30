# System Feature Flows — Chat Reader for Kindle

> Registro histórico das funcionalidades e de como elas percorrem o sistema.
> Cada feature é acrescentada **ao final** do documento. Funcionalidades anteriores
> nunca são removidas nem sobrescritas.

Estrutura de cada feature (ver [spec de fluxos](../../chtkindle.md)):

1. Resumo
2. Fluxo principal — entrada, validação, orquestração, regras de negócio,
   persistência, resposta
3. Fluxos alternativos e erros
4. Decisões técnicas importantes
5. Trechos de código relevantes
6. Regras importantes

Escopo: as features implementadas até a Etapa 6. Índice:

| # | Feature | Camada |
|---|---|---|
| 01 | [Autenticação por token](#feature-01--autenticação-por-token) | `security` |
| 02 | [Importação de conversas](#feature-02--importação-de-conversas) | `importer` + `chat` |
| 03 | [Adapters de formato (JSON, Markdown, ChatGPT)](#feature-03--adapters-de-formato) | `importer` |
| 04 | [Listagem e leitura de conversas](#feature-04--listagem-e-leitura-de-conversas) | `chat` |
| 05 | [Favoritos](#feature-05--favoritos) | `chat` |
| 06 | [Tags](#feature-06--tags) | `tag` |
| 07 | [Bookmarks de mensagens](#feature-07--bookmarks-de-mensagens) | `bookmark` |
| 08 | [Busca full-text](#feature-08--busca-full-text) | `search` |
| 09 | [Delta sync](#feature-09--delta-sync) | `sync` |
| 10 | [Snapshot](#feature-10--snapshot) | `sync` |
| 11 | [ACK e escritas offline](#feature-11--ack-e-escritas-offline) | `sync` |
| 12 | [Change log e retenção](#feature-12--change-log-e-retenção) | `sync` |
| 13 | [Limpeza local do Kindle](#feature-13--limpeza-local-do-kindle) | `kindle` |

Referência de dados: [data-model.md](data-model.md). Protocolo completo:
[sync-protocol.md](sync-protocol.md).

---

# Feature: 01 — Autenticação por token

## Resumo

Uma instalação tem **um único usuário**. O login devolve um JWT HMAC-SHA256 válido por
30 dias; todas as demais rotas exigem `Authorization: Bearer <token>`. Existe para que
o backend não possa ser exposto na rede local junto com o conteúdo privado das
conversas — não há multiusuário, cadastro ou refresh token.

## Fluxo principal

### 1. Ponto de entrada

`POST /api/auth/login` → `AuthController.login` (`security/AuthController.java`).

### 2. Validação de entrada

Os records `LoginRequest` declaram `@NotBlank`, **mas `AuthController.login` não tem
`@Valid`** — a anotação nunca é processada. Na prática um username em branco não gera
400: ele segue para a comparação e falha como credencial errada (**401**). A
validação anotada é aspiracional, não comportamento.

Se `chatreader.auth.enabled=false`, o controller responde `authenticated: true` sem
verificar nada (`AuthProperties.isEnabled()`), para desenvolvimento local.

### 3. Orquestração da aplicação

`AuthController` não tem service: monta `LoginRequest` → `JwtService.issue(username)`
→ `LoginResponse(token, expiresAt)`.

### 4. Regras de negócio

- A senha é comparada contra `CHAT_READER_PASSWORD_HASH` com
  `BCryptPasswordEncoder`. O backend **nunca** vê a senha em texto claro, e o BCrypt
  é o que torna a comparação resistente a timing por construção.
- `expiration-days` padrão 30 (`CHAT_READER_EXPIRATION_DAYS`).
- A comparação de hash é a única linha de código que toca a credencial.

### 5. Persistência / Integrações

Nenhuma. Não há tabela de usuários: usuário e hash vivem no ambiente, e o JWT é
autossuficiente.

### 6. Resposta final

```json
{ "token": "eyJhbGciOi...", "expiresAt": "2026-10-30T12:00:00Z" }
```

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| Credenciais erradas | **401** + `BadCredentialsException`; mensagem genérica, sem dizer se foi usuário ou senha |
| Username ou senha em branco | **401** (sem `@Valid` no controller, cai na comparação de hash) |
| Hash não configurado (`CHAT_READER_PASSWORD_HASH` vazio) | **401** — `matchesPassword` devolve `false` para hash vazio. A falha é silenciosa, e é uma dívida conhecida |
| Rota protegida sem header | **401** |
| Token expirado ou com assinatura inválida | **401** |

## Decisões técnicas importantes

- **Stateless.** Sem sessão, sem tabela, sem `HttpSession`: reiniciar o backend não
  derruba ninguém.
- **Um usuário só.** Modelar contas aqui seria trabalho sem caso de uso — o app é
  pessoal por definição.
- **`MessageDigest.isEqual` e não `String.equals`.** Comparecer hash com `equals`
  admite timing attack; o custo de mudar é zero.
- **Falhar alto se não configurado.** Preferível a um default permissivo.

## Trechos de código relevantes

```java
// security/JwtService.java
private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
```

### Dívidas conhecidas

- **Não há rate limit.** `RateLimiter` aparece no plano de arquitetura original, mas
  não existe no código: não há classe, não há handler de 429, e não há throttling de
  login. Um backend exposto na rede local aceita tentativas ilimitadas.
- **`@NotBlank` sem `@Valid`.** As annotations existem e não valem nada.
- **Hash vazio falha em silêncio**, com 401 indistinguível de senha errada.

## Regras importantes

- O token **não** expõe conteúdo: só `sub`, `iat`, `exp`.
- Nenhum segredo em log; `logging.level.com.example.chatreader: INFO` e o padrão de
  log não imprime headers.
- `.env`, `*.jwt`, `*.pem`, `*.key` e `kindle/settings.lua` estão no `.gitignore`.

---

# Feature: 02 — Importação de conversas

## Resumo

`POST /api/chats/import` recebe um arquivo e grava as conversas de forma **idempotente**.
O arquivo inteiro é uma transação; um item inválido é pulado e reportado, sem derrubar
o lote. É a única porta de entrada de conteúdo no sistema.

## Fluxo principal

### 1. Ponto de entrada

Duas rotas em `ImportController`:

| Rota | Content-Type | Uso |
|---|---|---|
| `POST /api/chats/import` | `multipart/form-data` | arquivo do disco |
| `POST /api/chats/import` (JSON) | `application/json` | `{ "content": "...", "filename": "..." }` |

### 2. Validação de entrada

| Verificação | Onde | Falha |
|---|---|---|
| Autenticação | filtro JWT | 401 |
| Tamanho do arquivo | `multipart.max-file-size` = `IMPORT_MAX_BYTES` (10 MB) | 413 |
| `chatreader.import.max-bytes` | **ninguém lê** — só o multipart do Spring limita | 413 |
| `chatreader.import.max-chats` (10 000) | `ImportService`, antes de qualquer escrita | 422 |
| Formato reconhecido | `ChatImporterRegistry` | 422 |
| Conversa sem mensagens | `ImportService.persistOne` | item `failed` |
| Conversa acima de `max-messages-per-chat` (5 000) | `ImportService.persistOne` | item `failed` |

### 3. Orquestração da aplicação

`ImportService.importSource(ImportSource)` — `@Transactional`, a unidade de trabalho:

```
registry.resolve(format)        -> adapter (ou detecção por assinatura)
adapter.importChats(source)     -> List<NormalizedChat>   (sem banco)
gate de max-chats               -> ImportException se exceder
para cada NormalizedChat:
    valida (mensagens > 0, tamanho)
    locateExisting(source, externalId | contentHash)
    Chat.create(...)   -> save -> changeLogWriter.chatCreated -> CREATED
    applyImportedContent -> save -> changeLogWriter.chatUpdated -> UPDATED
    (hash e tags iguais) -> SKIPPED, sem escrita
```

### 4. Regras de negócio

- **Prioridade de identidade:** `(source, external_id)` primeiro; sem ele,
  `(source, content_hash)`. O passo 2 também roda quando havia `externalId` e a busca
  não achou — ver [data-model.md → Idempotency](data-model.md#idempotency).
- **Título nunca vazio:** `normalizeTitle` usa `"Conversa sem titulo"`, aplica
  `strip` e corta em 500.
- **Papel desconhecido nunca falha:** vira `UNKNOWN`.
- **`SKIPPED` não escreve nada** — nem o chat, nem o change log, e **não** incrementa
  `content_version`. É o que torna reimportar o mesmo arquivo barato.
- **`UPDATED` é in-place:** as mensagens são `DELETE` + `INSERT` dentro da mesma
  transação, e `content_version` incrementa.

### 5. Persistência / Integrações

| Chamada | Onde |
|---|---|
| `ChatRepositoryAdapter.save` | JPA, `chats` + `messages` |
| `TagRepositoryAdapter.replaceChatTags` | JDBC, reescreve `chat_tags` |
| `ChangeLogWriter.chatCreated` / `chatUpdated` | `sync_change_log`, `REQUIRED` — mesma transação |

A gravação de tags e a linha do change log estão na **mesma** transação da escrita do
chat: ou o lote inteiro passa, ou nada passa.

### 6. Resposta final

```json
{ "total": 6, "created": 6, "updated": 0, "skipped": 0,
  "failed": 0, "failures": [] }
```

`failures` é uma lista de **strings** formatadas (`"<título>: <Exceção> — <mensagem>"`),
não de objetos: o cliente mostra o texto direto, sem precisar interpretar estrutura.

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| Formato não reconhecido por nenhum adapter | **422** |
| `schemaVersion > 1` | **422** |
| Mais de 10 000 conversas | **422**, antes de escrever qualquer uma |
| Uma conversa sem mensagens | item em `failures`; o resto importa |
| `DELETE`+`INSERT` das mensagens viola `uq_messages_chat_sequence` | item em `failures` |
| Importações concorrentes do mesmo arquivo | A segunda toma `DataIntegrityViolationException` no índice único parcial |
| Arquivo > 10 MB | **413** |

## Decisões técnicas importantes

- **Uma transação por arquivo.** Uma importação de 10 000 chats é tudo ou nada. Isso
  é caro em lock, mas é o que permite `SKIPPED` ser um no-op honesto.
- **`try/catch` por item dentro da transação.** Uma conversa ruim não aborta as
  outras — e é por isso que a transação do lote sobrevive.
- **`content_hash` calculado duas vezes:** por `Chat.create` e por
  `ImportService.contentHashOf`. `Chat.normalizeTitle` é `public` justamente para os
  dois produzirem o mesmo hash sem depender de ids.
- **Ordenação determinística `(createdAt, externalId)`** antes de atribuir `sequence`,
  porque o `id` da mensagem é um UUID novo a cada importação e não pode participar do
  hash.

## Trechos de código relevantes

```java
// ImportService.locateExisting — a ordem exata da idempotência
if (normalized.externalId() != null && !normalized.externalId().isBlank()) {
    var byExternal = chatRepository.findBySourceAndExternalId(source, normalized.externalId().strip());
    if (byExternal.isPresent()) return byExternal;
}
return chatRepository.findBySourceAndContentHash(source, contentHashOf(normalized));
```

## Regras importantes

- Importar o mesmo arquivo duas vezes **não duplica nada** (`created: 0, skipped: N`),
  provado contra Postgres real em `ImportIdempotencyIT`.
- O conteúdo Markdown é guardado **cru**. O backend nunca renderiza.

---

# Feature: 03 — Adapters de formato

## Resumo

Três adapters traduzem bytes em `NormalizedChat`. O backend não conhece o formato de
nenhum exportador: cada um se anuncia por assinatura (`supports`) e o registry escolhe.
Detalhe de formato em [importers.md](importers.md).

## Fluxo principal

### 1. Ponto de entrada

`GET /api/chats/import/formats` lista os adapters ativos;
`ImportService` chama `registry.resolve(format)`.

### 2. Validação de entrada

- `format` explícito: o enum é convertido; valor inválido →
  `MethodArgumentTypeMismatchException` → **422** (não 400).
- Sem `format`: `registry` testa `supports()` em ordem de precedência.
- Nenhum adapter aceita → **422**.

### 3. Orquestração da aplicação

`ChatImporterRegistry` descobre as implementações por injeção do Spring — **não há
switch central**. Adicionar um formato é registrar um `@Component`.

### 4. Regras de negócio

| Formato | Assinatura | Particularidade |
|---|---|---|
| `JSON` | começa com `{` e tem array `chats`/`conversations`/`conversations_list` | Cobre também o `mapping` do ChatGPT, mas perde precedência para o adapter específico |
| `CHATGPT_EXPORT` | objeto com `mapping` de chaves UUID e `current_node` | Ordena o grafo por `create_time`; **mantém todas as ramificações**; ignora o nó `current_node` |
| `MARKDOWN` | texto com `#` ou travessão `---` de front matter | Front matter opcional; H1 abre conversa, H2 abre mensagem |

Duas decisões que valem registro:

- **Ramificações do ChatGPT são preservadas.** Apagar mensagens editadas
  (`metadata.is_visually_hidden_from_conversation`) perderia histórico; quem decide o
  que exibir é a interface.
- **`SYSTEM` e `TOOL` entram no banco.** `Role` existe para mostrar a conversa como
  ela foi, não como o backend imagina que foi.

### 5. Persistência / Integrações

Nenhuma. Os adapters rodam **antes** de qualquer transação e devolvem
`List<NormalizedChat>`.

### 6. Resposta final

`{"formats": ["JSON", "MARKDOWN", "CHATGPT_EXPORT"]}`

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| Conteúdo que nenhum adapter reconhece | **422** |
| Papel irreconhecível | Importado com `role = UNKNOWN`; **não** é erro |
| Data em formato desconhecido | `createdAt`/`updatedAt` ficam `null`; **não** é erro |
| Front matter Markdown incompleto | Usa só o que estiver presente; o resto vem do corpo |
| Markdown sem nenhum `#` | Vira **um chat só**, com o corpo inteiro; suportado |
| Markdown sem nenhum `## papel` | **422** (nenhum bloco vira mensagem) |

## Decisões técnicas importantes

- **Normalização de tempo centralizada** em `DateTimes` (domínio do `importer`),
  reutilizada pelo `JsonChatImporter`: ISO-8601, epoch em segundos, `YYYY-MM-DD`, e
  epoch **float** do ChatGPT.
- **Detecção por assinatura, não por extensão do nome.** O ChatGPT salva como
  `conversations.json`, que é JSON — e o adapter específico precisa ganhar.
- **Ordem importa e é testada.** `ChatApiIT.formatsEndpointListsAdapters` não depende
  mais da ordem de iteração.

## Regras importantes

- O pacote `importer` pode depender de `chat.domain.Role`/`Message`;
  **nenhum** código de domínio conhece `importer`. Verificado por `ArchitectureTest`.
- Testes puros, sem banco: 14 para Markdown, 10 para ChatGPT.

---

# Feature: 04 — Listagem e leitura de conversas

## Resumo

`GET /api/chats`, `GET /api/chats/{id}` e `GET /api/chats/{id}/messages`. A listagem
nunca carrega mensagens — é o que permite 50 conversas em uma consulta.

## Fluxo principal

### 1. Ponto de entrada

`ChatController` (`chat/infrastructure/ChatController.java`).

### 2. Validação de entrada

`page` `@Min(0)` (padrão 0) e `size` `@Min(1) @Max(200)` (padrão 20); `favorite`
booleano opcional. Fora da faixa → **400**.

### 3. Orquestração da aplicação

`ChatService` — classe inteira `@Transactional(readOnly = true)`. `list` escolhe entre
`findActive` e `findFavorites` conforme o filtro.

### 4. Regras de negócio

- **Soft delete sempre aplicado:** `deleted = false` em toda listagem, contagem e
  leitura por id. Tombstone não aparece em lugar nenhum da API.
- **Ordenação:** `updated_at DESC NULLS LAST, id DESC`. O `id` no desempate é o que
  torna a paginação estável quando duas conversas têm a mesma data.
- **Paginação por `page`/`size`** sobre `OFFSET`, coerente com o token do sync: o
  cliente dá um pull e recomeça do topo a cada abertura.

### 5. Persistência / Integrações

`ChatRepositoryAdapter` → `ChatJpaRepository.findActive/findFavorites/findActiveById`
com `Pageable`. Sumário usa `message_count` desnormalizado; **não** há `COUNT(*)`
por conversa.

### 6. Resposta final

```json
{ "items": [ { "id": "...", "title": "...", "favorite": false,
               "messageCount": 14, "updatedAt": "2026-09-27T10:00:00Z",
               "tags": ["leitura"] } ],
  "page": 0, "size": 20, "total": 8, "last": true, "totalPages": 1 }
```

O envelope é `PageResponse<T>` com `items`, `page`, `size`, `total`, `last` e um
`totalPages` derivado. **Não** é `content`/`totalElements`, que é o formato de outro
projeto.

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| `id` inexistente ou deletado | **404** |
| `size > 200` | **400** |
| `page` além do fim | **200** com `items: []` e `last: true` |

## Decisões técnicas importantes

- **Sem `@OneToMany`.** A listagem de 50 conversas com `@OneToMany` seria dezenas de
  milhares de linhas por request.
- **`message_count` desnormalizado** em vez de `COUNT(*)` por chat.
- **`open-in-view: false`** impede o lazy loading acidental e mantém a transação
  fechada no service.

## Regras importantes

- Nunca retornar conversa deletada, nem por `GET /{id}`.
- **`GET /{id}/messages` não garante ordenação.** O método derivado é
  `findByChatId(String, Pageable)`, sem `OrderBy`, e o `PageRequest` é construído sem
  `Sort`. A unicidade de `(chat_id, sequence)` torna a ordem estável *na prática*, mas
  nada no SQL a garante. Isso é uma dívida real: se o plano de execução mudar, a
  paginação por `OFFSET` pode repetir ou pular mensagem.
- O teto aqui é `@Max(500)`, maior que o da listagem (`@Max(200)`), porque o leitor do
  Kindle baixa janelas grandes de uma conversa só.

---

# Feature: 05 — Favoritos

## Resumo

`PUT /api/chats/{id}/favorite` marca ou desmarca uma conversa. É uma flag bidirecional
(LWW), e a operação precisa ser idempotente porque o Kindle só consegue favoritar
offline.

## Fluxo principal

### 1. Ponto de entrada

`PUT /api/chats/{id}/favorite` com `{"favorite": true|false}`.

### 2. Validação de entrada

`id` obrigatório. O corpo usa `boolean` **primitivo**, sem validação: campo omitido vira
`false` e a chamada retorna **200**, sem erro.

### 3. Orquestração da aplicação

`ChatService.setFavorite` — `@Transactional` (write). Carrega, aplica, salva, e
registra o change log.

### 4. Regras de negócio

- **Bidirecional:** `true` e `false` são ambos válidos; não existe "remover favorito".
- **LWW explícito** (ADR-005). Dois dispositivos alternando produzem o último estado,
  sem erro e sem aviso.
- **Só grava quando o valor muda.** `ChatService.setFavorite` chama
  `chat.setFavorite(...)`, que devolve `false` se a flag já estava no valor pedido; aí
  nem `save` nem change log acontecem. Repetir o mesmo favorito é no-op.

### 5. Persistência / Integrações

`chats.favorite` + `idx_chats_favorite` (índice parcial: só os favoritados entre os
vivos). `ChangeLogWriter.chatUpdated` na mesma transação.

### 6. Resposta final

O `ChatSummaryResponse` completo, já com a flag nova — o cliente não precisa de um
GET extra.

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| Chat inexistente ou deletado | **404** |
| `favorite` ausente | **200** — primitivo vira `false` e desmarca |
| Dois dispositivos escrevendo ao mesmo tempo | Ambos **200**; o último grava |

## Decisões técnicas importantes

- **Sem `@Version`.** Detectar conflito aqui produziria erro para uma operação em que
  o usuário claramente quer o que pediu. Ver
  [ADR-DM-004](data-model.md#adr-dm-004--content_version-é-contador-não-version-de-lock).
- **Índice parcial** porque favoritos entre chats deletados nunca são lidos.

## Regras importantes

- O caminho offline no SQLite (`set_favorite_offline`) aplica **e** enfileira; o
  caminho online (`set_favorite`) só aplica, senão cada mudança remota voltaria para a
  fila.

---

# Feature: 06 — Tags

## Resuma

Tags são etiquetas livres, deduplicadas sem diferenciar maiúsculas, e compartilhadas
entre conversas. `GET /api/tags`, `POST /api/tags`, e o filtro `?tag=` na listagem de
conversas.

## Fluxo principal

### 1. Ponto de entrada

`GET /api/tags` e `POST /api/tags` em `TagController`. A troca de tags de uma conversa
é `PUT /api/chats/{id}/tags`, em `ChatService.setTags`.

### 2. Validação de entrada

`name` com `@NotBlank`. **Não há `@Size`**: um nome com mais de 100 caracteres é
**truncado silenciosamente** por `TagNames.require` e a requisição retorna **201**.

### 3. Orquestração da aplicação

`TagService` (`@Transactional(readOnly = true)` na classe, write no `create`).
`ChatService.setTags` orquestra a troca: resolve/cria as tags e regrava a associação.

### 4. Regras de negócio

- **`normalized_name` = `strip` → corte em 100 → `strip` de novo →
  `toLowerCase(Locale.ROOT)`.** O segundo `strip` existe porque cortar no meio pode
  deixar espaço na borda: um nome cujo 100º caractere é espaço normaliza com 99
  caracteres, não 100. `"Java"`, `"java"` e `" JAVA "` são a mesma tag; o primeiro
  texto digitado é o que aparece.
- **Normalização na aplicação** (`TagNames`), não em `citext`: o backend guarda o
  primeiro texto e a chave derivada, o que preserva a capitalização original.
- **Associação reescrita inteira** a cada save — são poucas tags por conversa.
- **A ordem é alfabética case-insensitive** na resposta (`ORDER BY lower(name), name`).
- **Tags do chat são sobrescritas, não acumuladas.**

### 5. Persistência / Integrações

`TagRepositoryAdapter` usa **`NamedParameterJdbcTemplate`**, não JPA, porque precisa de
`ON CONFLICT DO NOTHING`:

```sql
INSERT INTO chat_tags (chat_id, tag_id) VALUES (:chatId, :tagId) ON CONFLICT DO NOTHING
```

Na criação, `SELECT` → `INSERT` → em `DuplicateKeyException`, `SELECT` de novo e
seguir em frente. É a corrida resolvida na aplicação, não num lock.

### 6. Resposta final

`GET /api/tags` devolve **só os nomes**, em string:

```json
{ "tags": ["Java", "leitura", "markdown"] }
```

`POST /api/tags` devolve `{"id": "...", "name": "Java"}` — o `normalizedName` não é
exposto em nenhum dos dois.

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| `name` em branco | **400** |
| `name` com mais de 100 caracteres | **201** — truncado em 100, sem aviso |
| Criar tag que já existe | **201** com a existente (`@ResponseStatus` fixo; não é erro) |
| `?tag=` com tag inexistente | **200** com lista vazia |
| Corrida na criação | `DuplicateKeyException` capturada; segue |

## Decisões técnicas importantes

- **JDBC em um projeto que usa JPA no resto.** `ON CONFLICT DO NOTHING` e upsert por
   `normalized_name` não têm tradução limpa em JPQL, e forçar JPQL seria mais código do
   que o SQL.
 - **Filtro por tag pagina em memória** (`ChatRepositoryAdapter.findByTag`): carrega
   todos os ids da tag, fatia com `subList` e só então carrega a janela. É a única
   dívida de performance conhecida do modelo.
- **Tags de chat deletado ficam no banco.** Só o tombstone é soft; a junção permanece.

## Regras importantes

- Criar tag repetida é sucesso, não erro.
- `name` preserva a capitalização; a comparação é sempre na forma normalizada.

---

# Feature: 07 — Bookmarks de mensagens

## Resumo

Um marcador aponta para uma mensagem específica de uma conversa, para retomar a leitura
onde parou. `GET/POST /api/bookmarks` e `DELETE /api/bookmarks/{id}`.

## Fluxo principal

### 1. Ponto de entrada

`BookmarkController`:

| Rota | Efeito |
|---|---|
| `GET /api/bookmarks` | Lista geral, ou `?chatId=` |
| `POST /api/bookmarks` | `{ "chatId", "messageId", "note"? }` |
| `DELETE /api/bookmarks/{id}` | Remove |

### 2. Validação de entrada

`chatId` e `messageId` `@NotBlank`; `note` com teto de 1000 caracteres.
Mensagem inexistente → **404**.

### 3. Orquestração da aplicação

`BookmarkService` — `@Transactional(readOnly = true)` na classe, `@Transactional` em
`create` e `delete`. `create` faz checagem de existência e depois grava.

### 4. Regras de negócio

- **Um bookmark por `(chatId, messageId)`.** Segundo marcador para a mesma mensagem →
  **409**, por `uq_bookmarks_chat_message`.
- **O `id` do POST é escolhido pelo backend** na rota normal; no ACK, é o do cliente.
- **Ordenação:** `created_at DESC, id DESC`.
- **Bookmarks são apagados na mão** quando o chat é removido, não por cascade.

### 5. Persistência / Integrações

`bookmarks` + `idx_bookmarks_chat` + `idx_bookmarks_created`.
`ChangeLogWriter.bookmarkCreated` / `bookmarkDeleted` na mesma transação.

### 6. Resposta final

```json
{ "id": "...", "chatId": "...", "messageId": "...", "note": null,
  "createdAt": "2026-09-27T10:00:00Z" }
```

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| Bookmark duplicado | **409** |
| Chat ou mensagem inexistente | **404** |
| Token sem `chatId`/`messageId` | **400** |
| Remover bookmark inexistente | **404** |

## Decisões técnicas importantes

- **O `id` é a chave de idempotência.** No Kindle, o plugin usa o **`id` da mensagem**
  como `id` do bookmark. Isso funciona porque `bookmarks.id` é `VARCHAR(36)` e o
  plugin precisa conseguir reler o que gravou — e é o que uma constraint de unicidade
  resolve de graça.
- **Apagar antes de fazer tombstone**, na mesma transação. Sobrar bookmark apontando
  para mensagem inalcançável só criaria órfãos na listagem do Kindle.

## Regras importantes

- Um marcador por mensagem, sempre.
- `note` é opcional e nunca sai do servidor por padrão.

---

# Feature: 08 — Busca full-text

## Resumo

`GET /api/chats/search?q=...` devolve, por mensagem que casa, a conversa, o papel e um
trecho. Combina busca full-text no Postgres com igualdade exata em tags.

## Fluxo principal

### 1. Ponto de entrada

`GET /api/chats/search` em `SearchController`. Parâmetros: `q` (obrigatório),
`page` `@Min(0)` padrão 0, `size` `@Min(1) @Max(200)` padrão 20.

### 2. Validação de entrada

`SearchService.requireQuery` rejeita `q` vazio ou só com espaços com
`IllegalArgumentException` → **400**. `size > 200` → **400**.

### 3. Orquestração da aplicação

`SearchService` (`@Transactional(readOnly = true)`) chama
`SearchQueryRepository.search` e `.count`, que executam o mesmo SQL em `NamedParameterJdbcTemplate`.

### 4. Regras de negócio

A query são **dois conjuntos unidos por `UNION ALL`**, e a ordem é `bucket ASC,
rank DESC, created_at DESC NULLS LAST, message_id ASC`:

| Bucket | O que casa | `rank` | Snippet |
|---|---|---|---|
| 0 | `messages.content_tsv @@ query` | `ts_rank(content_tsv, query)` | `ts_headline` sobre o conteúdo |
| 1 | `title_tsv @@ query` **ou** tag com igualdade exata | literal `0` | `ts_headline` sobre o título |

- **Configuração `'simple'`** (sem stemming) em `plainto_tsquery`, `ts_headline` e nas
  colunas geradas. Funciona em português e inglês sem extensão.
- **Desduplicação:** o bucket 1 tem `AND NOT EXISTS (conteúdo que casa)`, então uma
  conversa nunca aparece duas vezes.
- **`bucket ASC` vem antes do `rank`:** todo match de conteúdo vem antes de todo match
  de título/tag, qualquer que seja o `ts_rank`.
- **Representante do bucket 1:** um `JOIN LATERAL` escolhe a **primeira mensagem por
  `sequence`** como `messageId` da resposta.
- **Tag é igualdade exata**, `t.normalized_name = lower(btrim(:q))` — não é full-text.
  "Java" acha `java`, mas não acha `Java Spring`.
- **`<mark>` é removido.** `ts_headline` gera os marcadores e `stripHighlights` os
  tira antes de responder, porque o Kindle renderiza texto simples.

### 5. Persistência / Integrações

Colunas `chats.title_tsv` e `messages.content_tsv`, ambas
`GENERATED ALWAYS AS (...) STORED`, com índice GIN. Não há trigger: a aplicação não
pode esquecer de mantê-las.

### 6. Resposta final

```json
{ "query": "markdown", "results": [
    { "chatId": "...", "title": "...", "messageId": "...", "role": "ASSISTANT",
      "snippet": "duas regras curtas para e-ink...", "createdAt": "..." } ],
  "page": 0, "size": 20, "total": 2, "last": true }
```

O envelope é `SearchResponse`, com `query`, `results`, `page`, `size`, `total` e
`last` — **não** o `PageResponse` genérico das outras rotas.

`rank` **não** é exposto: ele ordena dentro da query e morre ali.

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| `q` vazio | **400** |
| Nenhuma conversa casa | **200** com `content: []` e `totalElements: 0` |
| Termo com sintaxe inválida | `plainto_tsquery` nunca falha — sempre devolve uma query |
| `size > 200` | **400** |

## Decisões técnicas importantes

- **Colunas geradas, não trigger nem coluna preenchida pela aplicação.** Um caminho de
  escrita que esquecesse a coluna passaria despercebido; `GENERATED ... STORED`
  elimina a classe inteira de bug.
- **SQL à mão, não JPQL.** `tsvector`, `LATERAL` e `UNION ALL` com `NOT EXISTS` são
  ilegíveis em JPQL e não traduziriam.
- **`'simple'` em vez de `'portuguese'`.** Stemming em português parte palavras que
  deviam casar.
- **Contagem feita no SQL**, com os mesmos `WHERE` — não `COUNT` do resultado paginado.

## Regras importantes

- A busca nunca expõe ranking; é navegação, não score.
- Chat deletado nunca aparece em nenhum dos dois buckets.

---

# Feature: 09 — Delta sync

## Resumo

`GET /api/sync?since=<token>&limit=<n>` devolve o que mudou depois do token. É a
operação mais quente do sistema e a base do modelo offline-first do Kindle.

## Fluxo principal

### 1. Ponto de entrada

`GET /api/sync` em `SyncController.sync`. `since` começa em 0, o que força um cliente
novo ao caminho de snapshot.

### 2. Validação de entrada

`since` com `@Min(0)` (padrão 0) e `limit` com `@Min(1) @Max(1000)`. Exceder 1000 é
**400**, recusado pelo bean validation — não há ajuste silencioso nesse caminho.
Dentro do teto, `clampLimit` ainda normaliza `limit <= 0` para `default-limit` (200) e
reaplica o teto de `max-limit` (1000), porque o mesmo método é alcançado por código que
não passa pela validação do controller.

### 3. Orquestração da aplicação

`SyncService.pull(since, limit)` — **sem `@Transactional`**, de propósito:

```
clampLimit(limit)
prune()                                        // dois DELETEs, transação separada cada
if requiresFullResync(since, oldest): {changes: [], fullResyncRequired: true}
changes = changeLog.findSince(since, limit + 1)   // o +1 decide hasMore, sem contar 2x
version = changes vazio ? max(since, 0) : changes[last].version
```

### 4. Regras de negócio

- **`fullResyncRequired` é a regra de ouro.** `requiresFullResync(since, oldest)` tem
  três casos: `since <= 0` sempre pede snapshot (um cliente novo não pode receber
  `changes: []` e concluir que a biblioteca está vazia); `oldest <= 0` nunca pede, porque
  log vazio não prova buraco; e, com token, só pede quando `since < oldest - 1`.
  Esse `- 1` é deliberado: linhas podem ter sido podadas **depois** de aplicadas pelo
  cliente, então a tolerância é um pouco maior que a estrita. A lacuna residual — um
  cliente mais offline que a retenção — é tratada no cliente, que refaz o snapshot
  quando o intervalo passa de `retentionDays`.
- **O token é `sync_change_log.version`**, não `updated_at`: colide em lote, não
  sobrevive a ajuste de relógio e não registra remoção.
- **O payload é montado na leitura**, a partir do estado atual. O log guarda só
  metadados, então o cliente nunca recebe um snapshot congelado no passado.
- **`MESSAGE_*` não são emitidos.** Uma mudança de conteúdo chega como
  `CHAT_UPDATED` do chat inteiro. Mudança de mensagem é rara comparada ao custo de
  manter o cliente coerente.

### 5. Persistência / Integrações

`ChangeLogAdapter.findSince` →
`findByVersionGreaterThanOrderByVersionAsc(since, Pageable)` + `idx_sync_change_log_version`.
Sem FK para as entidades descritas, de propósito.

### 6. Resposta final

```json
{ "syncToken": 17, "hasMore": false, "fullResyncRequired": false,
  "changes": [ { "version": 15, "type": "CHAT_UPDATED",
                 "chatId": "...", "contentVersion": 2 } ] }
```

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| Token antigo demais | **200** com `fullResyncRequired: true` e `changes: []` |
| `limit > 1000` | **400** (validação do controller) |
| `limit <= 0` | **200** com `limit = 200` (`default-limit`) |
| Token do futuro | **200** com `changes: []` e o token do cliente preservado |
| Nenhuma mudança | **200** com `hasMore: false` e `syncToken` = o `since` recebido, para o cliente não perder a posição |

## Decisões técnicas importantes

- **Pull-only.** Nada é empurrado ao servidor: sem WebSocket, sem polling. É o que
  cumpre "otimizado para e-ink".
- **A poda está no caminho quente, dentro do read.** `pull` é a única janela em que o
  servidor escreve durante um sync, então é a chance barata de manter a tabela
  pequena. O custo é que um backend que ninguém sincroniza nunca poda por data — daí
  o teto de linhas como segunda rede.
- **`prune()` não é transacional com o pull**, e cada um dos dois DELETEs é a sua
  própria transação. Race entre dois pulls apenas duplica trabalho.

## Regras importantes

- Nunca **regressar** o token do cliente. `currentVersion()` é
  `max(MAX(version), pg_sequence_last_value(...))` justamente para isso.
- Um `BACKEND` recém-criado nunca devolve `syncToken: 0`; `reserveVersion()` chama
  `nextval`.

---

# Feature: 10 — Snapshot

## Resumo

`GET /api/sync/snapshot?page=<n>&size=<n>` devolve o estado completo quando o token do
cliente ficou antigo demais, ou quando o cliente é novo. O detalhe que o torna correto
sob escrita concorrente é reservar a versão **antes** de ler os dados. É o caminho de
`fullResyncRequired` do delta, e a forma como o Kindle sai de uma biblioteca vazia para
uma biblioteca completa.

## Fluxo principal

### 1. Ponto de entrada

`GET /api/sync/snapshot` em `SyncController.snapshot`. Parâmetros: `page` `@Min(0)`
(padrão 0) e `size` `@Min(1) @Max(500)`, opcional.

### 2. Validação de entrada

`page` `@Min(0)` e `size` `@Min(1) @Max(500)`. `size <= 0` é recusado pelo
controller com **400** e nunca chega ao service — o `safeSize` do `SyncService` é
defensivo, para quem chamar o service por fora.

### 3. Orquestração da aplicação

`SyncService.snapshot(page, size)`:

```
safeSize = size <= 0 ? snapshotDefaultPageSize : size
syncVersion = changeLog.reserveVersion()      // ANTES de ler os dados
total = chatService.count(null, null)
summaries = chatService.list(page, safeSize, null, null)
chats = summaries.map(s -> s.messageCount > maxMessagesPerChange ? s : chatService.get(s.id))
last = (page + 1) * safeSize >= total
bookmarks = bookmarkService.list(null, 0, maxBookmarksInSnapshot)
tags = tagService.all()
```

### 4. Regras de negócio

- **A versão é reservada ANTES da leitura.** Essa é a decisão que faz o snapshot
  funcionar com escrita concorrente: qualquer mudança que caia durante a leitura fica
  **acima** do token devolvido e volta no delta seguinte, em vez de sumir no meio do
  snapshot. Como remoções são tombstones, o cliente consegue reconstruir o estado.
- **Paginação por `page`/`size`, não por cursor.** `OFFSET` é aceitável aqui porque o
  snapshot é reconstrução de estado, não leitura incremental: um item deslocado é
  corrigido na página seguinte.
- **`last` fecha o laço**, calculado por comparação aritmética com `total`, e não por
  "a página veio menor que `size`".
- **Mensagens truncadas por tamanho.** Conversa com `messageCount` acima de
  `max-messages-per-change` (200) vai só com metadados; o cliente pagina sob demanda
  depois.
- **Bookmarks limitados** por `max-bookmarks-in-snapshot` (1000), com
  `bookmarksOmitted` sinalizando que houve corte.
- **Filtra `deleted = false`**, porque passa por `ChatService.list`.

### 5. Persistência / Integrações

Reaproveita `ChatService`, `BookmarkService` e `TagService` — não existe query de
snapshot própria. `reserveVersion()` usa `SELECT nextval('sync_change_log_version_seq')`,
um dos dois únicos SQL nativos do backend.

### 6. Resposta final

```json
{ "syncToken": 17, "page": 0, "size": 50, "total": 8, "last": true,
  "tags": [ { "id": "...", "name": "leitura" } ],
  "chats": [ { "id": "...", "title": "...", "messageCount": 14,
               "messagesOmitted": false,
               "messages": [ { "id": "...", "sequence": 1, "role": "USER",
                               "content": "..." } ] } ],
  "bookmarks": [], "bookmarksOmitted": false }
```

`messagesOmitted` é propriedade da **conversa**, não da mensagem: o cliente sabe, de
uma vez, se precisa paginar aquela conversa.

## Fluxos alternativos e erros

| Cenário | Comportamento |
|---|---|
| Banco vazio | **200**, `chats: []`, `last: true` |
| `page` além do fim | **200**, `chats: []`, `last: true` |
| `size > 500` | **400** (validação do controller) |
| `size <= 0` | **400** (validação do controller) |
| Mais de 1000 bookmarks | lista os 1000 primeiros e `bookmarksOmitted: true` |
| `reserveVersion` em log vazio | devolve a sequência corrente, nunca `0` |

## Decisões técnicas importantes

- **Reservar a versão antes de ler** é o que separa um snapshot consistente de um
  snapshot furado. Um backend novo, sem escrita alguma, precisa que isso funcione:
  `reserveVersion()` chama `nextval` justamente para nunca devolver `syncToken: 0`.
- **Reaproveitar `ChatService`** garante que snapshot e listagem nunca divirjam sobre o
  que é uma conversa deletada. O custo é `N+1` para as conversas que vêm com
  mensagens — aceitável, porque a grande maioria fica só com metadados.
- **`messagesOmitted` é explícito** em vez de truncar em silêncio: o cliente sabe que
  precisa paginar.

## Regras importantes

- O snapshot nunca inclui conversa deletada.
- O `syncToken` do snapshot é reservado antes da leitura, e é o token que o cliente
  passa a usar nos deltas seguintes.

---

# Feature: 11 — ACK e escritas offline

## Resumo

`POST /api/sync/ack` confirma o token do cliente e sobe as escritas que o Kindle fez
offline — favoritos e bookmarks. É o único caminho de escrita que nasce do cliente.

## Fluxo principal

### 1. Ponto de entrada

`POST /api/sync/ack` com `{"ackVersion": n, "clientId": "...", "localChanges": [...]}`.

### 2. Validação de entrada

`AckRequest` **não tem nenhuma anotação de restrição** — `ackVersion` é `long` primitivo
(vira 0 se omitido) e o `@Schema(maximum = "200")` do `localChanges` é documentação,
não validação. Um `ackVersion` negativo passa; uma lista com 5.000 itens passa.

### 3. Orquestração da aplicação

`SyncService.ack(...)` — **sem `@Transactional`**. Cada item é processado
independentemente, justamente para que um item ruim não derrube os outros.

```
para cada LocalChange:
    resolve o aggregate correspondente
    valida (favorito: chat existe?  bookmark: chat+mensagem existem?)
    aplica por caminho IDEMPOTENTE (createFromClient / deleteFromClient)
    registra no change log
    conta em aceitos ou recusados, com o motivo
```

### 4. Regras de negócio

- **O ACK devolve o token corrente do servidor**, em `newSyncVersion`, que é
  `changeLog.currentVersion()`. Não é o `ackVersion` recebido. Ainda assim o token só
  deve ser aplicado no `pull` seguinte: se a resposta se perder, o cliente reenvia o
  mesmo ACK, e a idempotência segura.
- **Item recusado fica na fila do Kindle**, com o motivo, para a tela avisar.
- **Idempotência no ACK:** `createFromClient` não sobrescreve — devolve o bookmark
  existente por `id`, ou o que já está naquele `(chatId, messageId)`, **sem tocar nos
  dados gravados**. `deleteFromClient` apaga se existir. Reprocessar o mesmo ACK não
  duplica nem perde `note`.
- **`clientId` é informativo**, usado só para observabilidade: não cria sessão nem
  estado no servidor.
- **`ContentWrite` é sempre recusado** com 409 e um motivo — o conteúdo da conversa é
  controlado pelo backend (ADR-005). O cliente que tentar escrever conteúdo recebe a
  recusa explícita, não um sucesso silencioso.

### 5. Persistência / Integrações

Cada item roda na transação do próprio service (`BookmarkService.createFromClient`,
`ChatService.setFavorite`) com o `ChangeLogWriter` junto. Como são unidades separadas,
um `409` ou `404` em um item não desfaz os anteriores.

### 6. Resposta final

```json
{ "ackVersion": 17, "newSyncVersion": 19,
  "accepted": 2, "rejected": 0,
  "rejections": [ { "index": 0, "reason": "NOT_FOUND",
                    "message": "mensagem nao encontrada" } ]
}
```

O detalhe que importa para o Kindle: **`rejections` traz o `index` dentro de
`localChanges`**, não o `type` nem o `refId`. É por isso que o cliente sabe exatamente
qual item da fila tirar e qual deixar — e é por isso que a ordem das escritas é
preservada entre a fila e a resposta.

## Fluxos alternativos e erros

| Cenário | Resposta |
|---|---|
| Chat do favorito não existe mais | Item **recusado**, com motivo; os outros sobem |
| Mensagem do bookmark não existe | Item **recusado**, com motivo |
| `localChanges` vazio ou ausente | **200** com `accepted: 0`, `rejections: []` |
| `ackVersion` ausente | **200** — primitivo, vira 0 |
| `ackVersion` negativo | **200** — aceito; `ackVersion` é informativo |
| `localChanges` acima de 200 itens | **200** — o teto é só `@Schema`, sem validação |
| `ContentWrite` (escrita de conteúdo pelo cliente) | Item **recusado** com `CONFLICT` e o motivo |
| Mesmo ACK reenviado | **200**; sem duplicar nada |

## Decisões técnicas importantes

- **Um item por transação, sem transação externa.** Deliberado: parcial é melhor que
  nada quando o usuário tem 3 favoritos e 1 mensagem apagada.
- **`reason` é uma categoria fechada** (`NOT_FOUND`, `CONFLICT`, …) e `message` é o
  texto; o Kindle usa a categoria para decidir e o texto para mostrar. `rejections`
  carrega o `index`, o que torna a fila do cliente reconciliável sem state extra.
- **ACK não avança token** — a avançar no ACK, uma resposta perdida custaria a escrita.

## Regras importantes

- ACK nunca falha inteiro por causa de um item.
- O caminho idempotente é obrigatório: o Kindle reenvia a fila até esvaziar.

---

# Feature: 12 — Change log e retenção

## Resumo

Toda mudança de conteúdo, favorito, tag ou bookmark grava uma linha em
`sync_change_log`. A coluna `version` é o token do cliente. A tabela é podada por idade
e por volume, oportunistamente, em cada `GET /api/sync`.

## Fluxo principal

### 1. Ponto de entrada

Não é um endpoint: `ChangeLogWriter` é chamado pelos módulos `importer`, `chat`,
`bookmark` e `tag` depois de uma escrita bem-sucedida.

### 2. Validação de entrada

`change_type` e `entity_type` têm CHECK no banco com os valores aceitos.

### 3. Orquestração da aplicação

Os seis métodos públicos — `chatCreated`, `chatUpdated`, `chatDeleted`,
`bookmarkCreated`, `bookmarkDeleted`, `tagCreated` — são
`@Transactional(propagation = REQUIRED)` explícito, e o Javadoc explica: a gravação roda
**na transação de quem chamou**, para que a mudança e o dado que a motivou façam commit
— ou não façam — juntos.

```
mudança no domínio (transação do service)
    └─ ChangeLogWriter.<evento>  (REQUIRED → mesma transação)
        └─ ChangeLogAdapter.append (REQUIRED)
            └─ INSERT em sync_change_log; version vem do BIGSERIAL
```

### 4. Regras de negócio

- **`version` nunca é escrito pela aplicação.** É `BIGSERIAL` com
  `GenerationType.IDENTITY`; o banco é a fonte da sequência.
- **Só metadados.** O payload é montado na leitura.
- **Duas camadas de retenção:** 30 dias (`SYNC_RETENTION_DAYS`) e teto de 1.000.000 de
  linhas (`SYNC_MAX_LOG_ROWS`), aplicada por `keepLatest` com
  `cutoff = maxVersion - maxRows`.
- **Poda só em `pull`.** Sem `@Scheduled` no backend.
- **`created_at` é `Instant.now()` em Java**; o `DEFAULT now()` da coluna não é usado
  nesse caminho.

### 5. Persistência / Integrações

`sync_change_log` + `idx_sync_change_log_version` (consulta quente) +
`idx_sync_change_log_created` (poda). Sem FK para `chats` — ver
[ADR-DM-002](data-model.md#adr-dm-002--change-log-sem-fk-para-o-que-ele-descreve).

### 6. Resposta final

Não há resposta: `ChangeLogWriter` é interno. O efeito aparece na resposta do
`GET /api/sync` seguinte.

## Fluxos alternativos e erros

| Cenário | Comportamento |
|---|---|
| `DuplicateKeyException` ao podar por linha | `maxRows <= 0` → `0`; `MAX(version) IS NULL` → `0`; `cutoff <= 0` → `0` (nenhuma linha removida) |
| Dois pulls simultâneos podam | Ambos fazem o trabalho; a duplicação é inofensiva |
| Log nunca lido | Cresce até o teto de linhas, e para |

## Decisões técnicas importantes

- **`REQUIRED` explícito e redundante.** É documentação executável: muda-se para
  `REQUIRES_NEW` e a garantia some em silêncio.
- **Fila de mudança só para o que tem efeito no cliente.** `MESSAGE_*` não é emitido;
  o custo de manter o cliente coerente é maior que o de reemitir o chat.
- **Teto de linhas como segunda rede.** Só o backend que alguém sincroniza podaria por
  data; o teto limita o dano de todo o resto.

## Regras importantes

- Nunca Writing de change log fora da transação do dado.
- O token do cliente nunca regride.

---

# Feature: 13 — Limpeza local do Kindle

## Resumo

O menu do plugin (`main.lua`) oferece limpar o banco local do dispositivo — recomeçar
do zero sem tocar no servidor. Operação local e sem rede, que existe porque recomeçar é
um direito do usuário, não um bug.

## Fluxo principal

### 1. Ponto de entrada

Menu principal do plugin: `ChatReader:wipe()` em `kindle/main.lua:240`.

### 2. Validação de entrada

Confirmação na UI antes de apagar. Sem ela, um toque acidental em e-ink destruiria a
biblioteca offline.

### 3. Orquestração da aplicação

```lua
function ChatReader:wipe()
    if self.reader then self.reader:dismiss() end
    self:get_store():clear()
    self.positions:clear()
    self.store:close()
    self.store = nil
end
```

### 4. Regras de negócio

- **Apaga só o dispositivo.** Nada no backend é tocado; o próximo sync recria tudo a
  partir do snapshot.
- **`clear()` não toca em `sync_state` nem em `outbox`.** O token de sync é
  deliberadamente **preservado**, e a fila de escritas pendentes sobrevive ao wipe.
- **A ordem respeita as FKs:** `bookmarks`, depois `chat_tags`, depois `messages`,
  depois `chats`, e por fim `tags` (que não tem FK, mas é órfã sem ninguém apontando).
- **Apagar o token junto seria o pior desfecho possível:** um banco vazio com token
  avanzado jamais receberia os chats, porque o delta viria vazio e correto.

### 5. Persistência / Integrações

SQLite local via `ljsqlite3` (FFI). Cinco `DELETE` em uma chamada `Store:exec`, que é
um `conn:exec` puro. Sem rede e sem backend.

### 6. Resposta final

Chats, mensagens, bookmarks e tags vazios; posições de leitura zeradas; token e fila de
escritas intactos.

## Fluxos alternativos e erros

| Cenário | Comportamento |
|---|---|
| Banco não aberto | Erro explícito em `get_store()`; nada é apagado |
| Falha no meio | **Não há transação**: `clear()` não usa `Store:transaction()`, então um `DELETE` que falhe deixa o banco parcial |
| Sem rede | Irrelevante — a operação é local |
| Escritas pendentes na fila | **Sobrevivem** ao wipe e sobem no próximo sync |

## Decisões técnicas importantes

- **Preservar o token é deliberado, e é o que torna o wipe correto.** Apagá-lo produziria
  uma biblioteca permanentemente vazia. O trade-off é que o wipe não é um
  "reinicia tudo" literal: o cliente continua sendo o mesmo dispositivo.
- **A fila sobrevive ao wipe.** Escrever isso offline e depois limpar a biblioteca é
  um fluxo legítimo: as escritas sobem na próxima oportunidade de rede.
- **E-ink não tem "desfazer".** Daí a confirmação explícita.
- **Wipe é sempre reconstruível**, porque o backend é a fonte da verdade do conteúdo.

## Dívidas conhecidas

- **`clear()` não é atômico.** `Store:transaction()` existe em `sqlite.lua:184` e
  poderia envolver os cinco DELETEs; hoje um erro no meio deixa o banco parcial. Como
  a operação é destrutiva por definição e o estado é reconstruível, o dano é baixo —
  mas não é zero.
- **`outbox` sobrevive sem o conteúdo que ele referencia.** Um bookmark pendente de uma
  mensagem que acabou de ser apagada vai subir e ser recusado com `NOT_FOUND`, que o
  Kindle sabe tratar. Funciona, mas é um caminho previsível de recusa.

## Regras importantes

- Wipe nunca toca o servidor.
- Wipe nunca apaga o token de sync.
- Wipe nunca apaga escritas pendentes.
