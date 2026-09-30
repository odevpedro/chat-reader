-- Etapa 6 — OFFLINE (especificacao, secao 43 "Etapa 6" e criterios de aceite da 44).
--
-- Prova a unica coisa que a Etapa 5 nao provou: que o Kindle continua lendo depois que
-- a rede cai. Nao e' um teste de "o cache existe" — e' o fluxo do §44 com o backend
-- fora do ar:
--
--   9. sincronizar  ->  10. desligar conexao  ->  11. abrir chats offline
--                                                        12. navegar pelas mensagens
--
-- Roda em tres fases, porque o mesmo SQLite atravessa as tres:
--
--   online   popula o banco com o backend de pe (sync de verdade)
--   offline  backend DERRUBADO: lista, favoritos, abre, navega, busca, bookmark
--   drenar   backend voltou: a fila escrita offline sobe
--
-- O "backend derrubado" e' literal: o offline.sh para o container, entao o curl falha
-- com connection refused. Nao ha simulacao de erro — se a rede estivesse de pe, a
-- fase offline falharia.--
-- Uso:
--   luajit scripts/offline_test.lua <online|offline|drenar> <db> [usuario senha url]

local json = require("scripts.json")
local sync = require("client.sync")
local sqlite = require("store.sqlite")
local library = require("client.library")

local function fail(msg, ...)
    print("ERRO: " .. string.format(msg, ...))
    os.exit(1)
end

-- ---------------------------------------------------------------------------
-- HTTP real via curl, no vocabulario da porta ClientHttp
-- ---------------------------------------------------------------------------

local HttpError = {}
HttpError.__index = HttpError
HttpError.__tostring = function(e)
    if e.code == 0 then
        -- curl nao chegou a responder: o host nao aceitou a conexao
        return string.format("sem conexao com %s (curl saiu com erro)", e.path)
    end
    return string.format("HTTP %d em %s: %s", e.code, e.path, e.body or "sem corpo")
end

local function shquote(s)
    return "'" .. (s:gsub("'", "'\\''")) .. "'"
end

local function new_http(base_url, token)
    local self = { base_url = base_url, token = token }

    local function fetch(method, path, body)
        local curl = "curl -sS -m 15 -o /tmp/offline_body -w '%{http_code}'"
        if self.token then
            curl = curl .. " -H " .. shquote("Authorization: Bearer " .. self.token)
        end
        if body ~= nil then
            curl = curl .. " -H 'Content-Type: application/json' -d "
                 .. shquote(json.encode(body))
        end
        if method ~= "GET" then curl = curl .. " -X " .. method end
        curl = curl .. " " .. shquote(self.base_url .. path)
        local pipe = io.popen(curl)
        local code = pipe:read("*a")
        pipe:close()
        local file = io.open("/tmp/offline_body", "r")
        local response = file and file:read("*a") or ""
        if file then file:close() end

        local numeric = tonumber(code)
        if not numeric or numeric < 200 or numeric > 299 then
            error(setmetatable({ code = numeric or 0, path = path, body = response },
                HttpError))
        end
        if response == "" then return nil end
        return json.decode(response)
    end

    function self:get_json(path) return fetch("GET", path) end
    function self:post_json(path, body) return fetch("POST", path, body) end
    return self
end

-- ---------------------------------------------------------------------------
-- fase 1 — online
-- ---------------------------------------------------------------------------

-- O que a fase online deixou marcado. As fases sao processos separados, entao a fase
-- offline precisa saber qual conversa foi favoritada e qual mensagem foi bookmarkada —
-- sem isso ela conferiria o id errado e o teste passaria (ou falharia) por acaso.
local function write_marker(path, marker)
    local file = assert(io.open(path, "w"))
    file:write(json.encode(marker))
    file:close()
end

local function read_marker(path)
    local file = io.open(path, "r")
    if not file then fail("sem estado de " .. path .. ": rode a fase online antes") end
    local text = file:read("*a")
    file:close()
    return json.decode(text)
end

local function phase_online(store, username, password, base_url, marker_path)
    print("==> [online] login e sync de abertura em " .. base_url)
    local http = new_http(base_url, nil)
    local answer = http:post_json("/api/auth/login",
        { username = username, password = password })
    if not answer or not answer.token then fail("login sem token") end
    http.token = answer.token

    local result = sync.run(http, store, 0, "offline-test")
    if not result.applied_snapshot then fail("1a sync nao veio por snapshot") end
    print(string.format("    token=%d chats=%d mensagens=%d",
        result.token, store:count("chats"), store:count("messages")))
    if store:count("chats") < 1 then fail("nenhuma conversa sincronizada") end

    -- um favorito e um bookmark, ainda com a rede de pe: e' o estado que a fase
    -- offline tem que encontrar intacto
    local chat_id = store:chat_ids()[1]
    store:set_favorite_offline(chat_id, true)
    local messages = store:list_messages(chat_id, 1, 0)
    store:bookmark_offline({ id = messages[1].id, chatId = chat_id,
                             messageId = messages[1].id })
    local drained = sync.run(http, store, result.token, "offline-test")
    if store:count_local() > 0 then
        fail("a fila deveria estar vazia: %d", store:count_local())
    end
    write_marker(marker_path, { chatId = chat_id, messageId = messages[1].id,
                                token = drained.token })
    print(string.format("    warmed: chat=%s mensagem=%s token=%d",
        chat_id, messages[1].id, drained.token))
