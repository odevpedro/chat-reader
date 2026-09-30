# ADR-001 — Modular monolith em vez de microsserviços

- **Status**: aceito
- **Data**: 2026-09-29
- **Contexto**: a especificação exige Spring Boot + PostgreSQL e proíbe explicitamente
  microsserviços, Kubernetes, Kafka e Redis no MVP.

## Decisão

Um único artefato Spring Boot 3.x, organizado em módulos por domínio
(`chat`, `importer`, `tag`, `bookmark`, `search`, `sync`, `security`), cada um com
`domain` / `application` / `infrastructure`. Um único banco PostgreSQL com schema público
e migrations Flyway versionadas.

Módulos **não se referenciam entre si diretamente**. A coordenação acontece em
`application`, contra interfaces de porta definidas pelo módulo *consumidor*. Exemplo:
`sync` depende de uma interface `ChatChangeSource`, implementada por `chat`.

## Motivos

- O sistema tem um usuário, uma linguagem e um banco. Não há necessidade de escala
  horizontal, deployment independente nem consistência eventual entre serviços.
- Sincronização exige **atomicidade** entre a mutação e a escrita no change log. Em um
  monolith isso é uma transação; entre serviços seria saga/outbox pattern.
- Menos partes móveis: um `docker compose up` sobe tudo (§28, §36).
- O isolamento por módulos preserva a possibilidade de extrair um serviço depois, se um dia
  houver motivo concreto.

## Consequências

**Positivas**: simples de rodar, debugar e testar; transação ACID sem sagas; sem
problemas de rede entre componentes; deploy único.

**Negativas**: um ponto único de falha; módulos compartilham processo e memória, então
falha em um impacta todos; scaling é vertical.

**Mitigações**: módulos com fronteiras claras de pacote; regra de dependência
`infrastructure → application → domain` verificada por `ArchUnit`; um teste de
arquitetura impede dependência circular.

## Alternativas rejeitadas

- **Microsserviços**: rejeitados pela própria especificação e por custo desproporcional.
- **Hexagonal por módulo com pacote único**: considered, mas o isolamento por pacote
  oferece o mesmo benefício de organização sem a indireção de um "port" genérico.
- **Event sourcing completo**: rejeitado; sobre-engineering. Change log pontual
  ([ADR-003](ADR-003-sync-protocol.md)) resolve a necessidade de sync.
