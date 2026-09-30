-- Store em memoria: implementa a mesma interface de store/sqlite.lua, sem banco.
--
-- Serve para os testes de busted e para exercitar a logica de sync sem dispositivo.
-- O Kindle usa a versao SQLite; se as duas divergirem, o bug e do store, nao do sync.

local M = {}

-- Lua 5.1 (LuaJIT) nao tem # para tabelas com chaves; o store e sempre por id
local function count_keys(t)
    local n = 0
    for _ in pairs(t) do n = n + 1 end
    return n
end

local Store = {}
Store.__index = Store

-- Mesma regra do store SQLite: entidade sem id nao tem chave de conflito, e o LWW
-- (ADR-005) nao sabe o que fazer com ela.
local function require_id(entity, what)
    local id = entity.id
    if type(id) ~= "string" or id == "" then
        error(what .. " sem id: " .. type(id))
    end
    return id
end

-- O JSON da API usa `sequence`/`contentHash`/`createdAt`; a linha do SQL usa `seq`/
-- content_hash/created_at. Quem chama a interface (o leitor, a busca) deve ver a mesma
-- forma nos dois stores, entao a conversao acontece na entrada.
local function normalize_message(chat_id, message)
    return {
        id = require_id(message, "put_message"),
        chat_id = chat_id,
        role = message.role or "user",
        seq = message.seq or message.sequence or 0,
        content = message.content or "",
        content_hash = message.contentHash,
        created_at = message.createdAt,
    }
end

function M.new()
    return setmetatable({
        chats = {}, tags = {}, bookmarks = {}, outbox = {}, next_seq = 1, token = 0,
    }, Store)
end

-- Paridade de interface com o store SQLite: quem usa a porta pode chamar close()
-- sem saber qual dos dois esta em maos.
function Store:close() end

function Store:count(what)
    if what == "chats" then return count_keys(self.chats) end
    if what == "tags" then return count_keys(self.tags) end
    if what == "bookmarks" then return count_keys(self.bookmarks) end
    if what == "messages" then
        local n = 0
        for _, chat in pairs(self.chats) do n = n + #chat.messages end
        return n
    end
    return 0
end

function Store:get_token()
    return self.token
end

function Store:save_token(token)
    self.token = token
end

-- O store guarda a conversa no vocabulario do dominio, igual ao SQLite: favorite e'
-- sempre booleano e messageCount e' sempre numero. Sem isto os dois stores divergem no
-- que devolvem (aqui nil, la false/0) e a tela passa a depender de qual store esta
-- embaixo. Quem preenche e' o sync, mas a normalizacao fica aqui para o contrato do
-- store ser o mesmo nos dois.
local function normalize_chat(chat)
    chat.favorite = chat.favorite and true or false
    chat.messageCount = tonumber(chat.messageCount) or 0
    chat.messages = chat.messages or {}
    return chat
end

