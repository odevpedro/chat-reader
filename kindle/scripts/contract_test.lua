-- Contrato ponta a ponta: o plugin (client/sync.lua + store/sqlite.lua) conversando
-- com o BACKEND REAL via HTTP de verdade, com banco de verdade (ljsqlite3). E a
-- validacao que o container permite fazer antes de tocar num Kindle.
--
-- De proposito, o HTTP sao os mesmos verbos da porta ClientHttp (get_json/post_json e
-- erro com codigo 401), mas em cima de curl: o que esta em prova aqui e o protocolo —
-- o que o cliente envia literalmente e como interpreta o que o servidor devolve — nao
-- a implementacao de rede do KOReader (testada stubbed nos specs, depois no device).
--
-- Uso (o container de spec tem curl + ljsqlite3 + Luajit):
--   docker run --rm --network host \
--       -v $PWD/kindle:/plugin -w /plugin \
--       chatreader-lua-spec:1.0-1 \
--       sh -c 'luajit scripts/contract_test.lua <usuario> <senha> [http://host:porta]'
--
-- Sai 0 com "contrato ok" no fim; qualquer assercao quebrada sai != 0.

local BASE_URL_DEFAULT = "http://127.0.0.1:8080"

local function fail(msg, ...)
    print("ERRO: " .. string.format(msg, ...))
    os.exit(1)
end

-- ---------------------------------------------------------------------------
-- JSON puro (scripts/json.lua)
-- ---------------------------------------------------------------------------
local json = require("scripts.json")

-- ---------------------------------------------------------------------------
-- HTTP real via curl, no vocabulario da porta ClientHttp
-- ---------------------------------------------------------------------------
local HttpError = {}
HttpError.__index = HttpError
HttpError.__tostring = function(e)
    return string.format("HTTP %d em %s: %s", e.code, e.path, e.body or "sem corpo")
end

local function is_unauthorized(err)
    return type(err) == "table" and getmetatable(err) == HttpError and err.code == 401
end

-- sobrescreve o arquivo /tmp/ct_body na rede, entao nada corre por baixo
local function shquote_curl(s)
    return "'" .. (s:gsub("'", "'\\''")) .. "'"
end

local function new_http(base_url, token)
    local self = { base_url = base_url, token = token }

    local function fetch(method, path, body)
        local curl = "curl -sS -m 30 -o /tmp/ct_body -w '%{http_code}'"
        if self.token then
            curl = curl .. " -H " .. shquote_curl("Authorization: Bearer " .. self.token)
        end
        if body ~= nil then
            curl = curl .. " -H 'Content-Type: application/json' -d "
                 .. shquote_curl(json.encode(body))
        end
        if method ~= "GET" then curl = curl .. " -X " .. method end
        curl = curl .. " " .. shquote_curl(self.base_url .. path)
        local pipe = io.popen(curl)
        local code = pipe:read("*a")
        pipe:close()
        local file = io.open("/tmp/ct_body", "r")
        local response = file and file:read("*a") or ""
        if file then file:close() end

        local numeric = tonumber(code)
        if not numeric or numeric < 200 or numeric > 299 then
            error(setmetatable({ code = numeric or 0, path = path, body = response },
                HttpError))
        end
        if response == "" then return nil end
        local decoded = json.decode(response)
        if decoded == nil then
            error(setmetatable({ code = numeric, path = path,
                                 body = "resposta nao e JSON" }, HttpError))
        end
        return decoded
    end

    function self:get_json(path) return fetch("GET", path) end
    function self:post_json(path, body) return fetch("POST", path, body) end
    return self
end

-- ---------------------------------------------------------------------------
-- o que sera testado
-- ---------------------------------------------------------------------------
local sync = require("client.sync")
local sqlite = require("store.sqlite")

local function new_tmp_store()
    local path = os.tmpname() .. ".db"
    os.remove(path)
    return sqlite.open(path)
end

