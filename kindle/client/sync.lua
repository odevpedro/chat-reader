-- Logica de sincronizacao do lado do Kindle (docs/sync-protocol.md, secoes 4 e 5).
--
-- Este arquivo nao conhece KOReader nem rede: ele fala com a porta ClientHttp
-- (get_json/post_json) e com um store (mesma interface de store/sqlite.lua). E o que
-- permite rodar os testes em Lua puro, no container, contra o backend real.
--
-- Contrato em duas portas:
--   http:get_json(path) -> tabela
--   http:post_json(path, body) -> tabela
--   store:put_chat/put_tag/put_bookmark/delete_chat/delete_bookmark/clear
--   store:get_token/save_token
--   store:transaction(fn) -- aplica fn e grava (ou nao) como um todo
--
-- Regra que o resto deste arquivo existe para respeitar: o token so avanca depois que
-- as mudancas daquela pagina foram gravadas, e tudo isso dentro de uma transacao. Se
-- a gravacao falhar no meio, o token fica para tras e o proximo ciclo repete a pagina
-- (as mudancas sao upserts, entao repetir e seguro).

local M = {}

M.DEFAULT_LIMIT = 200
M.SNAPSHOT_PAGE_SIZE = 200

-- Teto de escritas enviadas por ACK. O backend nao impõe limite aqui, mas um Lote
-- enorme deixaria o pedido lento no 3G do Kindle; o que sobrar vai no proximo ciclo.
M.ACK_BATCH = 50

local function apply_change(store, change)
    local kind = change.type

    if kind == "CHAT_DELETED" then
        store:delete_chat(change.entityId)
    elseif kind == "CHAT_CREATED" or kind == "CHAT_UPDATED" then
        if change.chat then store:put_chat(change.chat) end
    elseif kind == "TAG_CREATED" or kind == "TAG_UPDATED" then
        if change.tag then store:put_tag(change.tag) end
    elseif kind == "BOOKMARK_CREATED" or kind == "BOOKMARK_UPDATED" then
        if change.bookmark then store:put_bookmark(change.bookmark) end
    elseif kind == "BOOKMARK_DELETED" then
        store:delete_bookmark(change.entityId)
    end
    -- MESSAGE_* nao chega ao cliente: o conteudo vem dentro do chat
end

-- Snapshot paginado por conversa (o backend pagina por chat e repete tags e
-- bookmarks em todas as paginas — SyncService.snapshot).
--
-- Nao limpamos o banco antes: cada pagina e gravada em cima do que ja existe e as
-- conversas que o snapshot nao mencionar sao removidas no fim. Assim uma falha de
-- rede no meio do snapshot deixa a biblioteca intacta (com o token antigo, que faz
-- o proximo ciclo recomecar) em vez de deixar o Kindle sem nada. O custo e guardar
-- o conjunto de ids vistos, que e memoria pequena em comparacao com o conteudo.
local function apply_snapshot(http, store, expected_token)
    local page = 0
    local token = expected_token
    local seen = {}

    while true do
        local snap = http:get_json(string.format("/api/sync/snapshot?page=%d&size=%d",
                page, M.SNAPSHOT_PAGE_SIZE))
        token = snap.syncToken or token

        local chats = snap.chats or {}
        local tags = snap.tags or {}
        local bookmarks = snap.bookmarks or {}

        store:transaction(function()
            -- so a primeira pagina precisa: o catalogo vem repetido nas demais
            if page == 0 then
                for _, tag in ipairs(tags) do store:put_tag(tag) end
                for _, bookmark in ipairs(bookmarks) do store:put_bookmark(bookmark) end
            end
            for _, chat in ipairs(chats) do
                store:put_chat(chat)
                seen[chat.id] = true
            end
        end)

        if snap.last then break end
        page = page + 1
    end

    -- Fecha o snapshot: o que ficou de fora foi removido no servidor.
    store:transaction(function()
        for _, id in ipairs(store:chat_ids()) do
            if not seen[id] then store:delete_chat(id) end
        end
        store:save_token(token)
    end)

    return token
end

