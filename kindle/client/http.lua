-- Porta ClientHttp sobre o HTTP do KOReader (Turbo), com JSON via rapidjson.
--
-- Verificado contra plugins/kosync.koplugin/KOSyncClient.lua:
--   * o callback da requisicao roda no event loop, nao na coroutine que pediu;
--   * quem espera cede a execucao (coroutine.yield) e e retomado no callback;
--   * o Turbo devolve res.code (o Spore converte para res.status);
--   * headers entram por on_headers.
--
-- Isso significa que get_json/post_json so funcionam quando chamadas de dentro de uma
-- coroutine — que e como os handlers de UI do KOReader rodam. A alternativa
-- (frontend/httpasync.lua) traz um scheduler proprio e nao serve para uma requisicao
-- sequencial por vez.
--
-- Nao usamos httpasync porque ele resolve varios downloads concorrentes; aqui o sync e
-- sequencial e cabe no httpclient, que e o mesmo caminho do KOSync.

local M = {}

-- Erro de HTTP com o status preservado: a UI mostra a mensagem, e o caller decide se
-- renova o token (401) ou espera a rede voltar.
local HttpError = {}
HttpError.__index = HttpError
HttpError.__tostring = function(e)
    return string.format("HTTP %d em %s: %s", e.code, e.path, e.body or "sem corpo")
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

-- Requisicao + espera. Retorna o corpo ja decodificado (ou nil em 204).
function Client:_fetch(method, path, body)
    local json = require("rapidjson")
    local payload = body and json.encode(body) or nil
    local headers = self:_headers(payload ~= nil)
    local response
    local thread

    local function on_response(res)
        response = res
        -- A resposta pode chegar antes da espera comecar (caminho sincrono do
        -- Turbo, cache local, DNS ja resolvido). Se isso acontecer, a coroutine
        -- ainda esta por iniciar e nao ha o que retomar: so o resultado importa.
        if thread then coroutine.resume(thread) end
    end

    thread = coroutine.create(function()
        require("httpclient"):new():request({
            url = self.base_url .. path,
            method = method,
            body = payload,
            timeout = self.timeout,
            on_headers = function(received)
                for name, value in pairs(headers) do received:add(name, value) end
            end,
        }, on_response)
        if not response then coroutine.yield() end
    end)

    if not response then
        local ok, err = coroutine.resume(thread)
        if not ok then error(err) end
    end

    if not response then
        error(setmetatable({ code = 0, path = path, body = "sem resposta" }, HttpError))
    end

    -- Turbo usa code; Spore (e o mock em spec) usam status
    local code = response.code or response.status or 0
    if code < 200 or code > 299 then
        error(setmetatable({ code = code, path = path, body = response.body }, HttpError))
    end

    if not response.body or response.body == "" then return nil end
    local decoded, json_error = json.decode(response.body)
    if not decoded then
        error(setmetatable({ code = code, path = path,
                             body = "resposta nao e JSON: " .. tostring(json_error) },
            HttpError))
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
