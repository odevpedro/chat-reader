-- Consultas que a interface usa: biblioteca ordenada, filtro de favoritos, onde a
-- mensagem cai na conversa e se ela esta favoritada/bookmarkada.
--
-- Roda contra os dois stores de proposito. A UI e escrita uma vez e fala com a
-- interface; se o store em memoria ordenar diferente do SQL, a biblioteca mudaria de
-- ordem conforme o store — e nenhum teste de tela pegaria isso.

local sync = require("client.sync")

local function stores()
    return {
        { nome = "memoria", open = function() return sync.new_store() end },
        { nome = "sqlite", open = function() return require("store.sqlite").open(":memory:") end },
    }
end

--_fixture: 3 conversas com datas distintas, uma favoritada, e mensagens com seq
local function povoar(store)
    store:put_chat({ id = "c-antiga", title = "Antiga", updatedAt = "2026-01-01T00:00:00Z" })
    store:put_chat({ id = "c-nova", title = "Nova", updatedAt = "2026-03-01T00:00:00Z",
                     messageCount = 3 })
    store:put_chat({ id = "c-meio", title = "Meio", updatedAt = "2026-02-01T00:00:00Z",
                     favorite = true })
    store:replace_messages("c-nova", {
        { id = "m1", role = "user", seq = 1, content = "primeira" },
        { id = "m2", role = "assistant", seq = 2, content = "segunda sobre apples" },
        { id = "m3", role = "user", seq = 3, content = "terceira" },
    })
end

describe("consultas da biblioteca", function()
    for _, alvo in ipairs(stores()) do
        describe("no store de " .. alvo.nome, function()
            local store

            before_each(function()
                store = alvo.open()
                povoar(store)
            end)

            after_each(function()
                store:close()
            end)

            it("ordena por atualizacao, da mais recente para a mais antiga", function()
                local chats = store:list_chats()
                assert.equals("c-nova", chats[1].id)
                assert.equals("c-meio", chats[2].id)
                assert.equals("c-antiga", chats[3].id)
            end)

            it("filtra só os favoritos quando pedido", function()
                local favoritos = store:list_chats(nil, nil, true)
                assert.equals(1, #favoritos)
                assert.equals("c-meio", favoritos[1].id)
            end)

            it("pagina com limit e offset", function()
                local primeira = store:list_chats(2)
                assert.equals(2, #primeira)
                assert.equals("c-nova", primeira[1].id)

                local resto = store:list_chats(2, 2)
                assert.equals(1, #resto)
                assert.equals("c-antiga", resto[1].id)
            end)

            it("conta as mensagens da conversa", function()
                assert.equals(3, store:count_messages("c-nova"))
                assert.equals(0, store:count_messages("c-antiga"))
            end)

            it("acha a posicao da mensagem para pular direto nela", function()
                assert.equals(0, store:message_position("c-nova", 1))
                assert.equals(1, store:message_position("c-nova", 2))
                assert.equals(2, store:message_position("c-nova", 3))
            end)

            it("devolve o bookmark pelo id da mensagem", function()
                assert.is_nil(store:get_bookmark("m2"))
                store:bookmark_offline({ id = "m2", chatId = "c-nova", messageId = "m2" })
                local bm = store:get_bookmark("m2")
                assert.equals("m2", bm.id)
                assert.equals("c-nova", bm.chatId)
                assert.equals("m2", bm.messageId)
            end)

            it("busca devolve uma linha por mensagem, com o seq", function()
                local achados = store:search("segunda", 10)
                assert.equals(1, #achados)
                assert.equals("c-nova", achados[1].chat_id)
                assert.equals("m2", achados[1].message_id)
                assert.equals(2, achados[1].seq) -- o leitor usa para pular ate a mensagem
            end)

            it("busca pelo titulo devolve as mensagens da conversa", function()
                local achados = store:search("Nova", 10)
                assert.equals(3, #achados)
                assert.equals(1, achados[1].seq)
            end)

            it("busca respeita o limite", function()
                assert.equals(2, #store:search("a", 2))
            end)

            -- Regressao: o store em memoria devolve o vocabulario do dominio
            -- (messageCount, updatedAt, favorite booleano) e o SQLite devolvia a linha
            -- crua (message_count, updated_at, favorite = 0). Em Lua 0 e' verdade, entao
            -- a biblioteca marcava TODA conversa como favorita e perdia data e contagem.
            -- O contrato do store e' o mesmo nos dois; quem consome e' library.lua.
            it("devolve o vocabulario do dominio, e nao a linha do SQL", function()
                local chats = store:list_chats()
                local por_id = {}
                for _, chat in ipairs(chats) do por_id[chat.id] = chat end

                assert.equals(3, por_id["c-nova"].messageCount)
                assert.equals("2026-03-01T00:00:00Z", por_id["c-nova"].updatedAt)
                assert.is_false(por_id["c-nova"].favorite)
                assert.is_true(por_id["c-meio"].favorite)
                assert.is_false(por_id["c-antiga"].favorite)
            end)

            it("get_chat devolve o mesmo vocabulario de list_chats", function()
                local pelo_id = store:get_chat("c-nova")
                local pela_lista = store:list_chats()[1]
                assert.same(pela_lista, pelo_id)
            end)
        end)
    end

    describe("os dois stores entregam a mesma tela", function()
        -- Se store e library divergirem, a biblioteca muda conforme o store usado.
        -- Este e' o teste que pega isso: nos specs a UI so ve o store de memoria, mas
        -- no dispositivo o store e' o SQLite.
        it("chat_rows sai identico com memoria e com SQLite", function()
            local library = require("client.library")
            local telas = {}
            for _, alvo in ipairs(stores()) do
                local store = alvo.open()
                povoar(store)
                telas[alvo.nome] = library.chat_rows(store, { limit = 10 })
                store:close()
            end
            assert.same(telas.memoria, telas.sqlite)
        end)
    end)
end)
