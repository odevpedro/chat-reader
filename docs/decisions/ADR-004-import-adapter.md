# ADR-004 — Importadores são adapters plugáveis

- **Status**: aceito
- **Data**: 2026-09-29
- **Contexto**: o §3 proíbe assumir APIs inexistentes, proíbe scraping obrigatório do
  ChatGPT e proíbe depender de cookies ou endpoints privados. O §11 define a abstração
  `ChatImporter`. O roadmap (§39) prevê ChatGPT, Claude, Gemini, Markdown, Obsidian, PDF.

## Decisão

Uma única interface no domínio, com implementações em `infrastructure`:

```java
public interface ChatImporter {
    ImportFormat format();                       // qual formato este adapter sabe ler
    boolean supports(ImportSource source);       // detecção por nome/extração/conteúdo
    NormalizedChat[] importChats(ImportSource source);
}
```

O **único** tipo que entra no domínio é o `NormalizedChat` (um registro neutro:
`externalId`, `title`, `createdAt`, `updatedAt`, `messages[]`, `tags[]`). Nenhuma classe
do domínio conhece "ChatGPT", "conversations.json" ou o formato de qualquer exportador.

Implementações no MVP:

| Classe                    | Formato                              | Observação                        |
|---------------------------|--------------------------------------|-----------------------------------|
| `GenericJsonChatImporter` | JSON genérico do projeto (`chats.json`) | schema documentado em `docs/importers.md` |
| `MarkdownChatImporter`    | Markdown com front-matter             | segmentado por `---`               |
| `ChatGptExportImporter`   | `conversations.json` do export do ChatGPT | adapter do formato de **arquivo** |

A detecção de formato é **hierárquica e explicável**: o cliente pode declarar
(`?format=CHATGPT_EXPORT`); se não declarar, o `ChatImporterRegistry` tenta cada adapter
por sniffing de assinatura (chave JSON de topo, presença de `---` + front-matter) e
devolve um erro claro se nenhum reconhecer.

## Motivos

- O domínio não é acoplado a nenhum exportador, exatamente como o §3 exige.
- Adicionar Claude/Gemini/Obsidian é escrever **um** adapter novo, sem tocar no domínio,
  na API ou no cliente Kindle.
- `NormalizedChat` normaliza `role` e `sequence`, então o resto do sistema nunca precisa
  lidar com diferenças de formato.
- O importador é **puro**: recebe bytes, devolve `NormalizedChat[]`. A gravação no banco é
  responsabilidade de `ImportService`. Isso torna os importadores triviais de testar sem
  banco.

## Consequências

**Positivas**::testes unitários de parser sem banco; adicionar fonte é barato; o mesmo
backend suporta export de qualquer ferramenta; idempotência resolvida uma vez, no
`ImportService`, não por importer.

**Negativas**: mais um nível de tradução (exportador → `NormalizedChat` → domínio);
schemas de origem que não batem com `NormalizedChat` exigem decisão de mapeamento
(perda de informação é melhor que exceção); detectar formato por sniffing pode errar.

**Mitigações**: o schema de origem é **versionado** (`"schemaVersion": 1`); o importer
que não reconhece a versão falha com mensagem clara; `ImportResult` reporta contagens e
falhas por item, então um arquivo parcialmente inválido não perde o que deu certo.

## Alternativas rejeitadas

- **Adapter de API do ChatGPT**: rejeitado — não existe API pública documentada para o
  histórico completo. Importação é sempre por **arquivo** que o usuário possui.
- **Scraping da interface web / uso de cookies**: rejeitado explicitamente pelo §3.
- **Um importer "genérico" com `if (formato == "chatgpt")`**: rejeitado — volta a acoplar
  o domínio ao formato, exatamente o que o §3 proíbe.
