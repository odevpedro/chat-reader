-- Testes das settings com LuaSettings stubado.
--
-- O que vale testar sem o dispositivo: o que fica em disco (o token pode, a senha
-- nao) e a leitura da validade do token, que e o que evita um 401 a cada sync.

local settings_module = require("client.settings")

describe("client/settings", function()
    local disk
    local settings

    before_each(function()
        disk = {}
        package.loaded["luasettings"] = {
            open = function()
                return {
                    readSetting = function(_, key, default)
                        local value = disk[key]
                        if value == nil then return default end
                        return value
                    end,
                    saveSetting = function(_, key, value) disk[key] = value end,
                    flush = function() end,
                    close = function() end,
                }
            end,
        }
        settings = settings_module.open("chatreader")
    end)

    after_each(function()
        package.loaded["luasettings"] = nil
    end)

    it("usa a base local como padrao e normaliza a barra final", function()
        assert.equals("http://127.0.0.1:8080", settings:base_url())

        settings:set_base_url("http://10.0.0.5:8080/")

        assert.equals("http://10.0.0.5:8080", settings:base_url())
    end)

    it("nao guarda senha, so o token", function()
        settings:set_username("leitor")
        local http = { token = "velho" }
        function http:login(username, password)
            self.user, self.password = username, password
            return { token = "jwt-novo", expiresAt = "2099-01-01T10:00:00Z" }
        end

        settings:login(http, "senha-secreta")

        assert.equals("leitor", http.user)
        assert.equals("senha-secreta", http.password)
        assert.equals("jwt-novo", settings:token())
        for _, value in pairs(disk) do
            assert.is_nil(tostring(value):find("senha-secreta", 1, true))
        end
        -- o token velho foi limpo antes do POST (o Client:login real e quem grava o novo)
        assert.is_nil(http.token)
    end)

    it("converte expiresAt em epoch e marca expirado depois do prazo", function()
        settings:save_login({ token = "t", expiresAt = "2026-09-29T15:00:00Z" })

        assert.equals(2026, tonumber(os.date("!%Y", disk.token_expires_at)))
        -- o fixture e no passado em relacao ao instante do teste
        assert.is_true(settings:token_expired())
    end)

    it("token sem validade guardada nao e dado como expirado", function()
        settings:save_login({ token = "t" })

        assert.is_false(settings:token_expired())
    end)

    it("expiracao no futuro ainda vale", function()
        settings:save_login({ token = "t", expiresAt = "2099-01-01T10:00:00Z" })

        assert.is_false(settings:token_expired())
    end)

    it("esquece o token quando o servidor responde 401", function()
        settings:save_login({ token = "velho", expiresAt = "2099-01-01T10:00:00Z" })

        settings:forget_login()

        assert.is_nil(settings:token())
        assert.is_false(settings:token_expired())
    end)

    it("gera o id do aparelho uma vez e reusa", function()
        local id = settings:client_id()

        assert.is_not_nil(id:find("kindle"))
        assert.equals(id, settings:client_id()) -- estavel entre chamadas
        assert.equals(id, settings_module.open("chatreader"):client_id()) -- e entre sessoes
    end)
end)

describe("caminho do arquivo de settings", function()
    it("vai para o settings dir do KOReader, nao para o diretorio de trabalho", function()
        local aberto
        package.loaded["luasettings"] = { open = function(_, file) aberto = file return {} end }
        package.loaded["datastorage"] = {
            getSettingsDir = function() return "/mnt/onboard/.koreader/settings" end,
        }

        settings_module.open()

        assert.equals("/mnt/onboard/.koreader/settings/chatreader.lua", aberto)
        package.loaded["luasettings"] = nil
        package.loaded["datastorage"] = nil
    end)
end)
