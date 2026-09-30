# ADR-008 — EPUB é modo alternativo, não arquitetura

- **Status**: aceito
- **Data**: 2026-09-29

## Contexto

O §40 pede para avaliar gerar um EPUB por conversa ("Spring Transactional.epub" com
capítulos por assunto), como *fallback ou modo alternativo de leitura*, explicitamente
**não obrigatório** para o MVP.

## Decisão

O EPUB é um **formato de exportação** (§40) gerado sob demanda a partir do conteúdo
canônico do `Chat`, e não o caminho de leitura principal. Ele é:

- **não** parte do contrato de sync;
- **não** é salvo no SQLite do Kindle;
- é gerado sob demanda por `GET /api/chats/{id}/export?format=epub`, opcionalmente
  paginado, e baixado pelo usuário para o diretório de livros do KOReader;
- capítulos derivados de headings markdown de nível 2/3 dentro das mensagens; na ausência
  de headings, um capítulo por bloco de N mensagens.

## Motivos

- O EPUB resolve um problema **real e diferente**: leitura longa em tela cheia, com o
  leitor nativo do KOReader, fonte ajustável, e sem nada instalado. É o modo ideal para
  "quero só ler agora, sem menus".
- Ele **não** resolve busca, favoritos, bookmarks, delta sync, nem leitura incremental —
  que são os requisitos do §2. Um EPUB de 5 MB baixado por um Kindle com Wi-Fi
  instável, e que envelhece no instante em que a conversa é reimportada, seria uma
  arquitetura pior.
- Manter EPUB fora do sync evita duplicar a mesma conversa em dois mecanismos de cache
  com ciclos de invalidação distintos.

## Consequências

**Positivas**: dois modos de leitura com o esforço mínimo; o EPUB serve de
**interoperabilidade** (levar a conversa para qualquer leitor); o leitor nativo do KOReader
já sabe paginar, zoomar e Dictionary.

**Negativas**: o arquivo envelhece em relação ao backend (precisa regerar); dois
mecanismos de leitura para o usuário escolher; EPUB em e-ink com tabelas/código é
imperfeito.

**Mitigações**: a geração é idempotente e barata (uma consulta); o nome do arquivo inclui o
`contentHash` curto, então o usuário percebe qual versão tem; a tela do Kindle mostra
"Favoritos" e "Buscar" como entradas principais e o EPUB fica em "Configurações →
Exportar".

## Alternativas rejeitadas

- **EPUB como armazenamento primário no Kindle**: rejeitado — não há busca, nem sync
  incremental, nem edição de favorito/bookmark; e cada sync baixaria arquivos inteiros
  (justamente o que o §16 proíbe).
- **Não implementar EPUB**: rejeitado — o §40 pede a avaliação, e a alternativa de modo de
  leitura tem custo baixo e valor real. Implementado como rota de exportação, ~200 linhas.
