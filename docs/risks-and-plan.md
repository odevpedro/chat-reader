# Riscos e Plano de Implementação

> Complemento de discovery. Requer aprovação antes de iniciar a Etapa 2.

## 1. Riscos técnicos

| # | Risco | Impacto | Probabilidade | Mitigação no MVP |
|---|-------|---------|---------------|------------------|
| R1 | **Identidade estável ausente** em exports reais (sem `externalId` no Markdown) | Importar o mesmo arquivo 2× cria duplicata | Alta | Chave `(source, externalId)` quando existe; senão `contentHash`; teste de idempotência com Testcontainers |
| R2 | **Change log cresce sem limite** | Sync retorna `fullResyncRequired` e o Kindle baixa tudo | Média | Retenção 30 dias / 1M linhas + job; full resync é paginado |
| R3 | **Payload de sync grande demais** para a RAM do Kindle | OOM ou freeze ao aplicar o delta | Média | `messagesOmitted` + paginação de 20; `limit` adaptativo; `413` → cliente reduz `limit` |
| R4 | **Relógio do Kindle errado** corrompe LWW de `favorite` | Favorito alterna sozinho | Alta | Servidor satura `updatedAt` no futuro + desempate por `seq` de dispositivo; log `CLOCK_SKEW` |
| R5 | **API do KOReader diferente do assumido** (SQLite binding, HTTP, widgets) | Código do Kindle não roda no dispositivo | Média | **Feito:** APIs conferidas no código do KOReader (tabela em [kindle.md](kindle.md#verificação-de-apis-r5--feito-commit-b539d24-de-2026-09-29)) e a suíte roda no mesmo LuaJIT + ljsqlite3 do dispositivo |
| R6 | **Busca degrada em 100k+ mensagens** | Busca lenta | Média | GIN + `tsvector` gerado + keyset; `VACUUM ANALYZE` após import; benchmark com dataset grande |
| R7 | **Import malformado** trava ou polui o catálogo | Import falha inteiro | Média | Limites de tamanho; `try/catch` por chat; `ImportBatch` com contadores e `rejections`; schema versionado |
| R8 | **Tela de e-ink com refresh FLASH** a cada ação | UX ruim, bateria | Alta | Desenho único por ação; sem animação; sem polling ([ADR-007](decisions/ADR-007-kindle-performance.md)) |
| R9 | **Duplicação de dados** (Postgres + SQLite) consome o armazenamento do Kindle | Downloads parciais | Média | Delta sync; `messagesOmitted`; mostrar tamanho do cache nas Configurações |
| R10 | **Divergência de schema** entre backend e cliente ao evoluir | Sync quebrado após atualização | Média | Token opaco; cliente ignora campos/tipos desconhecidos; migração de SQLite versionada |
| R11 | **Rate limit em memória** não sobrevive a restart / não é distribuído | Abuso possível se exposto | Baixa (usuário único) | Documentar limitação; `allow_insecure_http` desligado por padrão; HTTPS obrigatório |
| R12 | **Sequência do Postgres** reseta em restore inconsistente do dump | Token invalido → full resync | Baixa | Dump com `pg_dump` consistente; cliente tolera token antigo; full resync é seguro |

### Riscos **não** aceitos (documentados, fora do escopo)

- Sem multiusuário / RBAC (o MVP é de usuário único).
- Sem refresh token nem revogação de JWT.
- Sem criptografia em repouso no SQLite local (o Kindle não tem keystore utilizável).
- Sem verificação de assinatura de export de terceiros (o usuário é a fonte).

## 2. Plano de implementação

| Etapa | Entrega | Critério de pronto | Risco principal |
|-------|---------|---------------------|-----------------|
| **1. Discovery** | `docs/`, ADRs, estrutura | Este documento aprovado | — |
| **2. Backend mínimo** | Spring Boot, Postgres, Flyway `V1`–`V2`, `Chat`/`Message`, `JsonChatImporter`, REST `/api/chats` | `docker compose up` sobe; importar `sample-data/chats.json`; listar via API; testes verdes | R1, R7 |
| **3. Busca e organização** ✅ | Tags, favoritos, bookmarks, busca full-text (`V3`–`V5`), paginação | Busca por título/conteúdo/tag com snippet; favoritar; bookmark de mensagem; tag retorna chats | R6 |
| **4. Sincronização** ✅ | `sync_change_log` (`V6`), `GET /api/sync`, `POST /api/sync/ack`, `GET /api/sync/snapshot` | Testes cobrem: 1ª sync, incremental, chat novo/atualizado/removido, bookmark, token, reset | R2, R4 |
| **5. Kindle** ✅ | `main.lua`, SQLite + migrations, client HTTP, sync, biblioteca, lista, leitor, busca, favoritos, settings | Plugin carrega no KOReader; sync popula o banco; navegação entre mensagens | R5, R8 |
| **6. Offline** ✅ | Teste com o backend **desligado** | Conversas, bookmarks, navegação e busca funcionam sem rede | R9 |

> **Etapas 5 e 6 encerradas.** R5 foi fechado conferindo o código do KOReader
> (`koreader/koreader@b539d24`); `kindle/` está escrito e coberto por **137 specs**
> em `./kindle/spec.sh` — store SQLite, sync transacional, outbox, lógica pura de
> biblioteca/leitor, adapters HTTP/settings, sintaxe de todos os arquivos e a UI
> (`main.lua`, biblioteca, leitor, configuração). O leitor usa o `TextViewer` do
> KOReader com markdown (mdToHtml + crengine); não há renderer próprio. O passo em
> **dispositivo** também passou (KOReader v2026.03 no Kindle): o primeiro sync morria
> porque `DUSE_TURBO_LIB = false` → sem `UIManager.looper` o `httpclient` quebra;
> `client/http.lua` foi movido para `socket.http` + `socketutil` (síncrono) e o sync
> de ponta a ponta rodou no aparelho (detalhes em `docs/kindle.md`).
>
> As duas etapas que dependiam do backend real também fecharam:
>
> - `./kindle/scripts/contract.sh` → `contrato ok`. Login, snapshot, escrita
>   offline, ACK, token que só avança no pull seguinte, delta vazio no ciclo 3.
> - `./kindle/scripts/offline.sh` → `offline ok`. Backend derrubado de verdade
>   (connection refused, não 401): biblioteca, favoritos, navegação paginada, busca
>   local, favorito/bookmark intactos, sync falha sem corromper o banco, escrita
>   offline continua enfileirando, e a fila drena quando o backend volta.
>
> Ambos sobem um backend **descartável** em `:8082`/`:8081` reaproveitando o seu
> Postgres, porque o `.env` guarda só o hash da senha e o teste precisa de login em
> texto claro — pedir a senha de verdade tornaria o teste impraticável.
>
> **R9 encerrada** com o caveat que vale registrar: o teste prova que a *biblioteca*
> não depende de rede, mas não prova que a *UI* não depende, porque `ui/library.lua`,
> `ui/reader.lua` e `main.lua` só passam por verificação de sintaxe nos specs (precisam
> dos widgets do KOReader). A dependência delas de rede é verificável por leitura — e
> é o que o próximo item cobre.
>
> **Único item aberto:** o plugin rodar no KOReader de verdade (emulador ou
> dispositivo). Nenhum teste em container substitui isso, e é de propósito que
> nenhum tente.
| **7. Refinamento** | UX, renderer de Markdown/código, tratamento de erro, docs (`api.md`, `importers.md`, `kindle.md`), EPUB opcional | Fluxo completo do §44 validado de ponta a ponta | R3, R10 |

### Ordem de construção do backend (arquivos)

```text
ChatReaderApplication
config/        (SecurityConfig, OpenApiConfig, JacksonConfig, MetricsConfig, DataSeeder)
shared/        (PageResponse, CursorPage, ApiExceptionHandler, Hashing)
chat/          domain/{Chat, Message, Role, ContentHash, ChatRepository(port)}
               application/{ChatService, ChatQueryService}
               infrastructure/{ChatEntity, MessageEntity, ChatJpaRepository, MessageJpaRepository,
                                ChatRepositoryAdapter, ChatMapper, ChatController, ChatDto}
importer/      domain/{ChatImporter, ImportSource, ImportResult, NormalizedChat, NormalizedMessage, ImportFormat}
               application/{ImportService, ChatImporterRegistry}
                infrastructure/{JsonChatImporter, ImportController}
                (Etapa 3: MarkdownChatImporter, ChatGptExportImporter)
tag/           domain/{Tag, TagRepository(port)} … TagController
bookmark/      domain/{Bookmark, BookmarkRepository(port)} … BookmarkController
search/        application/SearchService  infrastructure/{SearchQueryRepository, SearchController}
sync/          domain/{ChangeType, SyncChange, ChangeLog(port), LocalChange, SyncDelta,
                          SyncSnapshot, AckResult}
               application/{SyncService, ChangeLogWriter, SyncProperties}
               infrastructure/{ChangeLogEntity, ChangeLogJpaRepository, ChangeLogAdapter,
                                SyncChangeMapper, SyncDtos, SyncController}
security/      {JwtService, TokenAuthFilter, CurrentUser, AuthProperties, AuthController}
```

### Dependências do backend (mínimas)

`spring-boot-starter-web`, `-data-jpa`, `-validation`, `-actuator`, `postgresql`,
`flyway-core` + `flyway-database-postgresql`, `springdoc-openapi-starter-webmvc-ui`,
`jjwt-api/impl/jackson`, `testcontainers-postgresql` + `spring-boot-testcontainers`,
`junit-jupiter`, `mockito`, `assertj`, `archunit-junit5`.

Nada de Lombok (chamadas explícitas), nada de MapStruct (mapeamento à mão, são
~5 DTOs), nada de Flyway repetido, nada de cliente HTTP assíncrono.

## 3. Verificação de APIs externas (Etapa 5, antes de codar)

O §19/§42 proíbe inventar APIs. Antes de escrever `kindle/`, será **verificado no código
do KOReader**:

- `PluginManager:new{ file = "main.lua" }`, `onShow`, `onClose` (contrato de plugin).
- `ui.menu`, `MenuWidget:new{ title, item_table }`, `onSelect*` — listas e navegação.
- `UIManager:showInfo/showConfirm`, `bookmarks` — mensagens e diálogos.
- `LuaSettings` — persistência de preferências do plugin.
- **SQLite**: como o plugin `readerhighlight` obtém o binding (FFI `sqlite3` vs. módulo
  `luasql`); qual API e como abrir/criar o arquivo em `datadir`.
- **HTTP**: qual cliente o KOReader empacota (`socket.http` + `socket.ssl` vs. uma lib
  própria) e como `NetworkMgr` informa conectividade.
- Se `Storage:sqlite` existir, se há uma camada oficial de cache SQLite.

Só então o código é escrito. Se algum item não existir na versão-alvo, a alternativa é
desenhar o `ClientHttp` como **porta** com um adapter HTTP real e um adapter de teste, e
registrar a limitação.

## 4. Como testar sem o Kindle físico

1. **Backend** — `mvn verify` (unit + integração com Testcontainers/Postgres).
2. **Kindle** — testes de lógica pura em Lua (markdown renderer, protocolo, canonicalização
   de hash) executados com `lua`/`luajit` e `busted` **fora** do dispositivo, contra
   fixtures.
3. **Integração KOReader** — um `spike` mínimo que roda o fluxo real (abrir banco, criar
   menu, navegar) no emulador/dispositivo, documentado em `docs/kindle.md#como-testar`.
4. **Contrato de sync** — um script (`scripts/contract_test.lua`) sobe com o backend real,
   popula o SQLite, aplica um delta e confere o estado. Roda em Lua puro, sem KOReader,
   porque o SQLite e o JSON são acessados por abstrações injetadas.

## 5. Definição de pronto (por etapa)

Uma etapa só fecha quando (§45):

- [ ] compila (`mvn -q clean verify` sem erro);
- [ ] testes relevantes passam (unit + integração);
- [ ] `docker compose up -d` reproduz o ambiente do zero;
- [ ] documentação atualizada (`docs/`, README, OpenAPI);
- [ ] sem credenciais no Git (`.env` ignorado, `.env.example` versionado);
- [ ] tratamento de erro implementado e testado;
- [ ] nenhuma API externa inventada (verificado na fonte).

---

Aprovando este documento, inicio a **Etapa 2 — Backend mínimo**.

### Checklist da Etapa 4 (concluída)

- [x] `V6__create_sync.sql` com `version BIGSERIAL` e índices por `version`/`created_at`;
- [x] eventos gravados na mesma transação do dado que os motivou (`ChangeLogWriter`);
- [x] `GET /api/sync` com `since`, `limit`, `hasMore` e `fullResyncRequired`;
- [x] retenção dupla (30 dias ou 1.000.000 de linhas) na janela do pull;
- [x] `GET /api/sync/snapshot` paginado por chat, com `messagesOmitted`/`bookmarksOmitted`;
- [x] `POST /api/sync/ack` com escrita idempotente por item e recusas por índice;
- [x] escrita de conteúdo recusada com `CONFLICT` (ADR-005);
- [x] `DELETE /api/chats/{id}` como tombstone + `GET /api/chats/{id}/messages` paginado;
- [x] métricas `chatreader.sync_requests_total` e `chatreader.sync_changes_total`;
- [x] `SyncServiceTest` (11) e `SyncApiIT` (16) verdes; total **211 testes** (133 unit + 78 integração);
- [x] `MarkdownChatImporterTest` (14) e `ChatGptExportImporterTest` (10) verdes;
- [x] documentação atualizada (`api.md`, `domain.md`, `sync-protocol.md`, README).
