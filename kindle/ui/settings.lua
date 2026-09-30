-- Menu de configuracao: onde a URL, o login e a fila de pendencias aparecem.
--
-- Nao ha tela de login elaborate: a senha e digitada uma vez, vira token, e o token e
-- o que fica salvo (client/settings.lua). Sem senha no arquivo.

local ButtonDialog = require("ui/widget/buttondialog")
local ConfirmBox = require("ui/widget/confirmbox")
local InfoMessage = require("ui/widget/infomessage")
local InputDialog = require("ui/widget/inputdialog")
local Menu = require("ui/widget/menu")
local UIManager = require("ui/uimanager")

local M = {}

local APP = "Chat Reader"

local function info(text)
    UIManager:show(InfoMessage:new{ text = text })
end

-- ---------------------------------------------------------------------------
-- Campos
-- ---------------------------------------------------------------------------

function M.ask(title, current, hint, on_save)
    local dialog
    dialog = InputDialog:new{
        title = title,
        input = current or "",
        input_hint = hint,
        buttons = {
            {
                {
                    text = "Cancelar",
                    callback = function() UIManager:close(dialog) end,
                },
                {
                    text = "Salvar",
                    is_enter_default = true,
                    callback = function()
                        UIManager:close(dialog)
                        on_save((dialog.input and dialog.input:getText()) or "")
                    end,
                },
            },
        },
    }
    UIManager:show(dialog)
end

function M.edit_url(ctx)
    M.ask("Endereço do servidor", ctx.settings:base_url(),
        "http://192.168.0.10:8080", function(value)
            ctx.settings:set_base_url(value)
            info("Endereço salvo:\n" .. ctx.settings:base_url())
        end)
end

function M.edit_username(ctx)
    M.ask("Usuário", ctx.settings:username(), "mesmo usuário do servidor",
        function(value)
            ctx.settings:set_username(value)
            info("Usuário salvo:\n" .. ctx.settings:username())
        end)
end

-- A senha nao volta para o campo: nao esta salva, e o que fica e o token.
-- Quando o login vem de um sync interrompido (sem token ou 401), a mesma coisa que
-- mostrou a senha pode reaproveitar o caminho depois de deixar o token valido.
function M.ask_password(ctx, on_success)
    M.ask("Senha", "", "não é guardada", function(password)
        if password == "" then return end
        local ok, err = ctx:login(password)
        if not ok then
            info("Falha no login:\n" .. tostring(err))
            return
        end
        info("Login feito.")
        if on_success then on_success() end
    end)
end

-- ---------------------------------------------------------------------------
-- Acoes
-- ---------------------------------------------------------------------------

function M.show_queue(ctx)
    local pending = ctx:get_store():pending_local(10)
    if #pending == 0 then
        info("Nada pendente para enviar.")
        return
    end
    local lines = {}
    for _, row in ipairs(pending) do
        lines[#lines + 1] = string.format("%d. %s %s", row.seq, row.type,
            row.chatId or row.refId or "")
    end
    local total = ctx:get_store():count_local()
    if total > #lines then
        lines[#lines + 1] = string.format("... e mais %d", total - #lines)
    end
    info(string.format("%d escrita(s) na fila:\n%s", total, table.concat(lines, "\n")))
end

-- Apagar o banco local e perigoso e nao tem como desfazer: por isso a confirmacao
-- pede o motivo de novo.
function M.confirm_wipe(ctx)
    UIManager:show(ConfirmBox:new{
        text = "Apagar a biblioteca local?\n"
            .. "As conversas voltam no próximo sync, mas o que está só aqui — "
            .. "favoritos e bookmarks ainda não enviados — vai junto.",
        ok_text = "Apagar tudo",
        cancel_text = "Cancelar",
        ok_callback = function()
            ctx:wipe()
            UIManager:show(InfoMessage:new{ text = "Biblioteca local apagada." })
        end,
    })
end

-- ---------------------------------------------------------------------------
-- Menu
-- ---------------------------------------------------------------------------

function M.show(ctx)
    local items = {
        {
            text = "Sincronizar agora",
            callback = function() ctx:sync_now() end,
        },
        {
            text = "Fila de escritas (" .. ctx:get_store():count_local() .. ")",
            callback = function() M.show_queue(ctx) end,
        },
        {
            text = "Endereço: " .. ctx.settings:base_url(),
            callback = function() M.edit_url(ctx) end,
        },
        {
            text = "Usuário: " .. (ctx.settings:username() ~= "" and ctx.settings:username()
                                   or "(não definido)"),
            callback = function() M.edit_username(ctx) end,
        },
        {
            text = "Entrar / trocar senha",
            callback = function() M.ask_password(ctx) end,
        },
        {
            text = "Apagar biblioteca local",
            callback = function() M.confirm_wipe(ctx) end,
        },
    }
    UIManager:show(Menu:new{ title = APP, item_table = items })
end

return M
