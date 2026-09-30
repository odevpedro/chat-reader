# ADR-003 — Protocolo de sync por change log monotônico

- **Status**: aceito
- **Data**: 2026-09-29
- **Contexto**: a especificação exige sincronização incremental (§15, §16) com
  `syncToken` numérico, e a lista completa de tipos de mudança
  (`CHAT_*`, `MESSAGE_*`, `TAG_*`, `BOOKMARK_*`).

## Decisão

Uma tabela `sync_change_log` no Postgres, com `version BIGSERIAL PRIMARY KEY` gerado por
sequência. **O `version` é o `syncToken`**, opaco para o cliente.

```sql
CREATE TABLE sync_change_log (
  version     BIGSERIAL PRIMARY KEY,
  change_type VARCHAR(40)  NOT NULL,
  entity_type VARCHAR(20)  NOT NULL,
  entity_id   UUID         NOT NULL,
  chat_id     UUID,
  payload     JSONB        NOT NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_change_log_created ON sync_change_log (created_at);
```

O `GET /api/sync?since=N` lê `WHERE version > N ORDER BY version LIMIT limit+1`.
A escrita no log acontece **na mesma transação** da mutação que a originou.

O enum de `change_type` cobre os 12 tipos do §15 desde o MVP, mesmo que alguns ainda não
sejam emitidos. O cliente tolera e ignora tipos desconhecidos.

O payload de `CHAT_CREATED`/`CHAT_UPDATED` é um **snapshot da conversa**, não um diff. O
Kindle aplica com upsert. Para conversas grandes, o servidor omite `messages` e envia
`messagesOmitted=true` com metadados; a UI busca páginas sob demanda.

## Motivos

- **Monotônico e total**: uma sequência do Postgres é exatamente o "log de alterações" que
  a especificação descreve, sem depender de `updated_at` (que tem colisões, sofre ajuste de
  relógio e some em `DELETE` físico).
- **Simples de testar**: a asserção "sync desde V retorna exatamente as mudanças > V" é
  direta com Testcontainers.
- **Ordenação estável**: `ORDER BY version` dá uma ordem total consistente, inclusive
  quando dois eventos acontecem na mesma transação.
- **Tombstone**: `CHAT_DELETED` é uma linha no log, então um Kindle offline descobre a
  remoção mesmo que ele tenha o dado localmente.
- **Payload como snapshot**: aplica-se com um `INSERT OR REPLACE` idempotente. Diff parcial
  de mensagens exigiria lógica de reconciliação no cliente para um ganho marginal.

## Consequências

**Positivas**: incremental de verdade; reaplicação idempotente; ordem determinística;
detecção de full resync trivial (`since` < mínimo retido); cada entidade tem
`contentVersion` próprio, que resolve conflitos de granularidade fina.

**Negativas**:

- A tabela cresce continuamente. Mitigado por retenção de 30 dias / 1.000.000 de linhas,
  com job de limpeza. Retenção baixa pode forçar full resync em clientes muito antigos —
  aceitável, é raro e barato (o snapshot é paginado).
- Snapshot completo no log custa mais espaço que um diff. Aceitável no volume do MVP
  (dezenas de milhares de mensagens).
- O log **não** é event sourcing: não dá para reconstruir o estado a partir dele, e isso é
  intencional.

## Alternativas rejeitadas

- **`updated_at` como token**: rejeitado — colisões, `DELETE` físico invisível, adjustment
  de relógio, e sem ordenação total.
- **Replicação lógica do Postgres**: tecnicamente superior em escala, mas exige setup,
  slots de replicação e acopla o backend à versão do Postgres. Desproporcional.
- **Diff por entidade (patch JSON)**: rejeitado — mais complexo de aplicar, mais fácil de
  divergir, e sem benefício mensurável no tamanho das conversas do usuário-alvo.
- **WebSocket / push**: rejeitado — o Kindle não deve manter conexão aberta
  ([ADR-002](ADR-002-kindle-as-offline-client.md)); `§41` proíbe tempo real.
