-- Store em SQLite: a mesma interface que store/memory.lua, com o binding que o
-- KOReader embarca (require("lua-ljsqlite3/init"), doc/DataStore.md).
--
-- Este arquivo e a unica dependencia de KOReader em `store/`, de proposito: o resto do
-- plugin fala com a interface, nao com o banco. Os testes rodam no container com o
-- mesmo ljsqlite3, entao o SQL exercitado aqui e o que roda no Kindle.
--
-- Todo valor que vem da rede entra por prepared statement (`?`). Mensagem com
-- apostro e o caso comum, nao a excecao: concatenar SQL quebra.

local SQ3 = require("lua-ljsqlite3/init")

local M = {}

-- Versao do schema local; o store migra sozinho na abertura, como o statistics faz
-- no KOReader (plugins/statistics.koplugin/main.lua).
M.SCHEMA_VERSION = 2

local MIGRATIONS = {
    [1] = [[
        CREATE TABLE IF NOT EXISTS chats(
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            source TEXT,
            external_id TEXT,
            content_version INTEGER,
            favorite INTEGER NOT NULL DEFAULT 0,
            message_count INTEGER NOT NULL DEFAULT 0,
            updated_at TEXT
        );
        CREATE TABLE IF NOT EXISTS messages(
            id TEXT PRIMARY KEY,
            chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
            role TEXT NOT NULL,
            seq INTEGER NOT NULL,
            content TEXT NOT NULL,
            content_hash TEXT,
            created_at TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_messages_chat_seq ON messages(chat_id, seq);
        CREATE TABLE IF NOT EXISTS tags(
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS chat_tags(
            chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
            tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
            PRIMARY KEY(chat_id, tag_id)
        );
        CREATE TABLE IF NOT EXISTS bookmarks(
            id TEXT PRIMARY KEY,
            chat_id TEXT NOT NULL,
            message_id TEXT,
            note TEXT,
            created_at TEXT
        );
        CREATE TABLE IF NOT EXISTS sync_state(
            key TEXT PRIMARY KEY,
            value TEXT
        );
    ]],
    -- Fila de escritas feitas offline (docs/sync-protocol.md, secao 5 e 6). O payload
    -- fica em colunas, e nao em JSON: o store nao deve depender do rapidjson, que e
    -- modulo nativo do KOReader e nao existe no container de teste.
    [2] = [[
        CREATE TABLE IF NOT EXISTS outbox(
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            chat_id TEXT,
            ref_id TEXT,
            message_id TEXT,
            note TEXT,
            favorite INTEGER,
            created_at TEXT
        );
    ]],
}

local Store = {}
Store.__index = Store

-- @param path caminho do arquivo em datadir; ":memory:" nos testes
function M.open(path)
    local store = setmetatable({ path = path }, Store)
    store.conn = SQ3.open(path)
    store:exec("PRAGMA foreign_keys = ON;")
    store:migrate()
    return store
end

-- ---------------------------------------------------------------------------
-- SQL: prepared statements para tudo que tem valor vindo de fora
-- ---------------------------------------------------------------------------

function Store:exec(sql)
    return self.conn:exec(sql)
end

function Store:write(sql, ...)
    local stmt = self.conn:prepare(sql)
    stmt:reset()
    if select("#", ...) > 0 then stmt:bind(...) end
    stmt:step()
    stmt:close()
end

-- ljsqlite3 devolve o resultado por COLUNA, nao por linha: resultset("hik") retorna
-- uma tabela res[1][i] = valor da coluna 1 na linha i (e res[0] com os nomes). E o
-- que o statistics.koplugin consome. Aqui convertemos para lista de linhas com chave
-- pelo nome da coluna, que e o formato que a interface do store promete.
local function to_rows(res)
    local rows = {}
    if not res then return rows end
    local header = res[0]
    local total = 0
    for i = 1, #header do
        local column = res[header[i]]
        if column and #column > total then total = #column end
    end
    for n = 1, total do
        local row = {}
        for i = 1, #header do
            local column = res[header[i]]
            row[header[i]] = column and column[n] or nil
        end
        rows[n] = row
    end
    return rows
end

function Store:read(sql, ...)
    local stmt = self.conn:prepare(sql)
    stmt:reset()
    if select("#", ...) > 0 then stmt:bind(...) end
    local res = stmt:resultset("hik")
    stmt:close()
    return to_rows(res)
end

function Store:read_one(sql, ...)
    return self:read(sql, ...)[1]
end

-- ---------------------------------------------------------------------------
-- Migracoes
-- ---------------------------------------------------------------------------

-- Uma migracao por vez, cada uma em transacao propria: um crash no meio deixa a
-- base na versao anterior, nunca num estado meio aplicado.
function Store:migrate()
    local current = self:schema_version()
    if current > M.SCHEMA_VERSION then
        error("Banco com schema " .. current .. ", plugin espera " .. M.SCHEMA_VERSION)
    end
    for version = current + 1, M.SCHEMA_VERSION do
        self:exec("BEGIN;")
        local ok, err = pcall(self.exec, self, MIGRATIONS[version])
        if ok then
            self:write("INSERT OR REPLACE INTO sync_state(key, value) VALUES(?, ?);",
                "schema_version", tostring(version))
            self:exec("COMMIT;")
        else
            self:exec("ROLLBACK;")
            error("Falha na migracao " .. version .. ": " .. tostring(err))
        end
    end
end

function Store:schema_version()
    local exists = self:read_one(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'sync_state';")
    if not exists then return 0 end
    local row = self:read_one("SELECT value FROM sync_state WHERE key = 'schema_version';")
    return tonumber(row and row.value) or 0
end

function Store:close()
    if self.conn then self.conn:close() end
end

-- Uma transacao por chamada. O store nao aninha: quem chama (client/sync.lua) ja
-- agrupa o que precisa ser atomico, e transacoes aninhadas no SQLite viram SAVEPOINT,
-- o que mascara erro de "commit duplo".
function Store:transaction(fn)
    if self.in_transaction then
        error("transaction aninhada: o agrupamento e do chamador")
    end
    self.in_transaction = true
    self:exec("BEGIN;")
    local ok, result = pcall(fn)
    self.in_transaction = false
    if ok then
        self:exec("COMMIT;")
    else
        self:exec("ROLLBACK;")
        error(result)
    end
    return result
end

-- ---------------------------------------------------------------------------
-- Aplicacao de mudancas (usada por client/sync.lua)
-- ---------------------------------------------------------------------------

-- Toda entidade entra por id, e id e a chave de conflito do LWW (ADR-005). Sem id
-- nao ha o que atualizar: o SQLite aceitaria uma linha com id NULL (chave de texto
-- permite) e ela viraria lixo invisivel nas consultas. Payload malformado falha aqui.
local function require_id(entity, what)
    local id = entity.id
    if type(id) ~= "string" or id == "" then
        error(what .. " sem id: " .. type(id))
    end
    return id
end

function Store:put_chat(chat)
    local id = require_id(chat, "put_chat")
    self:write([[
        INSERT INTO chats(id, title, source, external_id, content_version, favorite,
                          message_count, updated_at)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            source = excluded.source,
            external_id = excluded.external_id,
            content_version = excluded.content_version,
            favorite = excluded.favorite,
            message_count = excluded.message_count,
            updated_at = excluded.updated_at;
    ]],
        id, chat.title or "", chat.source, chat.externalId,
        chat.contentVersion or 0, chat.favorite and 1 or 0,
        chat.messageCount or 0, chat.updatedAt)

    -- Tags: o backend manda o catalogo junto com a conversa. O conjunto e o estado
    -- (LWW, ADR-005), entao troca tudo em vez de acumular.
    self:replace_tags(id, chat.tags or {})

    -- Mensagens so sao trocadas quando o backend mandou (messagesOmitted = false);
    -- conversa grande mantem o que ja esta no banco.
    if chat.messages and not chat.messagesOmitted then
        self:replace_messages(id, chat.messages)
    end
end

function Store:replace_tags(chat_id, tags)
    self:write("DELETE FROM chat_tags WHERE chat_id = ?;", chat_id)
    for _, tag in ipairs(tags) do
        self:put_tag(tag)
        self:write("INSERT OR IGNORE INTO chat_tags(chat_id, tag_id) VALUES(?, ?);",
            chat_id, tag.id)
    end
end

-- Aceita `sequence` (nome do JSON da API) e `seq` (nome da linha que list_messages
-- devolve): quem escreve no store costuma estar lendo dele, e o store em memoria
-- aceita os dois. Divergir aqui faria a UI funcionar em um store e nao no outro.
function Store:replace_messages(chat_id, messages)
    self:write("DELETE FROM messages WHERE chat_id = ?;", chat_id)
    for _, message in ipairs(messages) do
        self:write([[
            INSERT OR REPLACE INTO messages(id, chat_id, role, seq, content,
                                            content_hash, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?);
        ]],
            require_id(message, "put_message"), chat_id, message.role,
            message.sequence or message.seq or 0, message.content or "",
            message.contentHash, message.createdAt)
    end
end

function Store:put_tag(tag)
    self:write([[
        INSERT INTO tags(id, name) VALUES(?, ?)
        ON CONFLICT(id) DO UPDATE SET name = excluded.name;
    ]], require_id(tag, "put_tag"), tag.name)
end

function Store:put_bookmark(bookmark)
    local id = require_id(bookmark, "put_bookmark")
    self:write([[
        INSERT INTO bookmarks(id, chat_id, message_id, note, created_at)
        VALUES(?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            chat_id = excluded.chat_id,
            message_id = excluded.message_id,
            note = excluded.note,
            created_at = excluded.created_at;
    ]], id, bookmark.chatId, bookmark.messageId, bookmark.note, bookmark.createdAt)
end

function Store:delete_bookmark(id)
    self:write("DELETE FROM bookmarks WHERE id = ?;", id)
end

-- Conversa removida: some da biblioteca junto com seus bookmarks e mensagens.
-- O backend mantem o tombstone; localmente basta apagar.
function Store:delete_chat(id)
    self:write("DELETE FROM bookmarks WHERE chat_id = ?;", id)
    self:write("DELETE FROM messages WHERE chat_id = ?;", id)
    self:write("DELETE FROM chats WHERE id = ?;", id)
end

function Store:clear()
    self:exec([[
        DELETE FROM bookmarks; DELETE FROM chat_tags; DELETE FROM messages;
        DELETE FROM chats; DELETE FROM tags;
    ]])
end

-- ---------------------------------------------------------------------------
-- Token de sync
-- ---------------------------------------------------------------------------

function Store:get_token()
    local row = self:read_one("SELECT value FROM sync_state WHERE key = 'sync_token';")
    return tonumber(row and row.value) or 0
end

function Store:save_token(token)
    self:write("INSERT OR REPLACE INTO sync_state(key, value) VALUES('sync_token', ?);",
        tostring(token))
end

-- ---------------------------------------------------------------------------
-- Escritas offline: aplicam localmente E enfileiram
--
-- Sao dois caminhos de proposito:
--   * put_chat/put_bookmark/set_favorite  -> estado que VEIO do servidor (sync)
--   * *_offline                            -> acao do usuario, que ainda vai subir
-- Se o sync usasse o caminho offline, cada mudanca remota voltaria para a fila e o
-- ACK reenviaria para sempre. Por isso os metodos de sync nunca enfileiram nada.
-- ---------------------------------------------------------------------------

function Store:enqueue_local(type, entry)
    self:write([[
        INSERT INTO outbox(type, chat_id, ref_id, message_id, note, favorite, created_at)
        VALUES(?, ?, ?, ?, ?, ?, ?);
    ]], type, entry.chatId, entry.refId, entry.messageId, entry.note,
        entry.favorite == nil and nil or (entry.favorite and 1 or 0),
        os.date("!%Y-%m-%dT%H:%M:%SZ"))
end

-- Fila em ordem de insercao. O backend aplica item a item e nao reordena, entao a
-- ordem importa: um favorito seguido da delecao do mesmo bookmark tem de sair nessa
-- ordem para nao deixar o favorito valendo.
--
-- As linhas voltam no mesmo formato do store em memoria (chatId, refId, messageId),
-- que e o nome que a API usa no payload. Assim o sync nao depende de qual store esta
-- embaixo, e o store nao vazia o vocabulario do banco para fora.
function Store:pending_local(limit)
    local out = {}
    for _, row in ipairs(self:read("SELECT seq, type, chat_id, ref_id, message_id, note,"
        .. " favorite FROM outbox ORDER BY seq LIMIT ?;", limit or 100)) do
        out[#out + 1] = {
            seq = row.seq, type = row.type, chatId = row.chat_id, refId = row.ref_id,
            messageId = row.message_id, note = row.note,
            favorite = row.favorite ~= nil and (row.favorite == 1) or nil,
        }
    end
    return out
end

function Store:drop_local(seqs)
    for _, seq in ipairs(seqs or {}) do
        self:write("DELETE FROM outbox WHERE seq = ?;", seq)
    end
end

function Store:count_local()
    local row = self:read_one("SELECT COUNT(*) AS total FROM outbox;")
    return tonumber(row and row.total) or 0
end

-- Acao do usuario: o efeito aparece na hora (offline-first, ADR-002) e a subida
-- acontece no proximo ACK. Tudo em uma transacao: ou os dois acontecem, ou nenhum.
function Store:set_favorite_offline(chat_id, favorite)
    self:transaction(function()
        self:set_favorite(chat_id, favorite)
        self:enqueue_local("CHAT_FAVORITE", { chatId = chat_id, favorite = favorite })
    end)
end

function Store:bookmark_offline(bookmark)
    local id = require_id(bookmark, "bookmark_offline")
    self:transaction(function()
        self:put_bookmark(bookmark)
        self:enqueue_local("BOOKMARK_CREATED", {
            refId = id, chatId = bookmark.chatId,
            messageId = bookmark.messageId, note = bookmark.note,
        })
    end)
end

function Store:unbookmark_offline(id)
    self:transaction(function()
        self:delete_bookmark(id)
        self:enqueue_local("BOOKMARK_DELETED", { refId = id })
    end)
end

-- ---------------------------------------------------------------------------
-- Consultas para a interface
-- ---------------------------------------------------------------------------

local COUNTS = { chats = "chats", messages = "messages", tags = "tags",
                 bookmarks = "bookmarks" }

function Store:count(what)
    local table_name = COUNTS[what]
    if not table_name then return 0 end
    local row = self:read_one("SELECT COUNT(*) AS total FROM " .. table_name .. ";")
    return tonumber(row and row.total) or 0
end

local CHAT_COLUMNS = "id, title, favorite, message_count, updated_at"

-- A linha do SQL usa snake_case, mas quem consome o store (library.lua, ui/) fala o
-- vocabulario do dominio — o mesmo que store/memory.lua devolve. Sem esta traducao a
-- biblioteca mostra "sem mensagens" em toda linha e, pior, marca TODA conversa como
-- favorita: em Lua 0 e' verdade, entao o favorite = 0 do banco viraria true.
local function chat_from_row(row)
    return { id = row.id, title = row.title, favorite = row.favorite == 1,
             messageCount = row.message_count, updatedAt = row.updated_at }
end

function Store:get_chat(id)
    local row = self:read_one("SELECT " .. CHAT_COLUMNS .. " FROM chats WHERE id = ?;", id)
    if not row then return nil end
    return chat_from_row(row)
end

-- So os ids: o snapshot precisa saber o que ficou de fora, e nao vale carregar
-- titulo e contadores de toda a biblioteca para isso.
function Store:chat_ids()
    local ids = {}
    for _, row in ipairs(self:read("SELECT id FROM chats;")) do
        ids[#ids + 1] = row.id
    end
    return ids
end

-- A interface filtra por favoritos sem carregar a biblioteca inteira; a ordem e a
-- mesma nos dois stores (updated_at desc, title) porque e o que o olho ve.
function Store:list_chats(limit, offset, only_favorites)
    local sql = "SELECT " .. CHAT_COLUMNS .. " FROM chats"
    if only_favorites then sql = sql .. " WHERE favorite = 1" end
    sql = sql .. " ORDER BY updated_at DESC, title"
    -- sem limit nao ha bind: passar nil como parametro quebra o ljsqlite3
    local rows
    if limit then
        rows = self:read(sql .. " LIMIT ? OFFSET ?;", limit, offset or 0)
    else
        rows = self:read(sql .. ";")
    end
    local out = {}
    for i, row in ipairs(rows) do out[i] = chat_from_row(row) end
    return out
end

function Store:list_messages(chat_id, limit, offset)
    return self:read("SELECT id, role, seq, content, content_hash, created_at"
        .. " FROM messages WHERE chat_id = ? ORDER BY seq LIMIT ? OFFSET ?;",
        chat_id, limit, offset or 0)
end

function Store:count_messages(chat_id)
    local row = self:read_one("SELECT COUNT(*) AS total FROM messages WHERE chat_id = ?;",
        chat_id)
    return tonumber(row and row.total) or 0
end

-- Posicao 0-based da mensagem na conversa: e o offset que o leitor usa para pular
-- direto num resultado de busca ou num bookmark, sem carregar o resto antes.
function Store:message_position(chat_id, seq)
    local row = self:read_one(
        "SELECT COUNT(*) AS total FROM messages WHERE chat_id = ? AND seq < ?;",
        chat_id, seq)
    return tonumber(row and row.total) or 0
end

-- O id do bookmark e o id da mensagem, entao o leitor pergunta por ele direto.
function Store:get_bookmark(id)
    local row = self:read_one("SELECT id, chat_id, message_id, note, created_at"
        .. " FROM bookmarks WHERE id = ?;", id)
    if not row then return nil end
    return { id = row.id, chatId = row.chat_id, messageId = row.message_id,
             note = row.note, createdAt = row.created_at }
end

function Store:set_favorite(id, favorite)
    self:write("UPDATE chats SET favorite = ? WHERE id = ?;", favorite and 1 or 0, id)
end

function Store:search(term, limit)
    -- FTS5 no dispositivo seria o ideal; no MVP a busca local e um LIKE simples, e o
    -- backend continua sendo quem faz full-text (docs/sync-protocol.md, secao 13).
    return self:read([[
        SELECT c.id AS chat_id, c.title AS title, m.id AS message_id, m.seq AS seq,
               m.content AS content
        FROM messages m JOIN chats c ON c.id = m.chat_id
        WHERE m.content LIKE ? OR c.title LIKE ?
        ORDER BY c.updated_at DESC, m.seq LIMIT ?;
    ]], "%" .. term .. "%", "%" .. term .. "%", limit or 50)
end

return M
