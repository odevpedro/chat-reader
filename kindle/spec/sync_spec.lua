-- Testes da porta ClientHttp e do sync, em Lua puro (sem KOReader e sem rede).
--
-- O contrato vem de docs/sync-protocol.md; aqui so exercitamos a logica que roda no
-- dispositivo. O adapter real de HTTP (require("httpclient")) entra depois, atras da
-- mesma porta.
local sync = require("client.sync")

describe("sync", function()
    local http, store

    before_each(function()
        -- fake do transporte: registra o que foi pedido e devolve fixtures
        http = {
            calls = {},
            responses = {},
        }
        function http:get_json(path)
            table.insert(self.calls, { method = "GET", path = path })
            local answer = self.responses[path]
            assert(answer, "fixture ausente para " .. path)
            return answer
        end
        function http:post_json(path, body)
            table.insert(self.calls, { method = "POST", path = path, body = body })
            return self.responses[path]
        end
        store = sync.new_store()
    end)

    describe("primeira sincronizacao", function()
        it("pede snapshot quando o servidor diz que precisa de full resync", function()
            http.responses["/api/sync?since=0&limit=200"] = {
                syncToken = 7,
                hasMore = false,
                fullResyncRequired = true,
                changes = {},
            }
            http.responses["/api/sync/snapshot?page=0&size=200"] = {
                syncToken = 7,
                page = 0,
                size = 200,
                total = 1,
                last = true,
                tags = { { id = "t1", name = "Java" } },
                chats = {
                    {
                        id = "c1",
                        title = "Spring Transactional",
                        messageCount = 2,
                        messagesOmitted = false,
                        messages = {
                            { id = "m1", role = "USER", sequence = 0, content = "oi" },
                            { id = "m2", role = "ASSISTANT", sequence = 1, content = "ola" },
                        },
                    },
                },
                bookmarks = {},
                bookmarksOmitted = false,
            }

            local result = sync.run(http, store, 0)

            assert.is_true(result.applied_snapshot)
            assert.equals(2, store:count("messages"))
            assert.equals(1, store:count("chats"))
            -- o token do snapshot e o que o cliente passa a usar
            assert.equals(7, store:get_token())
        end)

        it("guarda o token mesmo sem nenhuma conversa", function()
            http.responses["/api/sync?since=0&limit=200"] = {
                syncToken = 3,
                fullResyncRequired = true,
                changes = {},
            }
            http.responses["/api/sync/snapshot?page=0&size=200"] = {
                syncToken = 3, total = 0, last = true, tags = {}, chats = {},
                bookmarks = {}, bookmarksOmitted = false,
            }

            sync.run(http, store, 0)

            assert.equals(0, store:count("chats"))
            assert.equals(3, store:get_token())
        end)
    end)

    describe("delta incremental", function()
        it("aplica mudancas e avanca o token", function()
            store:save_token(10)
            http.responses["/api/sync?since=10&limit=200"] = {
                syncToken = 12,
                hasMore = false,
                fullResyncRequired = false,
                changes = {
                    {
                        version = 11, type = "CHAT_UPDATED", entityType = "CHAT",
                        entityId = "c1", chatId = "c1", contentVersion = 3,
                        chat = { id = "c1", title = "Renomeado", messageCount = 1,
                                 messagesOmitted = false, messages = {} },
                    },
                    { version = 12, type = "CHAT_DELETED", entityType = "CHAT",
                      entityId = "c2", chatId = "c2" },
                },
            }

            local result = sync.run(http, store, 10)

            assert.is_false(result.applied_snapshot)
            assert.equals(1, store:count("chats"))
            assert.equals("Renomeado", store:get_chat("c1").title)
            assert.equals(12, store:get_token())
        end)

        it("repete a requisicao enquanto hasMore for true", function()
            store:save_token(20)
            -- o servidor sinaliza que ha mais com hasMore, independente do limit
            http.responses["/api/sync?since=20&limit=200"] = {
                syncToken = 21, hasMore = true, fullResyncRequired = false,
                changes = { { version = 21, type = "CHAT_CREATED", entityType = "CHAT",
                              entityId = "c1", chatId = "c1",
                              chat = { id = "c1", title = "A", messageCount = 0,
                                       messagesOmitted = false, messages = {} } } },
            }
            http.responses["/api/sync?since=21&limit=200"] = {
                syncToken = 22, hasMore = false, fullResyncRequired = false,
                changes = { { version = 22, type = "CHAT_CREATED", entityType = "CHAT",
                              entityId = "c2", chatId = "c2",
                              chat = { id = "c2", title = "B", messageCount = 0,
                                       messagesOmitted = false, messages = {} } } },
            }

            sync.run(http, store, 20)

            -- duas paginas de delta; o ack do fim do ciclo e outra chamada
            local pages = 0
            for _, call in ipairs(http.calls) do
                if call.method == "GET" then pages = pages + 1 end
            end
            assert.equals(2, pages)
            assert.equals(2, store:count("chats"))
            assert.equals(22, store:get_token())
        end)
    end)

    describe("respostas que exigem atencao", function()
        it("nao mexe no estado quando fullResyncRequired vem no meio da paginacao", function()
            store:save_token(30)
            store:put_chat({ id = "c1", title = "Existente" })
            http.responses["/api/sync?since=30&limit=200"] = {
                syncToken = 99, hasMore = true, fullResyncRequired = true, changes = {},
            }
            http.responses["/api/sync/snapshot?page=0&size=200"] = {
                syncToken = 99, total = 0, last = true, tags = {}, chats = {},
                bookmarks = {}, bookmarksOmitted = false,
            }

            local result = sync.run(http, store, 30)

            -- o snapshot substitui tudo: o chat que estava no store sumiu
            assert.is_true(result.applied_snapshot)
            assert.equals(0, store:count("chats"))
            assert.equals(99, store:get_token())
        end)
    end)
end)
