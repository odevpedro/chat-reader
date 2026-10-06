-- Leitor: uma mensagem por tela.
--
-- E decisao de tela, nao de plataforma. Uma conversa pode ter centenas de mensagens, e
-- o TextViewer com o texto inteiro montaria um TextBoxWidget gigante — caro de montar e
-- pior de rolar. Uma mensagem por vez, com virada de pagina, e o que o leitor do
-- proprio KOReader faz com paginas.
--
-- O texto vai como **texto plano ja convertido**: o TextViewer do KOReader nao tem
-- `text_format = "md"` no v2026.03 (verificado no fonte), entao markdown mostrado cru
-- ficaria com `*`, `#` e cercas na tela. Quem converte e `client.format`
-- (markdown -> texto plano, com cabecalho de quem fala), e nao ha renderer aqui:
-- uma reimplementacao em HTML nao apareceria no TextViewer de qualquer jeito.

-- Os modulos do KOReader sao carregados dentro de build()/show(), nao no topo, de
-- proposito: a navegacao (goto, janela, onde parou) e logica pura que os specs do
-- container exercitam sem dispositivo. So quando a tela vai aparecer e que o widget
-- entra na historia.

local Reader = {}
Reader.__index = Reader

-- Quantas mensagens ficam carregadas por vez. Cobre uma conversa inteira curta e, numa
-- longa, novas paginas entram na memoria conforme o leitor avanca.
local CHUNK = 40

--- @param seq comeca nesta mensagem; nil continua de onde parou
local function M(store, chat_id, positions, seq)
    local self = setmetatable({}, Reader)
    self.store = store
    self.chat_id = chat_id
    self.positions = positions
    self.chat = store:get_chat(chat_id) or { id = chat_id, title = "(sem titulo)" }
    self.total = store:count_messages(chat_id)
    self.messages = {}
    self.loaded = 0
    self.viewer = nil
    self.on_close = nil
    self:load_more()
    -- Retoma onde parou; sem isso, reabrir a conversa comeca do comeco. Um seq
    -- explicito (resultado de busca) ganha da posicao lembrada.
    local last = positions and positions:get(chat_id)
    local start = seq or (last and last.seq)
    self.index = start and (1 + store:message_position(chat_id, start)) or 1
    if self.index > self.total and self.total > 0 then self.index = self.total end
    -- O index pode estar alem da primeira janela (resultado de busca numa conversa
    -- longa): carrega ate a mensagem inicial caber.
    while not self.messages[self.index] and self:load_more() > 0 do end
    return self
end

