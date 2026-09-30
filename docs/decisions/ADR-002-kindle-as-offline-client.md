# ADR-002 — O Kindle é um cliente offline-first

- **Status**: aceito
- **Data**: 2026-09-29
- **Contexto**: a especificação exige que o Kindle funcione após a sincronização mesmo sem
  rede (Etapa 6), e o §47 define o Kindle como ambiente restrito: pouca memória, CPU
  limitada, e-ink, conectividade intermitente.

## Decisão

O plugin do KOReader é um cliente **offline-first**:

1. **Toda** leitura de tela vem do SQLite local. Nenhuma tela faz requisição HTTP para
   renderizar conteúdo.
2. O SQLite local é um **cache materializado completo** do que já foi sincronizado, não um
   cache parcial (LRU). Depois de um sync, o usuário tem acesso integral, sem rede.
3. O HTTP só é acionado por **ação explícita do usuário** (menu "Sincronizar") ou por uma
   sessão de Wi-Fi configurada. **Nunca há polling.**
4. A busca é sempre local. Mesmo com rede disponível.
5. Escritas locais (favorito, bookmark) são persistidas primeiro no SQLite e enviadas no
   próximo sync.
6. A UI carrega **janelas** de mensagens (`LIMIT/OFFSET`, 20 por vez), nunca a conversa
   inteira em memória.

## Motivos

- No Kindle, cada chamada HTTP custa segundos de latência, um flash de e-ink e consumo
  de bateria. Uma lista de 500 chats com busca remota seria injogável.
- Falha de rede é o estado **normal**, não a exceção. Uma dependência de rede para ler
  transformaria o app em algo que falha todo dia.
- Memória é escassa (ver [ADR-007](../decisions/ADR-007-kindle-performance.md)). SQLite
  em disco + janelas mantém o footprint previsível.
- Busca local funciona offline sem nenhuma tela de "reconectando".

## Consequências

**Positivas**: leitura instantânea; sem flashes de e-ink na navegação; app utilizável
com o airplane mode; bateria preservada; sem dependência de latência de rede na UX.

**Negativas**: dados duplicados (Postgres + SQLite) — o SQLite ocupa espaço no
armazenamento limitado do Kindle; o usuário precisa lembrar de sincronizar; um backend
alterado não chega ao Kindle sem ação manual; zwei Migrationen (backend e cliente)
precisam evoluir juntas.

**Mitigações**:

- A retenção do change log e o `messagesOmitted` limitam o tamanho do sync.
- A tela de Configurações mostra "última sincronização: há 3 dias" e o número de chats
  pendentes.
- O protocolo de sync é versionado e o cliente tolera campos desconhecidos
  ([ADR-003](ADR-003-sync-protocol.md)).
- Um comando `Scripts/sync.lua` permite sincronizar por um cron/OPKG em cenários
  avançados, mas **não** é o caminho padrão.

## Alternativas rejeitadas

- **Online-first com cache de tela**: rejeitado, piora o caso comum (rede ruim).
- **Polling periódico**: rejeitado explicitamente pelo §47 (consumo e flashes).
- **Renderizar o chat como arquivo EPUB e usar o leitor do KOReader**: é o *fallback*
  previsto no §40, útil como modo alternativo de leitura, mas **não** como arquitetura
  principal: não dá busca local, nem favoritos, nem bookmarks, nem delta sync. Ver
  [ADR-008](../decisions/ADR-008-epub-optional.md).
