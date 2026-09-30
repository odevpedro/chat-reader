-- Logica de tela da biblioteca, sem KOReader (client/library.lua).
--
-- E o que os specs conseguem verificar sem dispositivo: o que entra na lista, a
-- ordem, o subtitulo e o recorte em volta do termo buscado. Se a UI montar a linha na
-- mao, nada disso e testavel e nada disso trava.

local library = require("client.library")
local sync = require("client.sync")

local function stores()
    return {
        { nome = "memoria", open = function() return sync.new_store() end },
        { nome = "sqlite", open = function() return require("store.sqlite").open(":memory:") end },
    }
end

describe("data", function()
    it("lê a data do ISO-8601 que o backend manda", function()
        local when = library.parse_when("2026-03-07T21:04:11Z")
        assert.same({ year = 2026, month = 3, day = 7 }, when)
    end)

    it("formata em dia/mes/ano", function()
        assert.equals("07/03/2026", library.when_label("2026-03-07T21:04:11Z"))
        assert.equals("07/03/2026", library.when_label("2026-03-07"))
    end)

    it("devolve nil para data ausente ou inesperada", function()
        assert.is_nil(library.parse_when(nil))
        assert.is_nil(library.parse_when("ontem"))
        assert.is_nil(library.when_label(42))
    end)
end)

describe("linha de conversa", function()
    it("junta contagem e data no subtitulo", function()
        local row = library.chat_row({ id = "c1", title = "Titulo", messageCount = 1,
                                       updatedAt = "2026-03-07T00:00:00Z" })
        assert.equals("1 mensagem · 07/03/2026", row.subtitle)
    end)

    it("acerta o plural", function()
        assert.equals("12 mensagens", library.chat_row({ id = "c1", messageCount = 12 }).subtitle)
    end)

    it("diz sem mensagens quando nao ha contagem nem data", function()
        assert.equals("sem mensagens", library.chat_subtitle({ id = "c1" }))
    end)

    it("marca os favoritos e nunca perde o titulo", function()
        assert.is_true(library.chat_row({ id = "c1", favorite = true }).favorite)
        assert.is_falsy(library.chat_row({ id = "c1" }).favorite)
        assert.equals("(sem titulo)", library.chat_row({ id = "c1" }).title)
    end)
end)

describe("linhas da biblioteca", function()
    for _, alvo in ipairs(stores()) do
        describe("no store de " .. alvo.nome, function()
            local store

            before_each(function()
                store = alvo.open()
                store:put_chat({ id = "c1", title = "Uma", messageCount = 3,
                                 updatedAt = "2026-03-01T00:00:00Z" })
                store:put_chat({ id = "c2", title = "Duas", messageCount = 10, favorite = true,
                                 updatedAt = "2026-02-01T00:00:00Z" })
            end)

            after_each(function()
                store:close()
            end)

            it("mantem a ordem do store (mais atualizada primeiro)", function()
                local rows = library.chat_rows(store)
                assert.equals(2, #rows)
                assert.equals("c1", rows[1].id)
            end)

            it("filtra favoritos", function()
                local rows = library.chat_rows(store, { only_favorites = true })
                assert.equals(1, #rows)
                assert.equals("c2", rows[1].id)
            end)

            it("pagina", function()
                local rows = library.chat_rows(store, { limit = 1, offset = 1 })
                assert.equals(1, #rows)
                assert.equals("c2", rows[1].id)
            end)
        end)
    end
end)

describe("trecho da busca", function()
    local function repeticao(n)
        return string.rep("palavra ", n) .. "alvo" .. string.rep(" depois", n)
    end

    it("recorta em volta do termo, nao do inicio", function()
        local out = library.snippet(repeticao(40), "alvo", 60)
        assert.is_true(out:find("alvo", 1, true) ~= nil)
        assert.is_true(out:sub(1, 3) == "...")
        assert.is_true(#out < 90)
    end)

    it("devolve o texto inteiro quando cabe", function()
        assert.equals("curto", library.snippet("curto", "curto", 60))
    end)

    it("junta quebras de linha, que quebrariam a linha do botao", function()
        assert.equals("a b c", library.snippet("a\n\nb\n  c", "b", 60))
    end)

    it("aguenta conteudo ausente e termo vazio", function()
        assert.equals("", library.snippet(nil, "x", 60))
        assert.equals("sem alvo", library.snippet("sem alvo", "", 60))
    end)
end)

describe("linhas da busca", function()
    it("leva chat e seq para o leitor pular na mensagem certa", function()
        local rows = library.search_rows({
            { chat_id = "c1", message_id = "m7", seq = 7, title = "Conversa",
              content = "aqui esta o termo" },
        }, "termo", 60)
        assert.equals(1, #rows)
        assert.same({ chatId = "c1", seq = 7, messageId = "m7", title = "Conversa",
                      snippet = "aqui esta o termo" }, rows[1])
    end)
end)
