-- Testes do adapter HTTP com LuaSocket e rapidjson stubados.
--
-- O adapter e IO puro em volta do LuaSocket, entao o que vale testar aqui e a parte
-- que quebra sem o dispositivo: montagem da URL, dos headers, do corpo JSON, do
-- tratamento de status e do token. O LuaSocket em si ja e testado pelo proprio
-- KOReader (frontend/socketutil.lua).
--
-- O stub de socket.http imita o contrato real de http.request(tabela):
--   * devolve 1, code, headers, status em sucesso e lanca/nil, erro em falha de
--     transporte (common/socket/http.lua, trequest);
--   * le o corpo pelo campo `source` (uma fonte ltn12) e entrega a resposta pelo
--     campo `sink` — e assim que o corpo do POST chega ao teste.

local http_client = require("client.http")

describe("client/http", function()
    local last_request
    local last_body
    local last_headers
    local response

    -- consome a fonte ltn12 do pedido ate nao devolver mais chunk
    local function read_source(source)
        if not source then return nil end
        local parts = {}
        while true do
            local chunk = source()
            if not chunk then break end
            parts[#parts + 1] = chunk
        end
        return table.concat(parts)
    end

    before_each(function()
        last_request = nil
        last_body = nil
        last_headers = nil
        response = { code = 200, body = '{"ok":true}' }

        -- stubs no lugar dos modulos do KOReader (socket.http, ltn12 e socketutil;
        -- rapidjson e .so do dispositivo)
        package.loaded["socket.http"] = {
            TIMEOUT = nil,
            request = function(request)
                last_request = request
                last_headers = request.headers
                last_body = read_source(request.source)
                if response.raise then error(response.raise) end
                if response.body and response.body ~= "" and request.sink then
                    request.sink(response.body)
                end
                if response.code == nil then return nil, response.err end
                return 1, response.code
            end,
        }
        package.loaded["ltn12"] = {
            source = {
                string = function(text)
                    local sent = false
                    return function()
                        if sent then return nil end
                        sent = true
                        return text
                    end
                end,
            },
        }
        package.loaded["socketutil"] = {
            set_timeout = function() end,
            reset_timeout = function() end,
            table_sink = function(t)
                return function(chunk)
                    if chunk then t[#t + 1] = chunk end
                    return 1
                end
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
        package.loaded["socket.http"] = nil
        package.loaded["ltn12"] = nil
        package.loaded["socketutil"] = nil
        package.loaded["rapidjson"] = nil
    end)

    it("monta a URL sem barra duplicada e pede o caminho certo", function()
        local client = http_client.new({ base_url = "http://10.0.0.5:8080/" })

        client:get_json("/api/sync?since=3")

        assert.equals("http://10.0.0.5:8080/api/sync?since=3", last_request.url)
        assert.equals("GET", last_request.method)
    end)

    it("manda token, content-type e content-length quando ha corpo", function()
        local client = http_client.new({ base_url = "http://api", token = "jwt-123" })

        client:post_json("/api/sync/ack", { syncToken = 9 })

        assert.equals("Bearer jwt-123", last_headers["authorization"])
        assert.equals("application/json", last_headers["accept"])
        assert.equals("application/json", last_headers["content-type"])
        assert.equals("{syncToken=9}", last_body)
        assert.equals(tostring(#last_body), last_headers["content-length"])
    end)

    it("nao manda authorization sem token, nem content-type sem corpo", function()
        local client = http_client.new({ base_url = "http://api" })

        client:get_json("/api/sync?since=0")

        assert.is_nil(last_headers["authorization"])
        assert.is_nil(last_headers["content-type"])
        assert.is_nil(last_headers["content-length"])
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

    it("erro de transporte vira HTTP 0 e nunca parece 401", function()
        response = { raise = "timeout" }
        local client = http_client.new({ base_url = "http://api" })

        local ok, err = pcall(function() client:get_json("/api/sync?since=1") end)

        assert.is_false(ok)
        assert.is_false(http_client.is_unauthorized(err))
        assert.equals(0, err.code)
        assert.is_not_nil(tostring(err):find("timeout", 1, true))
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
        assert.is_not_nil(last_body:find("username=leitor", 1, true))
        assert.is_not_nil(last_body:find("password=senha", 1, true))
    end)

    it("204 sem corpo nao quebra", function()
        response = { code = 204, body = "" }
        local client = http_client.new({ base_url = "http://api" })

        assert.is_nil(client:post_json("/api/sync/ack", { syncToken = 1 }))
    end)
end)