local function assert_store_state(store, expected_chats, expected_messages)
    local chats = store:count("chats")
    local messages = store:count("messages")
    print(string.format("    chats=%d mensagens=%d", chats, messages))
    if chats < expected_chats then
        fail("esperava pelo menos %d chats, veio %d", expected_chats, chats)
    end
    if messages < expected_messages then
        fail("esperava pelo menos %d mensagens, veio %d", expected_messages, messages)
    end
end

local function main()
    local username, password, base_url = arg[1], arg[2], arg[3] or BASE_URL_DEFAULT
    if not username or not password then
        fail("uso: contract_test.lua <usuario> <senha> [http://host:porta]")
    end

    print("==> login em " .. base_url)
    local http = new_http(base_url, nil)
    local ok, answer = pcall(function()
        return http:post_json("/api/auth/login", { username = username, password = password })
    end)
    if not ok then fail("login falhou: %s", tostring(answer)) end
    if not answer or not answer.token then fail("login sem token") end
    http.token = answer.token
    print("    token ok")

    print("==> sync vazio + snapshot povoando o SQLite real")
    local store = new_tmp_store()
    local result
    ok, result = pcall(function()
        return sync.run(http, store, 0, "contrato-test")
    end)
    if not ok then
        if is_unauthorized(result) then fail("backend devolveu 401 com token valido") end
        fail("sync de abertura falhou: %s", tostring(result))
    end
    assert_store_state(store, 1, 1)
    if not result.applied_snapshot then fail("1a sync nao veio por snapshot") end
    if result.token <= 0 then fail("token local saiu zerado") end

    print("==> favorito e bookmark offline esperando o ACK")
    local chat_id = store:chat_ids()[1]
    store:set_favorite_offline(chat_id, true)
    local message = store:list_messages(chat_id, 1, 0)
    if not message or not message[1] then fail("nenhuma mensagem no chat %s", chat_id) end
    -- O id do bookmark e o id da mensagem, como em ui/reader.lua: o backend exige
    -- VARCHAR(36) e o UNIQUE(chat_id, message_id) torna o reenvio um no-op.
    local message_id = message[1].id
    store:bookmark_offline({ id = message_id, chatId = chat_id,
                             messageId = message_id })
    local fila = store:count_local()
    if fila < 2 then fail("esperava 2 escritas na fila, veio %d", fila) end

    print("==> segundo ciclo: ACK envia as escritas e o token avanca so no pull")
    ok, result = pcall(function()
        return sync.run(http, store, result.token, "contrato-test")
    end)
    if not ok then fail("2o sync falhou: %s", tostring(result)) end
    if not result.ack or result.ack.sent ~= 2 then
        local motivo = "nenhum"
        if result.ack and result.ack.rejected and result.ack.rejected[1] then
            local r = result.ack.rejected[1]
            motivo = string.format("indice %d %s: %s", r.index, r.reason, r.message)
        end
        fail("ACK deveria ter enviado 2 escritas, aceitou %s (recusou: %s)",
            tostring(result.ack and result.ack.sent), motivo)
    end
    if store:count_local() > 0 then fail("fila deveria estar vazia apos o ACK") end
    local chat = store:get_chat(chat_id)
    if not chat or not chat.favorite then fail("favorito nao aplicado no store") end
    if store:get_bookmark(message_id) == nil then
        fail("bookmark nao aplicado no store")
    end

    print("==> terceiro ciclo: delta plano (nada mudou no servidor)")
    ok, result = pcall(function()
        return sync.run(http, store, result.token, "contrato-test")
    end)
    if not ok then fail("3o sync falhou: %s", tostring(result)) end
    if result.applied_snapshot then fail("3o sync nao deveria ser snapshot") end
    if #(result.rejections or {}) > 0 then
        fail("nao deveria haver rejeicoes, veio %d", #result.rejections)
    end

    print("contrato ok")
    os.exit(0)
end

main()