-- Configuracao do plugin em LuaSettings (frontend/luasettings.lua), o mesmo mecanismo
-- que o KOReader usa para preferences de plugin.
--
-- Fica tudo em um arquivo senao: base da API, credenciais e o token atual. O token
-- e cache local para nao pedir senha a cada sync; a validade vem do backend
-- (expiresAt) e e conferida antes de usar.

local M = {}

local Settings = {}
Settings.__index = Settings

-- @param path caminho do arquivo de settings; nil usa o padrao do KOReader
function M.open(path)
    local LuaSettings = require("luasettings")
    -- Caminho completo, como todo plugin do KOReader faz: um nome solto abriria
    -- relativo ao diretorio de trabalho, e o arquivo sumiria a cada execucao.
    local file = path or (require("datastorage"):getSettingsDir() .. "/chatreader.lua")
    return setmetatable({ store = LuaSettings:open(file) }, Settings)
end

function Settings:base_url()
    return self.store:readSetting("base_url", "http://127.0.0.1:8080")
end

function Settings:set_base_url(url)
    self.store:saveSetting("base_url", (url or ""):gsub("/+$", ""))
    self.store:flush()
end

function Settings:username()
    return self.store:readSetting("username", "")
end

-- Guardar o usuario e autenticar sao passos separados: a senha nunca vai para o
-- arquivo (so o token, que o backend reemite quando expira).
function Settings:set_username(username)
    self.store:saveSetting("username", username or "")
    self.store:flush()
end

function Settings:token()
    return self.store:readSetting("token", nil)
end

-- expires_at em epoch; sem valor o token e considerado valido (o 401 corrige).
function Settings:token_expired()
    local expires_at = self.store:readSetting("token_expires_at", nil)
    if not expires_at then return false end
    return os.time() >= expires_at
end

function Settings:save_login(answer)
    self.store:saveSetting("token", answer.token)
    if answer.expiresAt then
        -- o backend manda ISO-8601; guardamos epoch para comparar
        local year, month, day = answer.expiresAt:match("(%d%d%d%d)-(%d%d)-(%d%d)")
        local hour, minute, second = answer.expiresAt:match("(%d%d):(%d%d):(%d%d)")
        if year and hour then
            self.store:saveSetting("token_expires_at", os.time({
                year = tonumber(year), month = tonumber(month), day = tonumber(day),
                hour = tonumber(hour), min = tonumber(minute), sec = tonumber(second),
                isdst = false,
            }))
        end
    end
    self.store:flush()
end

-- Usado quando o servidor responde 401: o token velho nao tem recuperacao, e pedir
-- senha com um token invalido por header so faria o login falhar.
function Settings:forget_login()
    self.store:saveSetting("token", nil)
    self.store:saveSetting("token_expires_at", nil)
    self.store:flush()
end

-- Identificador do aparelho, so para observabilidade no servidor (docs/sync-protocol.md,
-- secao 5). Gerado uma vez e guardado: serve para saber de onde veio a escrita quando
-- dois aparelhos sincronizam, e nao tem por que ser novo a cada execucao.
function Settings:client_id()
    local id = self.store:readSetting("client_id", nil)
    if not id then
        local seed = string.format("%d-%d", os.time(), math.random(1, 999999))
        id = "kindle-" .. seed
        self.store:saveSetting("client_id", id)
        self.store:flush()
    end
    return id
end

-- Faz login e persiste o token. O http precisa estar sem token velho para nao mandar
-- credencial invalida no primeiro POST.
function Settings:login(http, password)
    http.token = nil
    local answer = http:login(self:username(), password)
    self:save_login(answer)
    return answer
end

function Settings:close()
    if self.store then self.store:close() end
end

return M
