# ADR-005 — Backend é a fonte da verdade do conteúdo

- **Status**: aceito
- **Data**: 2026-09-29
- **Contexto**: o §17 exige uma estratégia de conflitos explícita e a exige documentada.
  O Kindle pode ser acessado por uma pessoa só, mas em dispositivos diferentes (Kindle em
  casa, Kindle na viagem), e o relógio do Kindle é pouco confiável.

## Decisão

Divisão explícita de responsabilidade:

| Dado                                   | Source of truth | Kindle pode escrever? |
|----------------------------------------|-----------------|-----------------------|
| Título, mensagens, sequência, ordem    | **Backend**     | Não                  |
| Tags / categorias                      | **Backend**     | Não                  |
| `favorite`                             | Ambos, **LWW**  | Sim                  |
| Bookmark (criar/remover)               | **Backend** com união | Sim             |
| `lastReadMessage`                      | **Kindle**      | Sim (local, não sincronizado) |
| `lastOpenedAt`                         | **Kindle**      | Sim (local)           |
| `importedAt`, `contentHash`            | **Backend**     | Não                  |

Regras:

1. **O Kindle nunca escreve conteúdo.** Não existe endpoint de escrita de mensagem no MVP.
   Se um cliente enviar, o servidor responde `409 CONFLICT`.
2. **`favorite`**: last-writer-wins por `updatedAt` (UTC). Em empate exato de timestamp,
   o **backend vence** (determinístico, sem depender do relógio do cliente).
3. **Bookmark**: modelo de **união com tombstones**, não LWW. Dois dispositivos marcando
   mensagens diferentes produzem dois bookmarks. Remoção nunca é física enquanto outro
   dispositivo puder reenviar.
4. **Estado de leitura é estritamente local.** Não faz parte do contrato de sync. Abrir uma
   conversa no Kindle da viagem não deve marcar como lida no Kindle de casa.
5. **Toda transição** de um dado bidirecional gera uma linha no change log
   ([ADR-003](ADR-003-sync-protocol.md)), garantindo convergência.
6. **O cliente envia `updatedAt`**, mas o servidor **nunca** usa timestamp do cliente para
   decidir ordem ou frescor — apenas para desempatar LWW.

## Motivos

- O conteúdo vem de um **arquivo de export** que o usuário controla. A única fonte de
  verdade dele é o arquivo; o backend é o lugar onde todos os arquivos convergem.
- Escrita no Kindle exigiria resolver merge de texto — problema caro e sem valor no MVP
  (que explicitamente não implementa responder mensagens).
- O relógio do KindleAdvanced costuma estar errado por minutos ou horas. Usá-lo para
  decidir "quem é mais novo" introduziria flakiness e perda de dados silenciosa.
- Estado de leitura local é o comportamento que o usuário espera de um livro físico: você
  não "deslê" o Kindle de casa ao ler no da viagem.

## Consequências

**Positivas**: sem merge de texto, sem conflito perdido; converged simples; idempotência
fácil; o backend pode reimportar o export e sobrescrever com a "verdade" do arquivo; o
Kindle é simples e pode ser descartado e recriado sem perda de nada além de estado de
leitura.

**Negativas**:

- Sem edição offline no Kindle (aceito: fora do escopo do MVP).
- Dois dispositivos podem divergir em `favorite` por alguns segundos até o próximo sync —
  resolvido por converge no próximo ciclo.
- LWW depende de `updatedAt` correto dos dois lados; um relógio adiantado indefinidamente
  sobrescreveria o estado real. Mitigação: o servidor **desconta** o desvio — ao receber um
  `updatedAt` no futuro, ele satura no horário do servidor com um aviso `CLOCK_SKEW` no
  log, e o desempate final usa um `seq` de dispositivo gerado pelo servidor.

## Alternativas rejeitadas

- **Backend e Kindle cada um editando texto com merge**: rejeitado — merge de Markdown é
  um problema de-research, sem benefício no MVP.
- **CRDT**: rejeitado — desproporcional.
- **Servidor decide tudo, Kindle só espelha (inclusive leitura)**: rejeitado — forçar
  sincronização de posição de leitura entre dispositivos é UX ruim, sem ganho.
- **Timestamps apenas do servidor**: rejeitado — quando o Kindle está offline e alterna
  `favorite`, o servidor não tem como saber a ordem real sem um marcador do cliente.
  LWW com `updatedAt` do cliente + desempate determinístico é a solução mais simples que
  converge.
