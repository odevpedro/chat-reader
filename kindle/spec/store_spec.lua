-- Store em SQLite contra o mesmo binding do KOReader (lua-ljsqlite3), em :memory:.
--
-- O que este spec garante: o SQL do store/sqlite.lua roda (aqui e no Kindle), e a
-- logica de sync funciona sobre a interface real — e nao sobre a de memoria.

local store_sqlite = require("store.sqlite")
local sync = require("client.sync")

describe("store/sqlite", function()
    local store

    before_each(function()
        store = store_sqlite.open(":memory:")
    end)

    after_each(function()
        store:close()
    end)

    it("cria o schema e registra a versao", function()
        assert.equals(require("store.sqlite").SCHEMA_VERSION, store:schema_version())
        assert.equals(0, store:count("chats"))
    end)

    it("persiste o token entre aberturas", function()
        store:save_token(42)
        assert.equals(42, store:get_token())

        local outro = store_sqlite.open(":memory:")
        assert.equals(0, outro:get_token()) -- banco novo, nao ha token
        outro:close()
    end)

    it("guarda conversa com apostro no titulo e na mensagem", function()
        store:put_chat({
            id = "c1",
            title = "Spring: por que o '@Transactional' nao funciona?",
            source = "JSON",
            externalId = "ext-1",
            contentVersion = 3,
            messageCount = 1,
            updatedAt = "2026-09-29T15:00:00Z",
            messagesOmitted = false,
            messages = {
                { id = "m1", role = "USER", sequence = 0,
                  content = "Nao era pra usar 'a' de ao inves de a?" },
            },
            tags = {},
        })

        local chat = store:get_chat("c1")
        assert.equals("Spring: por que o '@Transactional' nao funciona?", chat.title)

        local rows = store:list_messages("c1", 10, 0)
        assert.equals(1, #rows)
        assert.equals("Nao era pra usar 'a' de ao inves de a?", rows[1].content)
    end)

    it("atualiza conversa sem duplicar nem perder mensagens", function()
        store:put_chat({
            id = "c1", title = "A", messageCount = 1, messagesOmitted = false,
            messages = { { id = "m1", role = "USER", sequence = 0, content = "oi" } },
            tags = {},
        })
        store:put_chat({
            id = "c1", title = "B", messageCount = 2, messagesOmitted = false,
            messages = {
                { id = "m1", role = "USER", sequence = 0, content = "oi" },
                { id = "m2", role = "ASSISTANT", sequence = 1, content = "ola" },
            },
            tags = {},
        })

        assert.equals(1, store:count("chats"))
        assert.equals("B", store:get_chat("c1").title)
        assert.equals(2, store:count("messages"))
    end)

    it("mantem as mensagens quando a conversa vem sem elas (messagesOmitted)", function()
        store:put_chat({
            id = "c1", title = "Grande", messageCount = 300, messagesOmitted = false,
            messages = { { id = "m1", role = "USER", sequence = 0, content = "oi" } },
            tags = {},
        })
        -- o delta seguinte traz so o metadado
        store:put_chat({ id = "c1", title = "Grande", messageCount = 300,
                         messagesOmitted = true, tags = {} })

        assert.equals(1, store:count("messages"))
        assert.equals(300, store:get_chat("c1").messageCount)
    end)

    it("troca o conjunto de tags em vez de acumular", function()
        store:put_chat({
            id = "c1", title = "A", messagesOmitted = false, messages = {},
            tags = { { id = "t1", name = "Java" }, { id = "t2", name = "Spring" } },
        })
        store:put_chat({
            id = "c1", title = "A", messagesOmitted = false, messages = {},
            tags = { { id = "t1", name = "Java" } },
        })

        assert.equals(2, store:count("tags")) -- catalogo global nao e limpo
        local row = store:read_one(
            "SELECT COUNT(*) AS total FROM chat_tags WHERE chat_id = 'c1';")
        assert.equals(1, tonumber(row.total))
    end)

    it("apagar conversa leva junto mensagens e bookmarks", function()
        store:put_chat({
            id = "c1", title = "A", messagesOmitted = false,
            messages = { { id = "m1", role = "USER", sequence = 0, content = "oi" } },
            tags = {},
        })
        store:put_bookmark({ id = "b1", chatId = "c1", messageId = "m1" })

        store:delete_chat("c1")

        assert.equals(0, store:count("chats"))
        assert.equals(0, store:count("messages"))
        assert.equals(0, store:count("bookmarks"))
    end)

    it("clear() limpa o estado mas nao o token", function()
        store:put_chat({ id = "c1", title = "A", messagesOmitted = false, messages = {},
                         tags = {} })
        store:save_token(7)

        store:clear()

        assert.equals(0, store:count("chats"))
        assert.equals(7, store:get_token())
    end)

    it("transaction reverte tudo quando a funcao falha no meio", function()
        store:put_chat({ id = "antiga", title = "Fica", messagesOmitted = false,
                         messages = {}, tags = {} })
        store:save_token(3)

        assert.has_error(function()
            store:transaction(function()
                store:delete_chat("antiga")
                store:save_token(99)
                error("falha no meio")
            end)
        end)

        -- nem a gravacao nem o token podem ter sobrado
        assert.equals(1, store:count("chats"))
        assert.is_not_nil(store:get_chat("antiga"))
        assert.equals(3, store:get_token())
    end)

    it("transaction nao aninha", function()
        assert.has_error(function()
            store:transaction(function()
                store:transaction(function() end)
            end)
        end)
    end)
end)

describe("sync sobre o store real", function()
    local http, store

    before_each(function()
        store = store_sqlite.open(":memory:")
        http = { responses = {} }
        function http:get_json(path) return self.responses[path] end
    end)

    after_each(function()
        store:close()
    end)

    it("popula o banco a partir do snapshot", function()
        http.responses["/api/sync?since=0&limit=200"] = {
            syncToken = 5, hasMore = false, fullResyncRequired = true, changes = {},
        }
        http.responses["/api/sync/snapshot?page=0&size=200"] = {
            syncToken = 5, page = 0, size = 200, total = 2, last = true,
            tags = { { id = "t1", name = "Java" } },
            chats = {
                { id = "c1", title = "Primeira", messageCount = 1, messagesOmitted = false,
                  tags = { { id = "t1", name = "Java" } },
                  messages = { { id = "m1", role = "USER", sequence = 0, content = "oi" } } },
                { id = "c2", title = "Segunda", messageCount = 0, messagesOmitted = false,
                  tags = {}, messages = {} },
            },
            bookmarks = { { id = "b1", chatId = "c1", messageId = "m1" } },
            bookmarksOmitted = false,
        }

        sync.run(http, store, 0)

        assert.equals(2, store:count("chats"))
        assert.equals(1, store:count("messages"))
        assert.equals(1, store:count("tags"))
        assert.equals(1, store:count("bookmarks"))
        assert.equals(5, store:get_token())
    end)

    it("aplica delta incremental e remove o que foi deletado no servidor", function()
        store:put_chat({ id = "c9", title = "Antiga", messagesOmitted = false,
                         messages = {}, tags = {} })
        store:save_token(10)
        http.responses["/api/sync?since=10&limit=200"] = {
            syncToken = 12, hasMore = false, fullResyncRequired = false,
            changes = {
                { version = 11, type = "CHAT_UPDATED", entityId = "c1", chatId = "c1",
                  chat = { id = "c1", title = "Nova", messageCount = 0,
                           messagesOmitted = false, messages = {}, tags = {} } },
                { version = 12, type = "CHAT_DELETED", entityId = "c9", chatId = "c9" },
            },
        }

        local result = sync.run(http, store, 10)

        assert.is_false(result.applied_snapshot)
        assert.equals(2, result.changes)
        assert.equals(1, store:count("chats"))
        assert.equals("Nova", store:get_chat("c1").title)
        assert.is_nil(store:get_chat("c9"))
        assert.equals(12, store:get_token())
    end)

    it("confirma o token no servidor ao fim do ciclo", function()
        store:save_token(4)
        http.responses["/api/sync?since=4&limit=200"] = {
            syncToken = 4, hasMore = false, fullResyncRequired = false, changes = {},
        }
        local posted = {}
        function http:post_json(path, body) posted[path] = body end

        local result = sync.run(http, store, 4)

        assert.is_nil(result.ack_error)
        assert.same({ ackVersion = 4 }, posted["/api/sync/ack"])
    end)

    it("token nao avanca quando a gravacao da pagina falha", function()
        store:save_token(20)
        http.responses["/api/sync?since=20&limit=200"] = {
            syncToken = 21, hasMore = false, fullResyncRequired = false,
            changes = {
                { version = 21, type = "CHAT_UPDATED", chat = { title = "sem id" } },
            },
        }

        -- payload sem id quebra a gravacao; o ciclo levanta e o token fica para tras
        assert.has_error(function() sync.run(http, store, 20) end)

        assert.equals(20, store:get_token())
    end)

    it("recusa entidade sem id em vez de gravar chave nula", function()
        assert.has_error(function() store:put_chat({ title = "sem id" }) end)
        assert.has_error(function() store:put_tag({ name = "sem id" }) end)
        assert.has_error(function() store:put_bookmark({ chatId = "c1" }) end)
        assert.equals(0, store:count("chats"))
    end)

    it("snapshot remove o que ficou de fora, sem limpar antes de gravar", function()
        store:put_chat({ id = "obsoleta", title = "Obsoleta", messagesOmitted = false,
                         messages = {}, tags = {} })
        store:save_token(1)
        http.responses["/api/sync?since=1&limit=200"] = {
            syncToken = 8, hasMore = false, fullResyncRequired = true, changes = {},
        }
        http.responses["/api/sync/snapshot?page=0&size=200"] = {
            syncToken = 8, last = true, tags = {},
            chats = { { id = "c1", title = "Fica", messageCount = 0,
                        messagesOmitted = false, messages = {}, tags = {} } },
            bookmarks = {},
        }

        local result = sync.run(http, store, 1)

        assert.is_true(result.applied_snapshot)
        assert.is_nil(store:get_chat("obsoleta"))
        assert.is_not_nil(store:get_chat("c1"))
        assert.equals(8, store:get_token())
    end)

    it("snapshot interrompido no meio nao apaga a biblioteca", function()
        store:put_chat({ id = "c1", title = "Continua aqui", messagesOmitted = false,
                         messages = {}, tags = {} })
        store:save_token(1)
        http.responses["/api/sync?since=1&limit=200"] = {
            syncToken = 8, hasMore = false, fullResyncRequired = true, changes = {},
        }
        http.responses["/api/sync/snapshot?page=0&size=200"] = {
            syncToken = 8, last = false, tags = {},
            chats = { { id = "c1", title = "Continua aqui", messageCount = 0,
                        messagesOmitted = false, messages = {}, tags = {} } },
            bookmarks = {},
        }
        -- segunda pagina falha: nada foi apagado e o token nao avanca
        function http:get_json(path)
            if path:find("snapshot") then error("sem rede") end
            return self.responses[path]
        end

        assert.has_error(function() sync.run(http, store, 1) end)

        assert.equals(1, store:count("chats"))
        assert.is_not_nil(store:get_chat("c1"))
        assert.equals(1, store:get_token())
    end)
end)