-- Executa um ciclo de sync.
--
-- @param http       porta ClientHttp
-- @param store      store local (SQLite no dispositivo, memoria nos testes)
-- @param since      token guardado; 0 quando o cliente nao tem nada
-- @param client_id  identificador do dispositivo, so para observabilidade no servidor
-- @return { applied_snapshot = boolean, changes = number, token = number,
--           ack = table|nil, rejections = table|nil, ack_error = string|nil }
function M.run(http, store, since, client_id)
    local token = since or 0
    local applied = 0
    local snapshot = false

    while true do
        local delta = http:get_json(string.format("/api/sync?since=%d&limit=%d",
                token, M.DEFAULT_LIMIT))

        -- fullResyncRequired pode chegar em qualquer pagina: o delta nao entrega o
        -- estado inteiro e so o snapshot recupera (secao 4.4)
        if delta.fullResyncRequired then
            token = apply_snapshot(http, store, delta.syncToken)
            snapshot = true
            break
        end

        store:transaction(function()
            for _, change in ipairs(delta.changes or {}) do
                apply_change(store, change)
            end
            store:save_token(delta.syncToken or token)
        end)

        applied = applied + #(delta.changes or {})
        token = delta.syncToken or token
        if not delta.hasMore then break end
    end

    local result = { applied_snapshot = snapshot, changes = applied, token = token }

    -- Confirma ao servidor e sobe o que ficou offline. Falha aqui nao desfaz nada do
    -- que ja esta local: e apenas um envio, e o proximo ciclo repete.
    local ok, err = pcall(M.ack, http, store, token, client_id)
    if ok then
        result.ack = err
        if err.rejected and #err.rejected > 0 then
            result.rejections = err.rejected
        end
    else
        result.ack_error = tostring(err)
    end

    return result
end

-- O store ja devolve a fila no vocabulario do payload (chatId, refId, messageId).
local function wire_payload(row)
    if row.type == "CHAT_FAVORITE" then
        return { chatId = row.chatId, favorite = row.favorite }
    elseif row.type == "BOOKMARK_CREATED" then
        return { id = row.refId, chatId = row.chatId,
                 messageId = row.messageId, note = row.note }
    end
    return { id = row.refId }
end

-- Confirma o token e sobe as escritas que ficaram offline.
--
-- Duas regras do protocolo (docs/sync-protocol.md, secao 5) que o codigo segue a risca:
--
-- 1. O corpo usa `ackVersion`, nao `syncToken`. E o `syncToken` devolvido NAO vira
--    token local: ele e a versao do servidor depois das escritas e pode incluir mudancas
--    de outro dispositivo que o Kindle ainda nao baixou. Adotar esse valor aqui pularia
--    essas mudancas. O token local so avanca no pull.
-- 2. Um item so sai da fila quando o ACK volta 2xx E o indice nao esta em rejections.
--    Recusado continua na fila para ser tentado de novo, e o motivo sobe para a tela.
--
-- @return { sent = number, rejected = { {index=, reason=, message=} ... } }
function M.ack(http, store, token, client_id)
    local pending = store:pending_local(M.ACK_BATCH)

    local body = { ackVersion = token, clientId = client_id }
    if #pending > 0 then
        local changes = {}
        for _, row in ipairs(pending) do
            changes[#changes + 1] = { type = row.type, payload = wire_payload(row) }
        end
        body.localChanges = changes
    end

    local answer = http:post_json("/api/sync/ack", body) or {}

    local rejected_index = {}
    local rejections = {}
    for _, rejection in ipairs(answer.rejections or {}) do
        rejected_index[rejection.index] = true
        rejections[#rejections + 1] = rejection
    end

    local sent, remove = 0, {}
    for i, row in ipairs(pending) do
        if not rejected_index[i - 1] then
            remove[#remove + 1] = row.seq
            sent = sent + 1
        end
    end
    if #remove > 0 then store:drop_local(remove) end

    return { sent = sent, rejected = rejections }
end

-- store em memoria, so para os testes
function M.new_store()
    return require("store.memory").new()
end

return M
