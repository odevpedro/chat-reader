# Importadores

O backend **não conhece** o formato de nenhum exportador específico. Cada formato é
um *adapter* que traduz bytes em `NormalizedChat` (ver
[ADR-004](decisions/ADR-004-import-adapter.md)). Existem três adapters: o **JSON
genérico** (`JsonChatImporter`), o **Markdown** (`MarkdownChatImporter`) e o **export
do ChatGPT** (`ChatGptExportImporter`).

## Formatos suportados

| Formato | Enum | Estado |
|---------|------|--------|
| JSON genérico (schema v1) | `JSON` | ✅ Etapa 2 |
| Markdown (transcrição em `.md`) | `MARKDOWN` | ✅ Etapa 3 |
| Export do ChatGPT (`conversations.json`) | `CHATGPT_EXPORT` | ✅ Etapa 3 |

`GET /api/chats/import/formats` lista os adapters ativos.

## Schema JSON v1

```json
{
  "schemaVersion": 1,
  "chats": [
    {
      "id": "identificador-estavel-opcional",
      "title": "Título da conversa",
      "createdAt": "2026-09-20T14:00:00Z",
      "updatedAt": "2026-09-27T18:30:00Z",
      "tags": ["Java", "Spring"],
      "messages": [
        {
          "role": "user",
          "content": "Pergunta com **Markdown**.",
          "createdAt": "2026-09-20T14:00:00Z"
        },
        {
          "role": "assistant",
          "content": "Resposta com `código`.",
          "createdAt": "2026-09-20T14:00:30Z"
        }
      ]
    }
  ]
}
```

Um exemplo completo está em [`sample-data/chats.json`](../sample-data/chats.json).

### Raiz

| Campo | Obrigatório | Observação |
|-------|-------------|------------|
| `chats` | sim* | Array de conversas. Aliases: `conversations`, `conversations_list`. |
| `schemaVersion` | não | Padrão `1`. Valores maiores que 1 são recusados com 422. |

\* É obrigatório que exista ao menos um dos aliases do array.

### Conversa

| Campo | Aliases aceitos | Observação |
|-------|-----------------|------------|
| `id` | `externalId`, `conversation_id`, `conversationId` | Opcional. Se ausente, a idempotência usa o `contentHash`. |
| `title` | `content` | Se não houver título, usa a primeira linha de `content`. Vazio vira `"Conversa sem titulo"`. |
| `createdAt` | `create_time`, `created_at` | ISO-8601, epoch em segundos ou `YYYY-MM-DD`. |
| `updatedAt` | `update_time`, `updated_at` | Idem. |
| `tags` | — | Array de strings; tags vazias são ignoradas. Normalizadas (trim, dedup case-insensitive, ordem alfabética) e vinculadas ao chat na importação. |
| `messages` | `mapping`, `conversation` | Array **ou** objeto (o formato `mapping` do ChatGPT é lido como objeto de valores). |

### Mensagem

| Campo | Aliases aceitos | Observação |
|-------|-----------------|------------|
| `role` | `author`, `sender`, ou `author.role` / payload aninhado | Ver tabela de papéis. Valor desconhecido vira `UNKNOWN` — a importação **nunca** falha por papel desconhecido. |
| `content` | `text`, `content.parts[]`, `content[]` | O conteúdo é guardado **cru** (Markdown não é renderizado no backend). |
| `createdAt` | `create_time`, `created_at` | Igual à conversa. |
| `id` | `externalId`, `message_id`, `messageId` | Opcional. |

O formato aninhado `{"message": {"author": {"role": ...}, "content": {"parts": [...]}}}`,
típico de exports do ChatGPT, também é aceito.

### Papéis (`RoleMapper`)

| Origem | `Role` |
|--------|--------|
| `user`, `human`, `prompt`, `voce`, `você`, `me` | `USER` |
| `assistant`, `gpt`, `model`, `bot`, `chatgpt`, `claude`, `gemini` | `ASSISTANT` |
| `system`, `developer` | `SYSTEM` |
| `tool`, `function`, `tool_call` | `TOOL` |
| qualquer outro / ausente | `UNKNOWN` |

## Idempotência

Reimportar o mesmo arquivo **não duplica** nada (ver
[docs/domain.md](domain.md)). Por conversa:

1. calcula o `contentHash` canônico (título + mensagens);
2. procura por `(source, externalId)`; se não houver `externalId`, procura por
   `(source, contentHash)`;
3. achou e o conteúdo é igual → conta como `skipped`; achou e mudou → atualiza *in
   place* e incrementa a versão; não achou → cria.

Um item inválido é **pulado** e reportado em `failures`, sem derrubar o lote. O
limite de conversas por arquivo (`IMPORT_MAX_CHATS`) e de mensagens por conversa
(`IMPORT_MAX_MESSAGES_PER_CHAT`) também é aplicado.