function Store:put_chat(chat)
    local id = require_id(chat, "put_chat")
    local atual = self.chats[id]
    if not atual then
        self.chats[id] = normalize_chat(chat)
        return
    end
    for k, v in pairs(chat) do
        if k ~= "messages" then atual[k] = v end
    end
    normalize_chat(atual)
    -- conversa grande vem sem mensagens (messagesOmitted): o que esta no banco
    -- continua valendo, e o leitor busca as paginas sob demanda
    if chat.messages then
        local normalized = {}
        for _, message in ipairs(chat.messages) do
            normalized[#normalized + 1] = normalize_message(id, message)
        end
        atual.messages = normalized
    end
end

function Store:replace_messages(chat_id, messages)
    local chat = self.chats[chat_id]
    if not chat then return end
    local normalized = {}
    for _, message in ipairs(messages) do
        normalized[#normalized + 1] = normalize_message(chat_id, message)
    end
    chat.messages = normalized
end

function Store:get_chat(id)
    return self.chats[id]
end

function Store:delete_chat(id)
    self.chats[id] = nil
end

-- Sem banco nao ha o que reverter: o store em memoria so existe para exercitar a
-- logica de sync. A atomicidade de verdade e testada em store/sqlite.lua.
function Store:transaction(fn)
    if self.in_transaction then error("transaction aninhada") end
    self.in_transaction = true
    local ok, result = pcall(fn)
    self.in_transaction = false
    if not ok then error(result) end
    return result
end

function Store:chat_ids()
    local ids = {}
    for id in pairs(self.chats) do ids[#ids + 1] = id end
    table.sort(ids)
    return ids
end

-- Mesma ordem do SQL (updated_at desc, title): a biblioteca e ordenada por data e a
-- UI nao pode mudar de ordem conforme o store usado.
local function sorted_chats(t, only_favorites)
    local keys = {}
    for id, chat in pairs(t) do
        if not only_favorites or chat.favorite then keys[#keys + 1] = id end
    end
    table.sort(keys, function(a, b)
        local x, y = t[a], t[b]
        local xa, ya = x.updatedAt or "", y.updatedAt or ""
        if xa ~= ya then return xa > ya end
        return (x.title or "") < (y.title or "")
    end)
    return keys
end

function Store:list_chats(limit, offset, only_favorites)
    local keys = sorted_chats(self.chats, only_favorites)
    local out, first = {}, (offset or 0) + 1
    for i = first, #keys do
        if limit and #out >= limit then break end
        out[#out + 1] = self.chats[keys[i]]
    end
    return out
end

function Store:list_messages(chat_id, limit, offset)
    local chat = self.chats[chat_id]
    if not chat then return {} end
    local out, first = {}, (offset or 0) + 1
    for i = first, #chat.messages do
        if limit and #out >= limit then break end
        out[#out + 1] = chat.messages[i]
    end
    return out
end

function Store:count_messages(chat_id)
    local chat = self.chats[chat_id]
    return chat and #(chat.messages or {}) or 0
end

function Store:message_position(chat_id, seq)
    local chat = self.chats[chat_id]
    if not chat then return 0 end
    local n = 0
    for _, message in ipairs(chat.messages or {}) do
        if (message.seq or 0) < seq then n = n + 1 end
    end
    return n
end

function Store:get_bookmark(id)
    return self.bookmarks[id]
end

function Store:set_favorite(id, favorite)
    if self.chats[id] then self.chats[id].favorite = favorite and true or false end
end

-- Mesma forma e mesmo recorte do SQL: uma linha por mensagem que casou, na ordem do
-- store (updated_at desc) e com seq, que e o que o leitor usa para pular ate ela.
function Store:search(term, limit)
    local needle = term:lower()
    local out = {}
    for _, id in ipairs(sorted_chats(self.chats)) do
        local chat = self.chats[id]
        local title_hit = (chat.title or ""):lower():find(needle, 1, true)
        for _, message in ipairs(chat.messages or {}) do
            if title_hit or message.content:lower():find(needle, 1, true) then
                out[#out + 1] = { chat_id = chat.id, title = chat.title,
                                  message_id = message.id, seq = message.seq,
                                  content = message.content }
            end
        end
        if limit and #out >= limit then break end
    end
    if limit then
        while #out > limit do out[#out] = nil end
    end
    return out
end

function Store:put_tag(tag)
    self.tags[require_id(tag, "put_tag")] = tag
end

function Store:put_bookmark(bookmark)
    self.bookmarks[require_id(bookmark, "put_bookmark")] = bookmark
end

function Store:delete_bookmark(id)
    self.bookmarks[id] = nil
end

function Store:clear()
    self.chats = {}
    self.tags = {}
    self.bookmarks = {}
    -- a fila de escritas offline NAO e limpa: sao acoes do usuario que o servidor
    -- ainda nao viu, e um reset local nao pode descartar isso
end

-- ---------------------------------------------------------------------------
-- Escritas offline (mesma separacao do store SQLite: sync nao enfileira)
-- ---------------------------------------------------------------------------

function Store:enqueue_local(type, entry)
    local row = { seq = self.next_seq, type = type }
    for key, value in pairs(entry) do row[key] = value end
    self.next_seq = self.next_seq + 1
    self.outbox[#self.outbox + 1] = row
    return row
end

function Store:pending_local(limit)
    local out = {}
    for i, row in ipairs(self.outbox) do
        if limit and #out >= limit then break end
        out[#out + 1] = row
    end
    return out
end

function Store:drop_local(seqs)
    local unwanted = {}
    for _, seq in ipairs(seqs or {}) do unwanted[seq] = true end
    local kept = {}
    for _, row in ipairs(self.outbox) do
        if not unwanted[row.seq] then kept[#kept + 1] = row end
    end
    self.outbox = kept
end

function Store:count_local()
    return #self.outbox
end

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

return M
