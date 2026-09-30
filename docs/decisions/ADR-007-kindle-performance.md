# ADR-007 — Orçamento de memória e refresh do Kindle

- **Status**: aceito
- **Data**: 2026-09-29

## Contexto

O §24 exige paginação: uma conversa pode ter milhares de mensagens e não pode ser carregada
inteira na memória. O §47 reforça: pouca memória, CPU limitada, tela e-ink, latência de
refresh, sem animações.

## Decisão

1. **Janela de mensagens.** `MESSAGES_PAGE_SIZE = 20`. A tela de leitor mantém no máximo
   `current_index ± 1` janela em cache LRU simples (não há todo o histórico em Lua).
2. **Filtro por índice, não por posição em array.** Toda query de página é
   `WHERE chat_id = ? AND sequence >= ? ORDER BY sequence LIMIT ?`. O índice
   `(chat_id, sequence)` faz isso em O(log n) sem materializar nada.
3. **Lista de chats só carrega metadados.** A tela de biblioteca nunca toca `messages`.
   `message_count` é um inteiro desnormalizado no cliente, evitando `COUNT(*)` por linha.
4. **Markdown é renderizado em chunks**, com limite de 8.000 caracteres por mensagem, para
   uma mensagem patologicamente grande não estourar o heap. Acima do limite, renderiza o
   início e exibe "[mensagem truncada — abrir no servidor]".
5. **Refresh só sob demanda.** Nenhum `UIManager:refresh` em timer, em `onShow` repetido
   ou durante scroll. A tela desenha uma vez por ação do usuário. Isso evita o "flash" de
   e-ink e economiza woken de CPU.
6. **Sem animações.** Nenhuma animação de transição; a tela é redesenhada de uma vez.
7. **Coleta de lixo explícita** após renderizar: as strings de markdown são liberadas
   (`content = nil`) ao trocar de mensagem.

## Consequências

**Positivas**: uso de memória previsível mesmo com conversas de milhares de mensagens;
sem flash de e-ink na navegação; sem CPU ociosa; a UI responde instantaneamente offline.

**Negativas**: trocar de mensagem mostra brevemente um "carregando" quando a página ainda
não está em cache; scroll contínuo é mais lento (limitado à janela) — aceitável, o caso de
uso é ler, não rolar 3000 mensagens de uma vez; mensagens muito longas são truncadas.

**Mitigações**: pré-carrega a próxima janela ao abrir a conversa, de forma assíncrona
(`trampoline.run`), então a navegação seguinte é quase sempre instantânea; o truncamento
tem um limite generoso e é sinalizado visualmente.

## Alternativas rejeitadas

- **Renderizar o chat inteiro em um widget**: rejeitado — OOM em conversas grandes, e
  refresh de e-ink para uma lista enorme.
- **Virtual scroll contínuo**: rejeitado — a matemática de scroll no KOReader para e-ink
  custa mais CPU do que o ganho.
- **Paginação por backend em cada virada de página**: rejeitado — seria rede em leitura,
  violando o offline-first ([ADR-002](ADR-002-kindle-as-offline-client.md)).
