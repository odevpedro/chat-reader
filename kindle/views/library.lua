-- Biblioteca: a lista de conversas, e a busca dentro dela.
--
-- A UI e a casca: quem decide o que aparece, em que ordem e com que texto e
-- client/library.lua, que nao depende de KOReader e e testado no container. Aqui so
-- existe ButtonDialog e InputDialog, que e o que o KOReader ja faz bem (pagina,
-- toca, volta).

local ButtonDialog = require("ui/widget/buttondialog")
local InfoMessage = require("ui/widget/infomessage")
local InputDialog = require("ui/widget/inputdialog")
local UIManager = require("ui/uimanager")

local library = require("client.library")

local settings_ui = require("views.settings")

local M = {}

local APP = "Chat Reader"

-- ---------------------------------------------------------------------------
-- Biblioteca
-- ---------------------------------------------------------------------------

-- Um botao por linha, do mais recente para o mais antigo. O ButtonDialog ocupa a
-- largura toda com um botao so e pagina sozinho, entao nao ha paginacao para fazer
-- aqui: e o que a plataforma ja resolve.
function M.show_chats(ctx, opts)
    opts = opts or {}
    local rows = library.chat_rows(ctx:get_store(), opts)

    if #rows == 0 then
        UIManager:show(InfoMessage:new{
            text = opts.only_favorites and "Nenhuma conversa favoritada."
                 or "Nenhuma conversa ainda. Sincronize para trazer a biblioteca.",
        })
        return
    end

    local buttons = { {
            {
                text = "Sincronizar",
                callback = function()
                    UIManager:close(dialog)
                    -- sincronizou: a lista recria do banco ao voltar
                    ctx.reopen_after_sync = true
                    ctx:sync_now()
                end,
            },
            {
                text = "Buscar",
                callback = function()
                    UIManager:close(dialog)
                    M.show_search(ctx)
                end,
            },
            {
                text = "Configurações",
                callback = function()
                    UIManager:close(dialog)
                    settings_ui.show(ctx)
                end,
            },
        } }
    local dialog
    local function open_chat(id)
        -- O botao do ButtonDialog nao fecha a caixa sozinho (as chamadas do KOReader
        -- fecham explicitamente) e o leitor so pode aparecer por cima se a lista sair.
        UIManager:close(dialog)
        -- a volta do leitor recria esta lista, com o mesmo filtro
        ctx.only_favorites = opts.only_favorites and true or nil
        ctx:open_chat(id, nil, "library")
    end
    for _, row in ipairs(rows) do
        buttons[#buttons + 1] = {
            {
                text = (row.favorite and "★ " or "") .. row.title
                     .. "\n" .. row.subtitle,
                id = row.id,
                callback = function() open_chat(row.id) end,
            },
        }
    end

    dialog = ButtonDialog:new{
        title = opts.only_favorites and (APP .. " · Favoritos") or APP,
        buttons = buttons,
        dismissable = true,
    }
    UIManager:show(dialog)
end

-- ---------------------------------------------------------------------------
-- Busca
-- ---------------------------------------------------------------------------

-- A busca e local (LIKE) e nao no backend: e o conteudo que esta no aparelho, e o
-- resultado e imediato mesmo sem rede. O backend continua sendo quem faz full-text
-- para quem quiser isso (docs/sync-protocol.md, secao 13).
function M.show_search(ctx)
    local dialog
    local function search()
        UIManager:close(dialog)
        local term = (dialog:getInputText() or ""):gsub("^%s+", "")
        term = term:gsub("%s+$", "")
        if term == "" then return end
        ctx.last_search = term
        M.show_results(ctx, term)
    end

    dialog = InputDialog:new{
        title = "Buscar nas conversas",
        input = ctx.last_search or "",
        buttons = {
            {
                {
                    text = "Cancelar",
                    callback = function() UIManager:close(dialog) end,
                },
                {
                    text = "Buscar",
                    is_enter_default = true,
                    callback = search,
                },
            },
        },
    }
    UIManager:show(dialog)
end

function M.show_results(ctx, term)
    local hits = ctx:get_store():search(term, 50)
    if #hits == 0 then
        UIManager:show(InfoMessage:new{ text = "Nada encontrado para “" .. term .. "”." })
        return
    end

    local rows = library.search_rows(hits, term)
    local buttons = {}
    local dialog
    for _, row in ipairs(rows) do
        buttons[#buttons + 1] = {
            {
                text = row.title .. "\n" .. row.snippet,
                id = row.chatId .. "/" .. tostring(row.seq),
                callback = function()
                    UIManager:close(dialog)
                    ctx:open_chat(row.chatId, row.seq, "search")
                end,
            },
        }
    end

    dialog = ButtonDialog:new{
        title = string.format("%s · %d resultados", APP, #rows),
        buttons = buttons,
        dismissable = true,
    }
    UIManager:show(dialog)
end

return M
