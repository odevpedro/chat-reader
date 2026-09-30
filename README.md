# chat-reader

Transforme suas conversas com IA em uma **biblioteca pessoal de leitura no Kindle**.

- **Backend** — Spring Boot 3 / Java 21 / PostgreSQL / Flyway
- **Kindle** — plugin Lua para KOReader, offline-first, SQLite local

O fluxo é: importar → sincronizar → ler no Kindle → favoritar/bookmark → continuar offline.

## Estado

🚧 **Etapas 5 e 6 concluídas; falta o passo em dispositivo.** A verificação das APIs
reais (R5) está feita e `kindle/` está escrito, com **131 specs** verdes no container:
store SQLite com migração, sync transacional, escritas offline com outbox, lógica pura
de biblioteca/leitor, adapter HTTP e settings, e — desde o último commit — as telas
**executando de verdade** sob stubs do KOReader (`spec/ui_spec.lua`), com os campos de
cada widget conferidos contra a fonte do upstream.

Dois testes rodam contra o backend **real**, não contra stub:

- `./kindle/scripts/contract.sh` → `contrato ok` (sync ponta a ponta, ACK, token);
- `./kindle/scripts/offline.sh` → `offline ok` (backend derrubado, biblioteca e
  escritas continuam funcionando, fila drena quando ele volta).

Para instalar no aparelho: **[docs/instalar-no-kindle.md](docs/instalar-no-kindle.md)**.
`./kindle/scripts/package.sh` monta o `.koplugin` e verifica que ele carrega fora do
repo — um `require` quebrado passa nos specs e só viraria tela branca no Kindle.

O único item aberto é o plugin rodar no KOReader de verdade (emulador/dispositivo),
que nenhum teste em container substitui: e-ink, gestos de página, teclado.

| Etapa | Status | Documento |
|-------|--------|-----------|
| 1. Discovery | ✅ | [docs/risks-and-plan.md](docs/risks-and-plan.md) |
| 2. Backend mínimo | ✅ | [docs/api.md](docs/api.md), [docs/importers.md](docs/importers.md) |
| 3. Busca e organização | ✅ | [docs/api.md](docs/api.md), [docs/domain.md](docs/domain.md) |
| 4. Sincronização | ✅ | [docs/sync-protocol.md](docs/sync-protocol.md) |
| 5. Kindle / KOReader | ✅ | [docs/kindle.md](docs/kindle.md) |
| 6. Offline | ✅ | [docs/kindle.md](docs/kindle.md) |
| 7. Refinamento | ⏳ | — |

O que já existe: `Chat`/`Message`, importação idempotente em **três formatos** (JSON
genérico, Markdown e export do ChatGPT), REST `/api/chats`, tags, favoritos, bookmarks
e busca full-text, autenticação JWT, Flyway `V1`–`V6`, métricas e **211 testes**
(133 unit + 78 integração com Testcontainers).

A sincronização é **pull-only**: `GET /api/sync?since=<token>` devolve só o que
mudou depois do token do cliente, `GET /api/sync/snapshot` recupera o estado
completo quando o token fica antigo, e `POST /api/sync/ack` confirma o token
trazendo as escritas feitas offline (favoritos e bookmarks).

## Documentação

| Documento | Conteúdo |
|-----------|----------|
| [docs/architecture.md](docs/architecture.md) | Arquitetura, módulos, decisões técnicas |
| [docs/domain.md](docs/domain.md) | Modelo de domínio, invariantes, idempotência, busca |
| [docs/api.md](docs/api.md) | Endpoints REST, paginação, autenticação, erros |
| [docs/importers.md](docs/importers.md) | Schema de importação, detecção, como criar adapters |
| [docs/sync-protocol.md](docs/sync-protocol.md) | Protocolo de delta sync, conflitos, resiliência |
| [docs/kindle.md](docs/kindle.md) | Cliente KOReader: desenho, SQLite, performance, testes |
| [docs/instalar-no-kindle.md](docs/instalar-no-kindle.md) | Passo a passo para instalar no aparelho e diagnosticar |
| [docs/risks-and-plan.md](docs/risks-and-plan.md) | Riscos, plano por etapa, verificação de APIs |
| [docs/system-feature-flows.md](docs/system-feature-flows.md) | Fluxo de cada feature: entrada, camadas, erros, decisões |
| [docs/data-model.md](docs/data-model.md) | Modelo persistido: entidades, atributos, índices, consistência, privacidade |
| [docs/decisions/](docs/decisions/) | ADRs |

### ADRs