## Como adicionar um adapter

1. Implemente `ChatImporter` em `importer/infrastructure`:
   - `format()` devolve o valor de `ImportFormat`;
   - `supports(ImportSource)` faz a detecção por assinatura;
   - `importChats(ImportSource)` devolve `List<NormalizedChat>`.
2. Registre o formato no enum `ImportFormat`.
3. O `ChatImporterRegistry` descobre a implementação por injeção do Spring — não há
   switch central.
4. Adicione testes de unidade puros (sem banco), como `JsonChatImporterTest`.

> Regra de ouro do domínio: o pacote `importer` pode depender de
> `chat.domain.Role`/`Message`, mas **nenhum** código de domínio conhece
> `importer`. Isso é verificado por `ArchitectureTest`.

## Datas

Os três adapters normalizam tempo por `importer/domain/DateTimes.java`, então aceitam
a mesma coisa:

| Entrada | Vira |
|---------|------|
| `2026-09-27T18:30:00Z` (ISO-8601) | `Instant`, preservado |
| `1788220800` ou `"1788220800"` (epoch em segundos) | `Instant`, convertido |
| `2026-09-27` | `Instant` no início do dia, UTC |
| ausente / vazio / irreconhecível | `null` — o backend decide o fallback |

O formato `mapping` do export do ChatGPT traz `create_time` como epoch **float**
(`1756307895.123456`); os milissegundos são preservados. O ChatGPT também emite um nó
de sistema (`current_node`) que aponta para o nó raiz da conversa — ele é **ignorado**,
porque guardá-lo criaria um artefato invisível com o texto da conversa inteira.

## Adapter Markdown

Lê transcrições em Markdown, o formato que a pessoa realmente tem no disco depois de
copiar do chat para um arquivo. A estrutura é:

```markdown
---
title: Ler Markdown confortável no Kindle
updatedAt: 2026-09-27T10:00:00Z
tags: [leitura, markdown]
---

## user — 27/09/2026 09:00

Como deixar código e listas legíveis em uma tela e-ink?

<!-- nota do autor, removida da mensagem -->

## assistant — 27/09/2026 09:01

Duas regras curtas: ...
```

- **Front matter** YAML opcional antes do primeiro `H1`. Aceita `title`, `createdAt`,
  `updatedAt` e `tags`. Só o que estiver presente é usado; o resto vem do corpo.
- **Cada `H1`** inicia uma conversa nova. `H2` inicia um bloco de mensagem. Vários
  `H1` no mesmo arquivo → várias conversas na mesma importação.
- **Papel e data** vêm do cabeçalho da mensagem, no formato `## <papel> — <data>`.
  O travessão pode ser `—`, `-` ou `:`. O papel passa pelo mesmo `RoleMapper` dos
  outros adapters (desconhecido → `UNKNOWN`); a data passa pelo `DateTimes`.
- **O corpo** do bloco é o conteúdo, com o comentário HTML do bloco removido.
- Sem data no cabeçalho: o bloco herda a data da conversa.

Exemplo real em [`sample-data/chats.md`](../sample-data/chats.md).

## Adapter do export do ChatGPT

Lê o `conversations.json` do "Export data" do ChatGPT (o mesmo arquivo que o
ChatGPT entrega no pedido de exportação). A topologia é um grafo: cada nó aponta para
o pai, e a conversa é a raiz.

- Ordena os nós por `create_time` para ter ordem de leitura estável (a ordem do objeto
  no JSON não é garantida pelo exportador).
- Mantém **todas** as ramificações, inclusive as mensagens editadas que o ChatGPT
  guarda como Alternative e as descartadas por `metadata.is_visually_hidden_from_conversation`.
  Apagar o que o usuário regerou perderia histórico; quem decide o que exibir é a
  interface, que já sabe mostrar ramo alternativo.
- Extrai `author.role` (`system`, `developer`, `user`, `assistant`, `tool`) e o texto
  de `content.parts[]`, juntando as partes com quebra de linha.
- O título vem de `title`; se estiver vazio, cai na primeira mensagem de usuário.

> Consequência de projeto: as mensagens `system`/`tool` do export entram no banco.
> Isso é deliberado — `Role` tem esses valores justamente para que a leitura mostre a
> conversa como ela foi, e não como o backend imagina que foi.

## Detecção entre os três

Sem `format` no request, o backend testa os adapters por assinatura:

1. **JSON genérico** — conteúdo começa com `{` e tem um array de chats (`chats`,
   `conversations` ou `conversations_list`). Também cobre o `mapping` do ChatGPT, mas
   o adapter específico tem precedência.
2. **Export do ChatGPT** — objeto com `mapping` cujas chaves são UUIDs e
   `current_node` presente.
3. **Markdown** — texto com `#` de título ou travessão `---` de front matter.

Se nenhum adapter aceitar, a resposta é **422**.
