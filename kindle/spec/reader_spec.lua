-- Navegacao do leitor, sem o widget do KOReader: eh logica pura (janela de mensagens,
-- onde parou, limite da conversa) que so funcionaria no aparelho se nao tivesse spec.

local reader_ui = require("ui.reader")
local sync = require("client.sync")

-- Conversa de 95 mensagens: mais que a janela de 40, para exercitar a paginacao.
local function povoar(store)
    store:put_chat({ id = "c1", title = "Conversa", updatedAt = "2026-03-01T00:00:00Z" })
    local messages = {}
    for i = 1, 95 do
        messages[#messages + 1] = { id = "m" .. i, role = "user", seq = i,
                                    content = "mensagem " .. i }
    end
    store:replace_messages("c1", messages)
end

local function fake_positions(initial)
    local p = { data = initial or {}, saved = 0 }
    function p:get(chat_id) return self.data[chat_id] end
    function p:set(chat_id, message_id, seq) self.data[chat_id] = { messageId = message_id, seq = seq } end
    function p:save() self.saved = self.saved + 1 end
    return p
end

describe("ui/reader", function()
    local store

    before_each(function()
        store = sync.new_store()
        povoar(store)
    end)

    it("comeca na primeira mensagem quando nao ha posicao", function()
        local r = reader_ui.new(store, "c1", nil)
        assert.equals(95, r.total)
        assert.equals(1, r.index)
        assert.equals("m1", r:current().id)
    end)

    it("retoma onde parou pela ultima posicao gravada", function()
        local positions = fake_positions({ c1 = { messageId = "m50", seq = 50 } })
        local r = reader_ui.new(store, "c1", positions)
        assert.equals(50, r.index)
        assert.equals("m50", r:current().id)
    end)

    it("pula direto para o resultado de busca, carregando a janela certa", function()
        local r = reader_ui.new(store, "c1", nil, 90)
        assert.equals(90, r.index)
        assert.equals("m90", r:current().id)
        assert.is_true(r.loaded >= 90)
    end)

    it("anda para frente e para tras dentro da conversa", function()
        local r = reader_ui.new(store, "c1", nil, 2)
        assert.is_true(r:next())
        assert.equals(3, r.index)
        assert.is_true(r:prev())
        assert.equals(2, r.index)
    end)

    it("para na primeira e na ultima mensagem", function()
        local r = reader_ui.new(store, "c1", nil, 1)
        assert.is_false(r:prev())
        assert.equals(1, r.index)
        assert.is_true(r:goto(95))
        assert.is_false(r:next())
        assert.equals(95, r.index)
    end)

    it("carrega a proxima janela so quando precisa", function()
        local r = reader_ui.new(store, "c1", nil, 40)
        assert.equals(40, r.loaded)
        assert.is_true(r:next()) -- 41 > 40 -> carrega mais
        assert.is_true(r.loaded >= 80)
    end)

    it("grava a posicao em memoria a cada passo, sem tocar no disco", function()
        local positions = fake_positions()
        local r = reader_ui.new(store, "c1", positions, 5)
        r:next()
        assert.equals(6, positions.data.c1.seq)
        assert.equals(0, positions.saved)

        r:dismiss()
        assert.equals(1, positions.saved) -- so o fechamento grava
    end)

    it("aguenta conversa sem mensagens", function()
        local vazia = sync.new_store()
        vazia:put_chat({ id = "x" })
        local r = reader_ui.new(vazia, "x", nil)
        assert.equals(0, r.total)
        assert.is_nil(r:current())
        assert.is_false(r:next())
        assert.is_false(r:prev())
    end)
end)