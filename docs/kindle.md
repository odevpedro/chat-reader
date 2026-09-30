# Cliente Kindle (KOReader)

> **Status: Etapa 6 concluída** (falta só o passo em dispositivo). O desenho foi conferido contra o código do KOReader
> (verificação R5, commit `b539d24` de 2026-09-29) e `kindle/` está escrito e testado
> com **117 specs** verdes no container (mesmo LuaJIT + ljsqlite3 do dispositivo):
> store SQLite com migração, lógica de sync transacional, outbox, lógica de biblioteca
> e leitor (puras, spec'd), navegação do leitor e adapters HTTP/settings. A UI
> (`main.lua`, biblioteca, leitor, configuração) está escrita. O contrato end-to-end e
> a Etapa 6 offline estão automatizados (`scripts/contract.sh`, `scripts/offline.sh`).
> **Falta a validação em emulador/dispositivo.**

## Princípio: offline-first

O Kindle **nunca** depende de rede para navegar (ADR-002). O backend é a fonte da
verdade do conteúdo (ADR-005), mas o dispositivo mantém uma cópia local em SQLite e
opera sobre ela. A rede serve apenas para sincronizar. Quando ela cai, nada quebra:
o usuário continua lendo, favoritando, criando bookmarks e buscando.

## Estrutura do plugin

```
kindle/
  main.lua              -- WidgetContainer:extend{...} + addToMainMenu, sync, wipe
  client/
    http.lua            -- porta ClientHttp: Turbo + rapidjson, com token e erros
    settings.lua        -- LuaSettings: base da API, usuário, token, validade, client_id
    sync.lua            -- aplica delta/snapshot (contrato em docs/sync-protocol.md)
    library.lua         -- lógica pura da biblioteca/busca (texto de linha, snippets)
    positions.lua       -- última mensagem lida por conversa (LuaSettings, puro)
  store/
    sqlite.lua          -- SQ3 = require("lua-ljsqlite3/init"), em datadir
    memory.lua          -- mesma interface, sem banco (só testes)
  ui/
    library.lua         -- ButtonDialog com as conversas + InputDialog de busca
    reader.lua          -- texto por mensagem, online/offline (TextViewer com markdown)
    settings.lua        -- Menu: URL, usuário, senha, fila de escritas, apagar local
  spec/                 -- testes busted (Lua puro, fora do dispositivo)
  Dockerfile.spec       -- imagem com o mesmo LuaJIT + ljsqlite3 do KOReader
  spec.sh               # roda a suíte no container
```

Para instalar no aparelho, `kindle/` vira `chatreader.koplugin/` no diretório de
plugins: o pluginloader só enxerga pastas terminadas em `.koplugin` (verificado em
`frontend/pluginloader.lua:207`). O `main.lua` devolve a classe com `name` e
`addToMainMenu`; o pluginloader põe o diretório no `package.path`, então
`require("client.sync")` e `require("ui.reader")` resolvem para dentro do plugin.

## Armazenamento local (SQLite)

O schema local espelha o do backend, sem as colunas que só interessam ao servidor.
Os detalhes e invariantes estão em [domain.md](domain.md); em resumo:

| Tabela | Papel |
|--------|-------|
| `chats` | metadados + `sync_token`/`content_version` para reconciliação |
| `messages` | conteúdo das mensagens, `sequence` 0..N-1 |
| `favorites` | flag bidirecional (LWW) |
| `bookmarks` | posição de leitura por mensagem |
| `sync_state` | último token aplicado e metadados da última sync |
| `outbox` | mudanças locais ainda não enviadas ao backend (Etapa 6) |

Migrações são numeradas e aplicadas de forma idempotente na abertura do plugin.

## Cliente HTTP

O cliente de rede é desenhado como **porta** (`client/http.lua`) para que a lógica de
sync possa ser testada sem KOReader nem rede:

- `get_json(path)`, `post_json(path, body)`, `is_online()`.
- O adapter real usa o cliente que o KOReader empacotar (verificação R5).
- Um adapter de teste lê fixtures locais — é o mesmo código que roda em produção,
  apenas com outra implementação de transporte.

## Sincronização

O contrato completo (endpoints, token, conflitos, resiliência) está em
[sync-protocol.md](sync-protocol.md). No dispositivo:

1. `GET /api/sync?since=<token>` devolve mudanças incrementais;
2. aplica em transação no SQLite;
3. guarda o novo token em `sync_state`;
4. mudanças locais (favoritos/bookmarks) vão para `outbox` e são enviadas quando há
   rede, com o token confirmado via `POST /api/sync/ack`.

Reconciliação por `content_version` (**last-write-wins**): o backend vence para o
conteúdo; o dispositivo vence para o que é local (posição, bookmarks).

## Desempenho em e-ink (ADR-007)

- **Um único repaint por ação**; sem animação, sem polling, sem *flash*.
- Renderer de Markdown simples: cabeçalhos, listas, negrito/itálico e blocos de
  código com *quebra de linha* em vez de rolagem horizontal.
- Paginação por mensagem já renderizada; evitar re-render de páginas anteriores.
- Memória contida: nunca carregar todas as mensagens de todas as conversas de uma vez.

## Verificação de APIs (R5 — feito, commit `b539d24` de 2026-09-29)

Nada de API inventada: a tabela abaixo foi conferida **no código do KOReader**
(`koreader/koreader@b539d24`, pasta `frontend/` e `plugins/`). A coluna "arquivo" é a
prova; se a versão-alvo do Kindle diferir, reverificar antes de codar.

| Necessidade | API **real** | Como usar | Arquivo |
|-------------|--------------|-----------|---------|
| Entrada do plugin no menu | `Widget:extend{...}` + `addToMainMenu(menu_items)` | o loader lê `main.lua`, mostra os plugins em *Ferramentas* | `plugins/statistics.koplugin/main.lua:1049`, `frontend/pluginloader.lua:294` |
| Telas/listas | `Menu:new{ title = ..., item_table = {...} }` | **`MenuWidget` não existe mais**; a classe em `ui/widget/menu.lua` chama-se `Menu` | `frontend/ui/widget/menu.lua:597` |
| Abrir/fechar tela | `UIManager:show(widget)`, `UIManager:close(self)` | Widgets herdam de `Widget:extend` | `frontend/ui/uimanager.lua:229,290` |
| Aviso ao usuário | `UIManager:show(InfoMessage:new{ text = ..., timeout = 3 })` | **`UIManager:showInfo` não existe** | `plugins/statistics.koplugin/main.lua:309` |
| Confirmação | `UIManager:show(ConfirmBox:new{...})` | **`showConfirm` não existe** | `plugins/statistics.koplugin/main.lua:293` |
| Preferências | `LuaSettings:open(path)`, `:readSetting(k, default)`, `:saveSetting`, `:close()` | caminho em `datastorage` | `frontend/luasettings.lua:21,89,102,278` |
| Diretório de dados | `require("datastorage")` → `:getDataDir()`, `:getSettingsDir()` | banco e settings do plugin | `plugins/statistics.koplugin/main.lua:29-30` |
| SQLite | `require("lua-ljsqlite3/init")` → `SQ3.open(path)`; `conn:exec(sql)`, `conn:rowexec(sql)`, `conn:prepare`, `conn:close()` | **é `ljsqlite3`, não `luasql` e não FFI cru**; `exec` aceita vários `;` e indexa o resultado por nome/número de coluna | `doc/DataStore.md`, `frontend/cachesqlite.lua:82` |
| Transação | `conn:exec('BEGIN;')` … `conn:exec('COMMIT;')` | o delta do sync é aplicado inteiro ou nada | `plugins/statistics.koplugin/main.lua:624,653` |
| Migrações | padrão `statistics`: `DB_SCHEMA_VERSION` + backup antes de migrar | espelha o Flyway do backend em espírito | `plugins/statistics.koplugin/main.lua:319-332` |
| HTTP | `require("httpclient"):new():request({url, method, body, headers, on_headers}, cb)` | **assíncrono via turbo**, roda em corrotina; `socketutil` é o cliente síncrono legado | `frontend/httpclient.lua`, `plugins/kosync.koplugin/KOSyncClient.lua:44` |
| JSON | `require("rapidjson")` → `.decode(s)`, `.encode(t)`, `.array()` | bundled; não há `cjson`/`dkjson` | `plugins/calibre.koplugin/wireless.lua:22` |
| Conectividade | `require("ui/network/manager")` → `NetworkMgr:isConnected()`, `:isWifiOn()`, `:runWhenOnline(cb)` | o módulo é `ui/network/manager`, **não** `frontend/networkmgr.lua` | `frontend/ui/network/manager.lua:182,187` |

Conclusões que mudam o desenho original:

- **`PluginManager:new{ file = "main.lua" }` não existe** no KOReader atual. O plugin é um
  `Widget:extend` que expõe `addToMainMenu`; o `PluginLoader` cuida do resto.
- **`Storage:sqlite` não existe.** O mais próximo é `cachesqlite.lua`, que é um cache
  genérico com cota de tamanho — serve para *cache*, não para o banco do plugin, que
  precisa de consultas e transações próprias.
- O HTTP do KOReader é assíncrono e orientado a corrotina. Por isso `client/http.lua`
  é uma **porta** com callbacks, não uma chamada bloqueante: é o que permite testar
  `client/sync.lua` com busted, fora do dispositivo, sem rede.
- Nenhuma limitação bloqueante: SQLite, JSON, HTTP e preferências existem com nomes
  diferentes dos inicialmente previstos. Não há adaptadores de teste "por falta de API";
  eles existem por testabilidade.

### Armadilhas do binding (encontradas ao escrever o store, não antes)

Estas só apareceram ao exercitar o `ljsqlite3` de verdade; estão aqui para ninguém
redescobri-las:

| Armadilha | O que acontece | O que o plugin faz |
|-----------|-----------------|--------------------|
| `resultset` devolve **colunas**, não linhas | `res[1][i]` = coluna 1, linha `i` (é assim que o `statistics.koplugin` consome) | `store/sqlite.lua` converte para lista de linhas com chave pelo nome da coluna, que é o formato que a interface do store promete |
| `bind(nil)` vira `NULL` e **chave de texto aceita NULL** | um payload sem `id` viraria uma linha invisível em vez de erro | `put_chat`/`put_tag`/`put_bookmark` recusam entidade sem `id` |
| `bind` só aceita string/número | `true` e tabelas levantam `unexpected Lua type` | booleanos são convertidos para `0`/`1` antes de gravar |
| Concatenar SQL quebra com apóstrofo | mensagem com `'a'` corromperia o INSERT | todo valor de rede entra por prepared statement (`?`) |
| A resposta do Turbo pode chegar **antes** da espera | no adapter ingênuo, `coroutine.resume(nil)` | `client/http.lua` só retoma a corrotina se ela já existir, e pula o `yield` quando a resposta já está lá |
| `busted` do Debian usa `lua5.1` | `require("ffi")` falha, e o ljsqlite3 é FFI | a suíte roda com `luajit` (ver "Como testar") |
| `list_chats` do SQLite devolvia a linha crua | `message_count`/`updated_at` viravam `nil` e a biblioteca mostrava "sem mensagens" em toda linha; pior, `favorite = 0` virava **favorita**, porque em Lua `0` é verdade | `store/sqlite.lua` traduz a linha para o vocabulário do domínio, e `store/memory.lua` normaliza o mesmo jeito; `spec/library_query_spec.lua` compara os dois stores e falha se divergirem |
| `nil` como parâmetro bind no ljsqlite3 | `ljsqlite3[range] column index out of range` | `list_chats` só monta `LIMIT ? OFFSET ?` quando há `limit`; sem ele, a query vai sem binds |

## Como testar

### Lógica pura (Lua, sem dispositivo)

```bash
./kindle/spec.sh                  # suíte inteira
./kindle/spec.sh spec/sync_spec.lua
```

O script sobe uma imagem com **o mesmo par LuaJIT + ljsqlite3 que o KOReader embarca**
e monta `kindle/` em `/plugin`. Não é uma aproximação: o SQL exercitado nos specs é o
mesmo que roda no Kindle. Detalhes que a imagem precisou resolver (todos verificados
contra o código do KOReader e do binding, não de memória):

- `busted` do Debian roda com `lua5.1`, e o ljsqlite3 precisa de FFI → a suíte é
  executada com `luajit` sobre o script do rock;
- o ljsqlite3 declara dependência de `xsys` (rock) e carrega o sqlite por
  `ffi.load("sqlite3")` → precisa do symlink `libsqlite3.so` e do `ldconfig`;
- o busted registra cada arquivo de spec com `envmode = "insulate"`
  (`busted/init.lua`), o que restaura `package.loaded` entre arquivos. Módulo com FFI
  seria executado de novo no arquivo seguinte e quebraria no segundo `ffi.cdef`; por
  isso o script roda **um busted por arquivo**, que é a forma honesta de isolar.

Estado atual: **117 specs** cobrindo store SQLite (schema, migração, CRUD, tags,
bookmarks, transações), escrita offline e outbox, `client/sync.lua` (delta, paginação,
snapshot, ack, token que não avança quando a gravação falha), a lógica pura de
biblioteca e leitor (`client/library.lua`, `client/positions.lua`, navegação de
`ui/reader.lua`), os adapters de HTTP e settings com `httpclient`/`rapidjson`/
`luasettings` stubados, e checagem de que todos os arquivos do plugin compilam no
mesmo LuaJIT do dispositivo (`spec/syntax_spec.lua`, que pega typos que só o device
veria). Como `ui/library.lua`, `ui/settings.lua` e `main.lua` precisam dos widgets do
KOReader, elas só passam por `loadfile` (sintaxe); a lógica que merecia teste vive
fora delas.

### Contrato e offline contra o backend real

Duas verificações que só um servidor de verdade prova, e que por isso ficam fora do
container da suíte:

```bash
./kindle/scripts/contract.sh        # contrato de sync ponta a ponta
./kindle/scripts/offline.sh         # Etapa 6: derrubar o backend e usar o app sem rede
```

Ambas sobem um **backend descartável** na porta 8082/8081, reaproveitando o seu
Postgres e sem tocar no seu stack em `:8080` (`scripts/disposable_backend.sh`). O
motivo de serem descartáveis: o `.env` guarda só o *hash* da senha, e o teste precisa
de login em texto claro; pedir a sua senha de verdade tornaria o teste impraticável.
Se preferir apontar para o seu backend: `./kindle/scripts/contract.sh USUARIO SENHA`.

**`contract.sh`** (`scripts/contract_test.lua`) faz login, sync de abertura por
snapshot, escreve favorito e bookmark pelo caminho *offline*, roda um segundo ciclo
que sobe o ACK e só então avança o token, e um terceiro ciclo que confirma delta
vazio. Sai 0 com `contrato ok`.

> O `id` do bookmark é o **`id` da mensagem**, não um UUID novo: `bookmarks.id` é
> `VARCHAR(36)` no Postgres e o plugin tem que conseguir ler de volta o que gravou.

**`offline.sh`** roda três fases em containers separados sobre o mesmo banco em disco:

| Fase | O que prova |
|------|-------------|
| `online` | login, snapshot, um favorito e um bookmark que sobem e esvaziam a fila |
| `offline` | backend **derrubado**: biblioteca, favoritos, navegação paginada, busca local, favorito/bookmark intactos, sync falha sem corromper o banco, escrita offline continua enfileirando, token local não anda |
| `drenar` | backend volta: a fila sobe, o ACK confirma e a fila esvazia |

O estado entre as fases (qual conversa foi favoritada, qual mensagem foi bookmarkada)
vai num arquivo `.marker` ao lado do banco. Sem ele a fase offline conferiria o `id`
errado e o teste passaria por acaso.

Duas coisas que a fase offline faz de propósito, porque são as que a tornam um teste:

- o backend é **derrubado de verdade**, e o script falha se a porta ainda responder —
  um `401` provaria que o servidor estava de pé e o teste não diria nada sobre offline;
- o cliente exige falha de **conexão** (`code == 0`), não qualquer erro HTTP.

### A UI, executada com stubs do KOReader

`spec/ui_spec.lua` carrega `main.lua` e as três telas com stubs dos módulos do KOReader
(`spec/koreader_stub.lua`) e **executa** o caminho que o pluginloader executa no
aparelho: registra no menu, abre a biblioteca, monta o `TextViewer`, aperta Favoritar e
Bookmark, vira página, abre as configurações.

O que isso compra: um `require` de módulo inexistente, um campo errado num
`ButtonDialog:new` ou um callback que fecha um widget já fechado aparecem em
milissegundos no container, com stack trace — em vez de tela branca no Kindle, sem
nenhum log. Os campos aceitos por cada widget foram conferidos contra
`frontend/ui/widget/*.lua` do upstream (`InputDialog` usa `buttons`, `TextViewer` usa
`buttons_table` + `add_default_buttons`, `ConfirmBox` usa `ok_text`/`ok_callback`).

O que não compra: e-ink de verdade — contraste, refresh, gestos de página, teclado.

### Em dispositivo

1. **Teste no aparelho** — passo a passo em
   [instalar-no-kindle.md](instalar-no-kindle.md). Este é o único item que falta e
   ele **não** pode ser substituído por nenhum dos testes acima.

`./kindle/scripts/package.sh` monta o `chatreader.koplugin` e faz duas conferências
que valem mais do que parecem: os `require` do `main.lua` contra o **conteúdo do
pacote** (um require quebrado passa em todo spec, que roda do repo), e o carregamento
do pacote **de fora do repo**, num container separado. O `spec/` e o `scripts/` não
vão para o aparelho.

Não há renderer de Markdown próprio: o leitor usa o `TextViewer` do KOReader com
`text_format = "md"`, que já reutiliza o mdToHtml + crengine usado para abrir .md —
renderer próprio seria uma reimplementação pior e não testável fora do aparelho.

## Escritas offline (outbox)

Favoritar e bookmarkar funcionam sem rede: o efeito aparece na hora no SQLite e a
subida fica numa fila (`outbox`), que o ACK envia no fim de cada ciclo.

- `store/sqlite.lua` tem **dois caminhos** de propósito: `put_chat`/`put_bookmark`/
  `set_favorite` aplicam estado que **veio do servidor** e nunca enfileiram nada; os
  `*_offline` aplicam **e** enfileiram, tudo em uma transação. Se o sync usasse o
  caminho offline, cada mudança remota voltaria para a fila e o ACK reenviaria para
  sempre.
- A fila é uma tabela com colunas, não JSON: o store não deve depender do `rapidjson`,
  que é módulo nativo e não existe no container de teste. `pending_local` devolve as
  linhas no vocabulário do payload (`chatId`, `refId`, `messageId`), que é o mesmo nos
  dois stores.
- Limite de 50 itens por ACK; o que sobra vai no ciclo seguinte.
- Item recusado **fica na fila** com o motivo, para a tela avisar (protocolo §5).

## Critério de pronto (Etapa 5/6)

- [x] store SQLite com migração versionada e transações (`store/sqlite.lua`);
- [x] lógica de sync (delta, snapshot paginado, ack, token transacional)
      (`client/sync.lua`);
- [x] escritas offline com outbox e ACK com rejeições (`store/sqlite.lua`,
      `client/sync.lua`);
- [x] adapter HTTP e settings com as APIs reais do KOReader (`client/http.lua`,
      `client/settings.lua`);
- [x] lógica pura da biblioteca, busca e posição de leitura spec'd
      (`client/library.lua`, `client/positions.lua`);
- [x] UI escrita: `main.lua` (menu, sync, wipe), biblioteca, leitor offline-first,
      configuração (`ui/*.lua`);
- [x] contrato end-to-end contra o backend real (`scripts/contract.sh` → `contrato ok`);
- [x] nenhuma tela depende de rede (`scripts/offline.sh` → `offline ok` nas 3 fases);
- [ ] plugin carrega no KOReader e o fluxo real roda (emulador/dispositivo).