end

-- ---------------------------------------------------------------------------
-- fase 2 — offline (o backend esta fora do ar)
-- ---------------------------------------------------------------------------

local function phase_offline(store, base_url, marker)
    if not marker or not marker.chatId or not marker.messageId then
        fail("fase offline sem estado da fase online")
    end
    local before_chats = store:count("chats")
    local before_messages = store:count("messages")
    if before_chats < 1 then fail("banco vazio antes do teste offline") end
    print(string.format("==> [offline] %d chats e %d mensagens ja locais",
        before_chats, before_messages))

    -- 1. primeiro: a rede esta mesmo fora. A falha tem de ser de CONEXAO (curl 7 /
    --    codigo HTTP 0), nao uma resposta do servidor: um 401 provaria que o backend
    --    estava de pe e que este teste nao diz nada sobre offline.
    local http = new_http(base_url, "token-que-nao-existe")
    local reachable, err = pcall(function() return http:get_json("/api/sync?since=0") end)
    if reachable then
        fail("o backend respondeu na fase offline: o teste nao provaria nada")
    end
    if type(err) ~= "table" or err.code ~= 0 then
        fail("esperava falha de conexao, veio erro HTTP %s — o backend respondeu, "
             .. "entao a fase offline nao prova nada",
            tostring(type(err) == "table" and err.code or err))
    end
    print("    backend inacessivel: " .. tostring(err):gsub("\n.*", ""))

    -- 2. §44.11 abrir chats offline
    local rows = library.chat_rows(store, { limit = 20 })
    if #rows < 1 then fail("biblioteca vazia offline") end
    print(string.format("    biblioteca: %d conversas, primeira = %s (%s)",
        #rows, rows[1].title, rows[1].subtitle))

    -- A conversa mais longa da biblioteca, para a paginacao ter o que paginar.
    local maior, maior_n = nil, 0
    for _, row in ipairs(rows) do
        if (row.subtitle or ""):match("(%d+) mensagens?") then
            local n = tonumber(row.subtitle:match("(%d+)")) or 0
            if n > maior_n then maior, maior_n = row, n end
        end
    end
    if not maior then fail("nenhuma conversa com contagem de mensagens no subtitulo") end
    if maior_n < 2 then
        fail("contagem de mensagens ausente no subtitulo: '%s'", maior.subtitle)
    end

    -- 3. favoritos visiveis offline
    local favorites = library.chat_rows(store, { limit = 20, only_favorites = true })
    if #favorites < 1 then fail("nenhum favorito visivel offline") end
    print(string.format("    favoritos: %d", #favorites))

    -- 4. §44.12 navegar pelas mensagens: paginado, sem carregar a conversa inteira
    local chat_id = maior.id
    local total = store:count_messages(chat_id)
    if total < 2 then fail("conversa sem mensagens suficientes para navegar") end
    local window = 2
    local visited, seen = 0, {}
    local offset = 0
    while true do
        local page = store:list_messages(chat_id, window, offset)
        if #page == 0 then break end
        for _, message in ipairs(page) do
            visited = visited + 1
            seen[message.seq] = true
            if #message.content == 0 then fail("mensagem %d sem conteudo", message.seq) end
        end
        offset = offset + window
        if offset >= total then break end
    end
    if visited ~= total then
        fail("navegacao visitou %d de %d mensagens", visited, total)
    end
    -- pular direto para uma mensagem, como o leitor faz ao abrir um resultado
    local jumped = store:message_position(chat_id, total - 1)
    if jumped ~= total - 1 then fail("message_position devolveu %d", jumped) end
    print(string.format("    navegacao: %d/%d mensagens, pulo para #%d ok",
        visited, total, total - 1))

    -- 5. busca local (§44.12): tem que achar no conteudo sincronizado. Usa a palavra
    --    mais longa do titulo, para o termo ser de verdade e nao uma letra.
    local termo = nil
    for palavra in rows[1].title:gmatch("%a[%a%d]+") do
        if #palavra > 3 and (not termo or #palavra > #termo) then termo = palavra end
    end
    termo = termo or rows[1].title
    local hits = store:search(termo, 10)
    if #hits < 1 then fail("busca local por '%s' nao achou nada offline", termo) end
    local hit_rows = library.search_rows(hits, termo, 60)
    if #hit_rows < 1 or not hit_rows[1].snippet or #hit_rows[1].snippet == 0 then
        fail("resultado de busca sem trecho: %s", tostring(hit_rows[1] and hit_rows[1].snippet))
    end
    print(string.format("    busca local '%s': %d resultados, ex. %s",
        termo, #hit_rows, (hit_rows[1].snippet or ""):sub(1, 50)))

    -- 6. o favorito e o bookmark que a fase online deixou continuam aqui
    local favoritada = store:get_chat(marker.chatId)
    if not favoritada or not favoritada.favorite then
        fail("o favorito da fase online sumiu offline")
    end
    local bookmark = store:get_bookmark(marker.messageId)
    if not bookmark then fail("o bookmark da fase online sumiu offline") end
    if bookmark.chatId ~= marker.chatId or bookmark.messageId ~= marker.messageId then
        fail("bookmark offline aponta para outra conversa: %s/%s",
            tostring(bookmark.chatId), tostring(bookmark.messageId))
    end
    print(string.format("    intactos: favorito=%s bookmark=%s",
        marker.chatId, bookmark.id))

    -- 7. um sync offline falha, e o mais importante: NAO estraga o que ja era local
    local ok, result = pcall(function() return sync.run(http, store, store:get_token(), "offline-test") end)
    if ok then fail("sync teve sucesso com o backend fora do ar") end
    if store:count("chats") ~= before_chats or store:count("messages") ~= before_messages then
        fail("o sync offline corrompeu o banco local")
    end
    print("    sync offline falhou como esperado, banco intacto")

    -- 8. escrita offline ainda funciona: favoritar e bookmarkar sem rede
    local other, other_msg = nil, nil
    for _, row in ipairs(rows) do
        if row.id ~= marker.chatId and store:count_messages(row.id) > 0 then
            other = row.id
            other_msg = store:list_messages(row.id, 1, 0)[1]
            break
        end
    end
    if not other then fail("nenhuma outra conversa com mensagens para escrever offline") end
    store:set_favorite_offline(other, true)
    store:bookmark_offline({ id = other_msg.id, chatId = other, messageId = other_msg.id })
    if store:count_local() < 2 then fail("escrita offline nao enfileirou") end
    print(string.format("    escritas offline enfileiradas: %d em %s",
        store:count_local(), other))

    -- 9. e o token local NAO andou
    if store:get_token() == nil then fail("token local sumiu") end
    print("    token local preservado: " .. tostring(store:get_token()))
end

-- ---------------------------------------------------------------------------
-- fase 3 — drenar (o backend voltou)
-- ---------------------------------------------------------------------------

local function phase_drenar(store, username, password, base_url)
    local pending = store:count_local()
    print("==> [drenar] voltando com " .. pending .. " escritas pendentes")
    if pending < 2 then fail("a fase offline deveria ter deixado 2 escritas") end

    local http = new_http(base_url, nil)
    local answer = http:post_json("/api/auth/login",
        { username = username, password = password })
    http.token = answer and answer.token
    if not http.token then fail("login falhou na fase drenar") end

    local result = sync.run(http, store, store:get_token(), "offline-test")
    if not result.ack or result.ack.sent < 2 then
        local motivo = "nenhum"
        if result.ack and result.ack.rejected and result.ack.rejected[1] then
            motivo = tostring(result.ack.rejected[1].message)
        end
        fail("ACK nao subiu as escritas: enviou %s (recusou: %s)",
            tostring(result.ack and result.ack.sent), motivo)
    end
    if store:count_local() > 0 then fail("fila nao esvaziou") end
    print(string.format("    fila esvaziada (%d enviadas), token=%d", result.ack.sent, result.token))
end

-- ---------------------------------------------------------------------------

local function main()
    local phase, db = arg[1], arg[2]
    local username, password = arg[3], arg[4]
    local base_url = arg[5] or "http://127.0.0.1:8080"
    local marker_path = db .. ".marker"
    if not phase or not db then
        fail("uso: offline_test.lua <online|offline|drenar> <db> [usuario senha url]")
    end

    local store = sqlite.open(db)
    if phase == "online" then
        phase_online(store, username, password, base_url, marker_path)
    elseif phase == "offline" then
        phase_offline(store, base_url, read_marker(marker_path))
    elseif phase == "drenar" then
        phase_drenar(store, username, password, base_url)
    else
        fail("fase desconhecida: " .. tostring(phase))
    end
    store:close()

    print("offline ok (" .. phase .. ")")
    os.exit(0)
end

main()