-- Append: a janela so cresce. `offset` e a contagem do que ja esta carregado, que e
-- exatamente o offset da paginacao por seq do store.
function Reader:load_more()
    local more = self.store:list_messages(self.chat_id, CHUNK, self.loaded)
    for _, message in ipairs(more) do
        self.messages[#self.messages + 1] = message
    end
    self.loaded = self.loaded + #more
    return #more
end

function Reader:current()
    return self.messages[self.index]
end

function Reader:goto(index)
    if index < 1 or index > self.total then return false end
    if index > self.loaded and self:load_more() == 0 then return false end
    self.index = index
    self:remember()
    return true
end

function Reader:next()
    return self:goto(self.index + 1)
end

function Reader:prev()
    return self:goto(self.index - 1)
end

function Reader:remember()
    local message = self:current()
    if message and self.positions then
        self.positions:set(self.chat_id, message.id, message.seq)
    end
end

function Reader:is_favorite()
    local chat = self.store:get_chat(self.chat_id)
    return (chat and chat.favorite) and true or false
end

function Reader:is_bookmarked()
    local message = self:current()
    return message ~= nil and self.store:get_bookmark(message.id) ~= nil
end

function Reader:title()
    -- A barra de titulo mostra de quem e a mensagem atual: e a distincao visual
    -- mais clara no e-ink (o corpo tem o cabecalho em texto, e o titulo tambem).
    local format = require("client.format")
    local who = format.role_label(self:current())
    local name = self.chat.title or ""
    if who ~= "" and name ~= "" then
        return string.format("%d/%d · %s · %s", self.index, self.total, who, name)
    end
    return string.format("%d/%d · %s%s", self.index, self.total, who, name)
end

-- ---------------------------------------------------------------------------
-- Acoes do usuario
-- ---------------------------------------------------------------------------

-- Favoritar e bookmarkar sao offline-first: o store aplica e enfileira na mesma
-- transacao, e a tela diz o que vai acontecer em vez de fingir que sincronizou.
local function announce(text)
    local UIManager = require("ui/uimanager")
    local InfoMessage = require("ui/widget/infomessage")
    UIManager:show(InfoMessage:new{ text = text .. "\nEnviado no próximo sync." })
end

function Reader:toggle_favorite()
    local favorite = not self:is_favorite()
    self.store:set_favorite_offline(self.chat_id, favorite)
    -- O botao mostra o estado, entao a tela precisa ser refeita depois da acao.
    self:refresh()
    announce(favorite and "Conversa favoritada." or "Favorito removido.")
end

function Reader:toggle_bookmark()
    local message = self:current()
    if not message then return end
    if self.store:get_bookmark(message.id) then
        self.store:unbookmark_offline(message.id)
        self:refresh()
        announce("Bookmark removido.")
    else
        self.store:bookmark_offline({ id = message.id, chatId = self.chat_id,
                                      messageId = message.id })
        self:refresh()
        announce("Bookmark criado.")
    end
end

-- O close_callback chega depois que o TextViewer ja se fechou: fechar de novo seria
-- pedir ao UIManager para tirar da pilha um widget que ja saiu.
function Reader:close()
    self.viewer = nil
    if self.positions then self.positions:save() end
    if self.on_close then self:on_close() end
end

-- Para quem fecha o leitor por fora (sair da biblioteca), e nao pelo proprio botao.
function Reader:dismiss()
    if self.viewer then
        local UIManager = require("ui/uimanager")
        UIManager:close(self.viewer)
        self.viewer = nil
    end
    self:close()
end

-- Troca a mensagem na tela recriando o TextViewer: um refresh de tela inteira, sem
-- herdar o scroll do texto anterior. Em e-ink e o caminho mais previsivel.
function Reader:refresh()
    local UIManager = require("ui/uimanager")
    if self.viewer then UIManager:close(self.viewer) end
    self.viewer = self:build()
    UIManager:show(self.viewer)
end

function Reader:build()
    local UIManager = require("ui/uimanager")
    local TextViewer = require("ui/widget/textviewer")
    local format = require("client.format")
    local message = self:current()
    local next_message = self.messages[self.index + 1]
    return TextViewer:new{
        title = self:title(),
        text = message and format.display_text(message, next_message) or "",
        buttons_table = {
            {
                {
                    text = "‹ Anterior",
                    id = "prev",
                    callback = function()
                        if self:prev() then self:refresh() end
                    end,
                },
                {
                    text = "Próxima ›",
                    id = "next",
                    callback = function()
                        if self:next() then self:refresh() end
                    end,
                },
            },
            {
                {
                    text = self:is_favorite() and "Favorito ✓" or "Favoritar",
                    id = "favorite",
                    callback = function() self:toggle_favorite() end,
                },
                {
                    text = self:is_bookmarked() and "Bookmark ✓" or "Bookmark",
                    id = "bookmark",
                    callback = function() self:toggle_bookmark() end,
                },
            },
        },
        -- Sem isto, passar buttons_table some com os botoes Find e Close.
        add_default_buttons = true,
        -- Fim do texto vira proxima mensagem. No fim da conversa, next() devolve
        -- false e a pagina continua sendo rolada normalmente.
        page_turn_callback_next = function()
            if self:next() then self:refresh() end
        end,
        page_turn_callback_prev = function()
            if self:prev() then self:refresh() end
        end,
        close_callback = function() self:close() end,
    }
end

function Reader:show()
    local UIManager = require("ui/uimanager")
    local InfoMessage = require("ui/widget/infomessage")
    self.viewer = self:build()
    UIManager:show(self.viewer)
    if self.total == 0 then
        UIManager:show(InfoMessage:new{
            text = "Esta conversa ainda não tem mensagens baixadas.\n"
                 .. "Sincronize para trazer o conteúdo.",
        })
    end
    return self
end

return { new = M, CHUNK = CHUNK }
