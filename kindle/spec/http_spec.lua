-- Testes do adapter HTTP com httpclient e rapidjson stubados.
--
-- O adapter e IO puro em volta do Turbo, entao o que vale testar aqui e a parte que
-- quebra sem o dispositivo: montagem da URL, dos headers, do corpo JSON, do
-- tratamento de status e do token. O Turbo em si ja e testado pelo KOReader.

local http_client = require("client.http")

describe("client/http", function()
    local last_request
    local last_headers
    local response

    local function header_object()
        last_headers = {}
        return {
            add = function(_, name, value) last_headers[name] = value end,
        }
    end

    before_each(function()
        last_request = nil
        last_headers = nil
        response = { code = 200, body = '{"ok":true}' }

        -- stubs no lugar dos modulos do KOReader (httpclient e Turbo; rapidjson e C)
        package.loaded["httpclient"] = {
            new = function()
                return {
                    request = function(_, opts, callback)
                        last_request = opts
                        opts.on_headers(header_object())
                        callback(response)
                    end,
                }
            end,
        }
        package.loaded["rapidjson"] = {
            encode = function(value)
                local parts = {}
                for k, v in pairs(value) do
                    parts[#parts + 1] = tostring(k) .. "=" .. tostring(v)
                end
                table.sort(parts)
                return "{" .. table.concat(parts, ",") .. "}"
            end,
            decode = function(body)
                return { raw = body }, nil
            end,
        }
    end)

    after_each(function()
        package.loaded["httpclient"] = nil
        package.loaded["rapidjson"] = nil
    end)

    it("monta a URL sem barra duplicada e pede o caminho certo", function()
        local client = http_client.new({ base_url = "http://10.0.0.5:8080/" })

        client:get_json("/api/sync?since=3")

        assert.equals("http://10.0.0.5:8080/api/sync?since=3", last_request.url)
        assert.equals("GET", last_request.method)
    end)

    it("manda token e content-type quando ha corpo", function()
        local client = http_client.new({ base_url = "http://api", token = "jwt-123" })

        client:post_json("/api/sync/ack", { syncToken = 9 })

        assert.equals("Bearer jwt-123", last_headers["authorization"])
        assert.equals("application/json", last_headers["accept"])
        assert.equals("application/json", last_headers["content-type"])
        assert.equals("{syncToken=9}", last_request.body)
    end)

    it("nao manda authorization sem token, nem content-type sem corpo", function()
        local client = http_client.new({ base_url = "http://api" })

        client:get_json("/api/sync?since=0")

        assert.is_nil(last_headers["authorization"])
        assert.is_nil(last_headers["content-type"])
    end)

    it("devolve o corpo ja decodificado", function()
        local client = http_client.new({ base_url = "http://api" })

        assert.equals('{"ok":true}', client:get_json("/api/sync?since=0").raw)
    end)

    it("levanta erro com o status preservado", function()
        response = { code = 401, body = '{"message":"Token expirado"}' }
        local client = http_client.new({ base_url = "http://api" })

        local ok, err = pcall(function() client:get_json("/api/sync?since=1") end)

        assert.is_false(ok)
        assert.is_true(http_client.is_unauthorized(err))
        assert.equals(401, err.code)
        assert.is_not_nil(tostring(err):find("401"))
    end)

    it("is_unauthorized distingue erro de HTTP de erro de Lua", function()
        assert.is_false(http_client.is_unauthorized("erro de Lua"))
        assert.is_false(http_client.is_unauthorized(nil))
    end)

    it("login guarda o token devolvido", function()
        package.loaded["rapidjson"].decode = function()
            return { token = "jwt-novo", expiresAt = "2026-09-29T15:00:00Z" }, nil
        end
        local client = http_client.new({ base_url = "http://api" })

        local answer = client:login("leitor", "senha")

        assert.equals("jwt-novo", answer.token)
        assert.equals("jwt-novo", client.token)
        assert.equals("http://api/api/auth/login", last_request.url)
        assert.equals("POST", last_request.method)
    end)

    it("204 sem corpo nao quebra", function()
        response = { code = 204, body = "" }
        local client = http_client.new({ base_url = "http://api" })

        assert.is_nil(client:post_json("/api/sync/ack", { syncToken = 1 }))
    end)
end)
