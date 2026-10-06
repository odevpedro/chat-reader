# sample-data

Dataset fictício para importar e testar. **Nenhum conteúdo privado real** — as conversas
aqui foram escritas para o projeto (a especificação, §37, pede pelo menos 5 conversas,
com Markdown, código, tags, datas diferentes e uma conversa longa).

| Arquivo | Formato | Conversas | Para que serve |
|---------|---------|-----------|----------------|
| `chats.json` | JSON genérico (schema v1) | 10 (108 mensagens) | O caminho principal: `JsonChatImporter` |
| `chats.md` | Markdown com front-matter | 2 | `MarkdownChatImporter` (§11 da especificação) |

O export real do ChatGPT (`conversations.json`) **não** está versionado aqui: é um
arquivo pessoal, de estrutura conocida e testada em
`ChatGptExportImporterTest`. Para ver o adapter funcionando de verdade, use o seu
próprio export.

## O que o dataset exercita

| Requisito da especificação | Onde |
|---------------------------|-------|
| ≥ 5 conversas | 10 no JSON, 2 no Markdown |
| Mensagens de usuário e de assistente | todas as conversas, em pergunta/resposta alternadas |
| Papéis além de user/assistant | `sample-flyway-migracoes` tem `system` e `tool` |
| Markdown | títulos, listas, `> citação`, tabelas, links, negrito/itálico |
| Código | blocos `java`, `sql`, `bash`, `yaml` |
| Tags | todas, exercitando a normalização do backend |
| Datas diferentes | agosto, setembro de 2026 |
| Conversa longa para **paginar o leitor** | `sample-maratona-java21` — 46 mensagens (passa da janela de 40 do `views/reader.lua`) |
| Datas em 3 formatos | `sample-datas-epoch`: epoch, ISO e data pura |

E as exercitam também o `client/format.lua` do plugin (markdown → texto plano no
e-ink): **"3 * 4" não vira itálico**, blocos de código cercados são preservados e
recuados, e cada mensagem abre com `Você`/`Assistente`.

## Importar

Com o backend de pé (`docker compose up -d`):

```bash
# as duas conversas do Markdown
./scripts/import.sh sample-data/chats.md

# as dez conversas do JSON
./scripts/import.sh sample-data/chats.json
```

Reimportar qualquer um dos dois devolve `created: 0` — a importação é idempotente,
porque a identidade da conversa é `(source, externalId)` quando o `externalId` existe
(§12). Apague o banco se quiser recomeçar do zero:

```bash
docker compose down -v && docker compose up -d
```

## Conferir que entrou

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"reader","password":"sua-senha"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

# 12 conversas no total
curl -s "localhost:8080/api/chats?size=20" -H "Authorization: Bearer $TOKEN"

# busca full-text sobre conteúdo, título e tags
curl -s "localhost:8080/api/chats/search?q=token" -H "Authorization: Bearer $TOKEN"
```
