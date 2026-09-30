---
id: md-kindle-sync
title: Como o delta sync lida com token antigo
createdAt: 2026-09-05T15:20:00Z
updatedAt: 2026-09-05T15:38:00Z
tags: [Sync, Kindle, ADR]
---

## VOCE

Se eu não sincronizar por 40 dias, o que acontece com o meu token?

<!-- 2026-09-05T15:20:00Z -->

## CHATGPT

O change log tem **retenção dupla**: 30 dias ou 1.000.000 de linhas, o que vier
primeiro. Passou disso, o token que você manda está antes da janela mais antiga que o
servidor ainda guarda, e a resposta é:

```json
{ "fullResyncRequired": true, "syncToken": 5120 }
```

O cliente então chama `GET /api/sync/snapshot`, que entrega o estado completo
**paginado por conversa** — não o histórico inteiro de uma vez. Cada página vem com
`last: false` até a última.

<!-- 2026-09-05T15:31:00Z -->

## VOCE

O que acontece com os favoritos que eu fiz offline nesse meio tempo?

<!-- 2026-09-05T15:35:00Z -->

## CHATGPT

Nada é perdido. A ordem do ciclo é sempre a mesma:

1. **pull** (`GET /api/sync`) — busca o que mudou;
2. se veio `fullResyncRequired`, **snapshot** paginado;
3. **ACK** (`POST /api/sync/ack`) — confirma o token **e** sobe a fila local.

A fila local (`outbox`) sobrevive ao snapshot porque o snapshot mexe no catálogo, não
nas escritas pendentes. O que o ACK não aceitar volta para a fila **com o motivo**, para
a tela avisar em vez de sumir com a alteração.

> O token devolvido no ACK **não** vira token local: ele pode incluir mudanças de outro
> dispositivo que você ainda não baixou. O token local só avança no pull.

<!-- 2026-09-05T15:38:00Z -->

# Markdown como fonte da biblioteca

---
id: md-markdown-biblioteca
title: Por que aceitei ler minhas conversas no e-ink
createdAt: 2026-08-02T21:00:00Z
updatedAt: 2026-08-02T21:24:00Z
tags: [Kindle, UX, Estudos]
---

## VOCE

Por que o Kindle é um bom leitor de conversa, apesar de ser tela lenta?

<!-- 2026-08-02T21:00:00Z -->

## CHATGPT

Porque conversa longa é **texto longo**, e o Kindle foi projetado para ler texto longo.

- A bateria dura dias, porque a tela só gasta energia quando muda;
- Não há notificação, badge ou *feed* puxando a atenção de volta;
- A distância do olhar é a mesma da leitura de livro, e o texto grande na tela pequena
  é confortável.

O que o Kindle **não** tem é o que torna a interface de chat ruim para ler: rolagem
infinita sem posição, nada de "onde parei", e updates chegando sozinhos.

<!-- 2026-08-02T21:12:00Z -->

## VOCE

O que fazer com a posição de leitura?

<!-- 2026-08-02T21:20:00Z -->

## CHATGPT

Guardar por conversa a última mensagem lida, e oferecer "continuar de onde parei" como
ação padrão da biblioteca. O Kindle guarda isso localmente (`last_read_message_id` no
SQLite) — é estado de leitura, e estado de leitura pertence ao dispositivo, não ao
servidor (ADR-005).