| ADR | Decisão |
|-----|---------|
| [001](docs/decisions/ADR-001-modular-monolith.md) | Modular monolith, sem microsserviços |
| [002](docs/decisions/ADR-002-kindle-as-offline-client.md) | Kindle é cliente offline-first |
| [003](docs/decisions/ADR-003-sync-protocol.md) | Change log monotônico como sync token |
| [004](docs/decisions/ADR-004-import-adapter.md) | Importadores são adapters plugáveis |
| [005](docs/decisions/ADR-005-backend-source-of-truth.md) | Backend é a fonte da verdade do conteúdo |
| [006](docs/decisions/ADR-006-postgres-full-text-search.md) | Full-text no Postgres, sem Elasticsearch |
| [007](docs/decisions/ADR-007-kindle-performance.md) | Orçamento de memória e refresh no e-ink |
| [008](docs/decisions/ADR-008-epub-optional.md) | EPUB como modo alternativo, não principal |

## Requisitos

| Ferramenta | Versão |
|------------|--------|
| Java | 21 |
| Docker + Docker Compose | 24+ |
| KOReader (Kindle jailbroken) | ≥ 2023.x |
| PostgreSQL | 16 (via Docker) |

`mvn` e `lua` não são necessários para rodar — ambos vêm em container / no dispositivo.

## Início rápido

```bash
git clone <repo> chat-reader
cd chat-reader
cp .env.example .env

# 1. gere um segredo forte e cole em JWT_SECRET
openssl rand -base64 48

# 2. gere o hash da sua senha e cole em CHAT_READER_PASSWORD_HASH
./scripts/hash-password.sh 'sua-senha'

# 3. suba tudo
docker compose up -d --build
```

| Serviço | URL |
|---------|-----|
| API | http://localhost:8080 |
| OpenAPI (Swagger UI) | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |

Importe os arquivos de exemplo e valide o fluxo:

```bash
# 6 conversas (JSON genérico)
./scripts/import.sh sample-data/chats.json

# 2 conversas (Markdown) e o export do ChatGPT, se você tiver o seu
./scripts/import.sh sample-data/chats.md
./scripts/import.sh ~/Downloads/conversations.json

# ou rode o smoke test ponta a ponta (health → login → import → listagem)
CHAT_READER_USERNAME=reader CHAT_READER_PASSWORD='sua-senha' ./scripts/smoke.sh
```

Importar o mesmo arquivo de novo devolve `created: 0` e `skipped: 6` — a importação
é idempotente. Veja [sample-data/README.md](sample-data/README.md).

Organize e busque (com token em `$TOKEN`):

```bash
# favoritos
curl -X PUT localhost:8080/api/chats/<id>/favorite -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"favorite":true}'

# tags de uma conversa
curl -X PUT localhost:8080/api/chats/<id>/tags -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"tags":["java","spring"]}'

# busca full-text (título, conteúdo e tags)
curl "localhost:8080/api/chats/search?q=transactional" -H "Authorization: Bearer $TOKEN"
```

Sincronize (com token em `$TOKEN`):

```bash
# delta desde o token 0: devolve fullResyncRequired=true (cliente novo)
curl "localhost:8080/api/sync?since=0&limit=200" -H "Authorization: Bearer $TOKEN"

# estado completo, e o token que o cliente passa a usar
curl "localhost:8080/api/sync/snapshot" -H "Authorization: Bearer $TOKEN"

# delta incremental com o token guardado
curl "localhost:8080/api/sync?since=5120&limit=200" -H "Authorization: Bearer $TOKEN"

# confirma o token e envia escritas feitas offline
curl -X POST localhost:8080/api/sync/ack -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"ackVersion":0,"clientId":"kobo","localChanges":[
        {"type":"CHAT_FAVORITE","payload":{"chatId":"<id>","favorite":true}}]}'
```

## Desenvolvimento

```bash
cd backend
# unit tests (sem Docker)
mvn test
# unit + integração (Testcontainers sobe um Postgres 16)
mvn verify

# suíte Lua do plugin KOReader (imagem com o mesmo LuaJIT + ljsqlite3 do KOReader)
./kindle/spec.sh

# contrato e offline contra o backend REAL (sobem um backend descartável em
# :8082 / :8081; o seu stack em :8080 não é tocado)
./kindle/scripts/contract.sh
./kindle/scripts/offline.sh
```

O schema é gerenciado **somente** pelo Flyway (`ddl-auto: validate`). Credenciais vêm
sempre do ambiente.

## Princípios inegociáveis

1. Backend é a **fonte da verdade** do conteúdo das conversas.
2. O Kindle é **offline-first** — nenhuma tela depende de rede.
3. A sincronização é **incremental** (delta por token), nunca full-download.
4. O domínio **não** conhece ChatGPT nem nenhum formato de exportador.
5. Nada de microsserviços, Redis, Elasticsearch, Kafka ou WebSocket.
6. Otimizado para e-ink: sem animação, sem polling, sem flash de tela.

## Licença

MIT — ver [LICENSE](LICENSE).
