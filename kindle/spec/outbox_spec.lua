-- Escritas offline e ACK: o caminho que faz favorito e bookmark criados no Kindle
-- sem rede chegarem ao servidor (docs/sync-protocol.md, secoes 5 e 6).
--
-- Tudo roda contra os dois stores, porque a interface e o contrato: se o SQLite e o
-- store em memoria divergirem, o bug e do store, nao do sync.

local sync = require("client.sync")

local function stores()
    return {
        { nome = "memoria", open = function() return sync.new_store() end },
        { nome = "sqlite", open = function() return require("store.sqlite").open(":memory:") end },
    }
end

describe("escritas offline", function()
    for _, alvo in ipairs(stores()) do
        describe("no store de " .. alvo.nome, function()
            local store, http, posted

            before_each(function()
                store = alvo.open()
                http = { answers = {} }
                function http:post_json(path, body)
                    posted = { path = path, body = body }
                    return self.answers[path] or { ackVersion = body.ackVersion, accepted = 0,
                                                   rejected = 0, rejections = {} }
                end
                function http:get_json(path) error("sem rede neste spec") end
            end)

            after_each(function()
                store:close()
            end)

            it("favoritar aparece na hora e vai para a fila", function()
                store:put_chat({ id = "c1", title = "A", messagesOmitted = false,
                                 messages = {}, tags = {} })

                store:set_favorite_offline("c1", true)

                assert.is_true(store:get_chat("c1").favorite)
                assert.equals(1, store:count_local())
                local fila = store:pending_local(10)
                assert.equals("CHAT_FAVORITE", fila[1].type)
                assert.equals("c1", fila[1].chatId)
                assert.is_true(fila[1].favorite)
            end)

            it("bookmark criado vai para a fila com o id gerado no cliente", function()
                store:bookmark_offline({ id = "b-local-1", chatId = "c1", messageId = "m1",
                                         note = "relevar" })

                assert.equals(1, store:count("bookmarks"))
                local fila = store:pending_local(10)
                assert.equals("BOOKMARK_CREATED", fila[1].type)
                assert.equals("b-local-1", fila[1].refId)
                assert.equals("m1", fila[1].messageId)
            end)

            it("remover bookmark enfileira a delecao", function()
                store:bookmark_offline({ id = "b-local-1", chatId = "c1" })
                store:unbookmark_offline("b-local-1")

                assert.equals(0, store:count("bookmarks"))
                local fila = store:pending_local(10)
                assert.equals(2, #fila)
                assert.equals("BOOKMARK_DELETED", fila[2].type)
                assert.equals("b-local-1", fila[2].refId)
            end)

            it("estado vindo do sync nao entra na fila", function()
                store:put_chat({ id = "c1", title = "A", favorite = true,
                                 messagesOmitted = false, messages = {}, tags = {} })
                store:put_bookmark({ id = "b-servidor", chatId = "c1" })

                assert.equals(0, store:count_local())
            end)

            it("limpar o store nao descarta o que falta subir", function()
                store:bookmark_offline({ id = "b-local-1", chatId = "c1" })

                store:clear()

                assert.equals(1, store:count_local())
            end)
        end)
    end
end)

describe("ACK", function()
    local store, http, posted

    before_each(function()
        store = sync.new_store()
        http = {}
        function http:post_json(path, body)
            posted = { path = path, body = body }
            return { ackVersion = body.ackVersion, syncToken = 9999, accepted = 0,
                     rejected = 0, rejections = {} }
        end
    end)

    it("manda ackVersion, nao syncToken", function()
        sync.ack(http, store, 2310, "kobo-clara")

        assert.equals("/api/sync/ack", posted.path)
        assert.equals(2310, posted.body.ackVersion)
        assert.is_nil(posted.body.syncToken)
        assert.equals("kobo-clara", posted.body.clientId)
    end)

    it("nao adota o syncToken devolvido pelo ACK", function()
        store:save_token(50)

        local result = sync.ack(http, store, 50)

        -- o token local so muda no pull: 9999 pode conter mudancas de outro device
        assert.equals(50, store:get_token())
        assert.equals(0, result.sent) -- nada na fila, nada enviado
    end)

    it("sobe a fila e limpa o que foi aceito", function()
        store:set_favorite_offline("c1", true)
        store:bookmark_offline({ id = "b1", chatId = "c1", messageId = "m1" })
        http.post_json = function(self, path, body)
            posted = { path = path, body = body }
            return { accepted = 2, rejected = 0, rejections = {} }
        end

        local result = sync.ack(http, store, 3)

        assert.equals(2, #posted.body.localChanges)
        assert.equals("CHAT_FAVORITE", posted.body.localChanges[1].type)
        assert.same({ chatId = "c1", favorite = true }, posted.body.localChanges[1].payload)
        assert.equals("BOOKMARK_CREATED", posted.body.localChanges[2].type)
        assert.same({ id = "b1", chatId = "c1", messageId = "m1" },
            posted.body.localChanges[2].payload)
        assert.equals(2, result.sent)
        assert.equals(0, store:count_local())
    end)

    it("item recusado continua na fila, com o motivo", function()
        store:bookmark_offline({ id = "b1", chatId = "c1", messageId = "m1" })
        store:set_favorite_offline("c1", true)
        http.post_json = function(self, path, body)
            posted = body
            return { accepted = 1, rejected = 1,
                     rejections = { { index = 0, reason = "NOT_FOUND",
                                      message = "Mensagem nao encontrada" } } }
        end

        local result = sync.ack(http, store, 3)

        -- indice 0 recusado (o primeiro da fila), o segundo subiu
        assert.equals(1, store:count_local())
        assert.equals("BOOKMARK_CREATED", store:pending_local(10)[1].type)
        assert.equals(1, #result.rejected)
        assert.equals("NOT_FOUND", result.rejected[1].reason)
    end)

    it("respeita o indice da resposta, nao a ordem do resultado", function()
        store:bookmark_offline({ id = "b1", chatId = "c1" })
        store:bookmark_offline({ id = "b2", chatId = "c1" })
        store:bookmark_offline({ id = "b3", chatId = "c1" })
        http.post_json = function()
            return { accepted = 2, rejected = 1,
                     rejections = { { index = 1, reason = "CONFLICT", message = "x" } } }
        end

        sync.ack(http, store, 3)

        -- so o item de indice 1 volta; os outros dois subiram
        local fila = store:pending_local(10)
        assert.equals(1, #fila)
        assert.equals("b2", fila[1].refId)
    end)

    it("fila grande e limitada por lote, e o que sobra fica", function()
        for i = 1, sync.ACK_BATCH + 5 do
            store:bookmark_offline({ id = "b" .. i, chatId = "c1" })
        end

        sync.ack(http, store, 3)

        assert.equals(sync.ACK_BATCH, #posted.body.localChanges)
        assert.equals(5, store:count_local())
    end)

    it("sem pendencias nao manda localChanges", function()
        sync.ack(http, store, 3)

        assert.is_nil(posted.body.localChanges)
        assert.equals(3, posted.body.ackVersion)
    end)
end)

describe("ciclo completo com escrita offline", function()
    it("favorita offline e o servidor devolve o estado no pull seguinte", function()
        local store = sync.new_store()
        store:put_chat({ id = "c1", title = "A", messagesOmitted = false, messages = {},
                         tags = {} })
        store:set_favorite_offline("c1", true)

        local ack_body
        local http = { responses = {} }
        function http:get_json(path) return self.responses[path] end
        function http:post_json(path, body)
            ack_body = body
            return { accepted = 1, rejected = 0, rejections = {}, syncToken = 77 }
        end
        http.responses["/api/sync?since=0&limit=200"] = {
            syncToken = 76, hasMore = false, fullResyncRequired = false, changes = {},
        }

        local result = sync.run(http, store, 0, "kobo-1")

        assert.is_true(result.ack_error == nil)
        assert.equals(1, result.ack.sent)
        assert.equals(0, store:count_local())
        assert.equals(76, store:get_token())
        assert.equals(76, ack_body.ackVersion)
        -- o favorito continua valendo localmente
        assert.is_true(store:get_chat("c1").favorite)
    end)
end)
