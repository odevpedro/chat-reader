-- Porta ClientHttp sobre o HTTP sincrono do KOReader (LuaSocket), com JSON via rapidjson.
--
-- Por que sincrono e nao httpclient/Turbo: no Kindle de verdade DUSE_TURBO_LIB = false
-- (defaults.lua:195), entao UIManager:initLooper() nao cria o looper e
-- httpclient:request() morre com "attempt to index field 'looper' (a nil value)" — foi
-- o erro que o teste no aparelho pegou. O proprio KOSync tem esse corte: so usa Turbo
-- quando UIManager.looper existe e cai para o caminho sincrono caso contrario
-- (plugins/kosync.koplugin/KOSyncClient.lua:40), e o LuaSocket e o que o KOReader usa
-- no resto do frontend (frontend/socketutil.lua).
--
-- O pedido bloqueia a tela durante o sync — e o mesmo que todo plugin do KOReader faz
-- quando fala com a rede. O socketutil poe timeout no bloco e no total para uma rede
-- ruim nunca prender o aparelho para sempre; a resposta e lida num sink que tambem
-- respeita o tempo total.
--
-- Nao ha porta com callback aqui: o que importa e que client/sync.lua consiga falar
-- com este adapter sem conhecer rede nenhuma, e e isso que os specs cobrem.

local M = {}

-- Erro de HTTP com o status preservado: a UI mostra a mensagem, e o caller decide se
-- renova o token (401) ou espera a rede voltar. code 0 e erro de transporte (timeout,
-- recusa, DNS) — nunca e 401, entao o caller mostra a mensagem em vez de pedir senha.
local HttpError = {}
HttpError.__index = HttpError
HttpError.__tostring = function(e)
    return string.format("HTTP %d em %s: %s", e.code, e.path, e.body or "sem corpo")
end

local function http_error(code, path, body)
    return setmetatable({ code = code, path = path, body = body }, HttpError)
end

function M.is_unauthorized(err)
    return type(err) == "table" and getmetatable(err) == HttpError and err.code == 401
end

local Client = {}
Client.__index = Client

--- @param config { base_url = string, token = string|nil, timeout = number|nil }
function M.new(config)
    config = config or {}
    return setmetatable({
        base_url = (config.base_url or ""):gsub("/+$", ""),
        token = config.token,
        timeout = config.timeout,
    }, Client)
end

function Client:_headers(has_body)
    local headers = { ["accept"] = "application/json" }
    if self.token then headers["authorization"] = "Bearer " .. self.token end
    if has_body then headers["content-type"] = "application/json" end
    return headers
end

-- Requisicao bloqueante. Retorna o corpo ja decodificado (ou nil em 204).
function Client:_fetch(method, path, body)
    local json = require("rapidjson")
    local ltn12 = require("ltn12")
    local socketutil = require("socketutil")
    local http = require("socket.http")

    local payload = body and json.encode(body) or nil
    local headers = self:_headers(payload ~= nil)
    -- sem content-length o LuaSocket manda o corpo em chunked; o Spring aceita, mas
    -- declarar o tamanho e o que a maioria dos servidores espera de um POST JSON.
    if payload then headers["content-length"] = tostring(#payload) end

    local chunks = {}
    local request = {
        url = self.base_url .. path,
        method = method,
        headers = headers,
        source = payload and ltn12.source.string(payload) or nil,
        sink = socketutil.table_sink(chunks),
    }

    -- block_timeout cobre o tempo sem resposta, total_timeout o tempo inteiro do
    -- pedido (inclui o download); ambos valem para o socket e para o sink.
    socketutil:set_timeout(15, self.timeout or 60)
    local ok, sent, code, response_headers = pcall(http.request, request)
    socketutil:reset_timeout()

    if not ok then
        -- transporte: o LuaSocket lanca (timeout, conexao recusada, DNS)
        error(http_error(0, path, tostring(sent)))
    end
    if not sent then
        -- variante que devolve nil, erro em vez de lancar
        error(http_error(0, path, tostring(code)))
    end

    -- LuaSocket devolve 1, code, headers, status; code vem numerico, mas o
    -- contrato aqui e string/number — normalizar evita comparacao quebrada.
    code = tonumber(code) or 0
    if code < 200 or code > 299 then
        error(http_error(code, path, table.concat(chunks)))
    end

    local response = table.concat(chunks)
    if response == "" then return nil end
    local decoded, json_error = json.decode(response)
    if not decoded then
        error(http_error(code, path,
            "resposta nao e JSON: " .. tostring(json_error)))
    end
    return decoded
end

function Client:get_json(path)
    return self:_fetch("GET", path)
end

function Client:post_json(path, body)
    return self:_fetch("POST", path, body)
end

-- POST /api/auth/login -> JWT. Quem chama guarda o token e a validade.
function Client:login(username, password)
    local answer = self:post_json("/api/auth/login",
        { username = username, password = password })
    if not answer or not answer.token then
        error("resposta de login sem token")
    end
    self.token = answer.token
    return answer
end

return M
