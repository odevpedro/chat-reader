-- Ponto de entrada do plugin.
--
-- Segue o caminho que o proprio KOReader usa (verificado em
-- koreader/koreader@frontend/apps/filemanager/filemanagermenu.lua e
-- plugins/opds.koplugin/main.lua):
--
--   * o diretorio do plugin precisa terminar em .koplugin para o pluginloader achar;
--   * main.lua devolve a classe, com `name` e `addToMainMenu`;
--   * a instancia recebe `ui` (o FileManager) e se registra no menu;
--   * o pluginloader coloca o diretorio do plugin no package.path, entao
--     require("client.sync"), require("ui.reader") resolvem para ca.
--
-- Este arquivo e a cola entre o KOReader e o resto: quem manda no conteudo e o
-- store, quem manda no protocolo e o client/sync.lua, e a UI so desenha.

local ButtonDialog = require("ui/widget/buttondialog")
local DataStorage = require("datastorage")
local InfoMessage = require("ui/widget/infomessage")
local NetworkMgr = require("ui/network/manager")
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")

local client_http = require("client.http")
local client_sync = require("client.sync")
local positions = require("client.positions")
local settings_mod = require("client.settings")
local library_ui = require("ui.library")
local reader_ui = require("ui.reader")
local settings_ui = require("ui.settings")

local ChatReader = WidgetContainer:extend{
    name = "chatreader",
    store = nil,
    settings = nil,
    http = nil,
    positions = nil,
    last_search = nil,
    syncing = false,
}

function ChatReader:init()
    -- O FileManager e quem carrega o plugin e quem entrega `ui`.
    self.ui.menu:registerToMainMenu(self)
    local LuaSettings = require("luasettings")
    self.settings = settings_mod.open()
    self.positions = positions.new(LuaSettings:open(
        DataStorage:getSettingsDir() .. "/chatreader_reader.lua"))
    self.reader = nil
end

function ChatReader:addToMainMenu(menu_items)
    menu_items.chatreader = {
        text = "Chat Reader",
        callback = function()
            self:onShowChatReader()
        end,
    }
end

-- ---------------------------------------------------------------------------
-- Store e HTTP
-- ---------------------------------------------------------------------------

-- Aberto sob demanda: carregar o plugin nao deve criar arquivo nenhum. O caminho
-- fica no datadir, que e o que o backup do KOReader cobre.
function ChatReader:db_path()
    return DataStorage:getDataDir() .. "/chatreader.sqlite3"
end

function ChatReader:get_store()
    if not self.store then
        self.store = require("store.sqlite").open(self:db_path())
    end
    return self.store
end

function ChatReader:get_http()
    local settings = self.settings
    self.http = client_http.new{
        base_url = settings:base_url(),
        token = settings:token(),
    }
    return self.http
end

-- ---------------------------------------------------------------------------
-- Login
-- ---------------------------------------------------------------------------

-- @return ok, erro
function ChatReader:login(password)
    local ok, result = pcall(function()
        return self.settings:login(self:get_http(), password)
    end)
    if not ok then
        return false, tostring(result)
    end
    return true, result
end

-- ---------------------------------------------------------------------------
-- Sincronizacao
-- ---------------------------------------------------------------------------

function ChatReader:onShowChatReader()
    local store = self:get_store()
    if store:count("chats") == 0 then
        -- Biblioteca vazia: sincronizar e o que ha a fazer. A caixa sai quando o
        -- usuario escolhe um caminho, para o proximo widget aparecer por cima.
        local dialog
        dialog = ButtonDialog:new{
            title = "Chat Reader",
            buttons = {
                { {
                    text = "Sincronizar agora",
                    callback = function()
                        UIManager:close(dialog)
                        self.reopen_after_sync = true
                        self:sync_now()
                    end,
                } },
                { {
                    text = "Configurações",
                    callback = function()
                        UIManager:close(dialog)
                        settings_ui.show(self)
                    end,
                } },
            },
        }
        UIManager:show(dialog)
        return
    end
    library_ui.show_chats(self)
end

-- runWhenOnline: se nao ha rede, ele liga o wifi e roda depois. O sync manual e
-- assincrono por definicao — nao faz sentido travar a tela esperando.
function ChatReader:sync_now()
    if self.syncing then return end
    if not self.settings:base_url() or self.settings:base_url() == "" then
        settings_ui.edit_url(self)
        return
    end
    if self.settings:token_expired() or not self.settings:token() then
        -- Sem token valido nao ha sync: a senha e pedida uma vez e vira token.
        settings_ui.ask_password(self, function() self:sync_now() end)
        return
    end
    NetworkMgr:runWhenOnline(function() self:do_sync() end)
end

function ChatReader:do_sync()
    if self.syncing then return end
    self.syncing = true
    self:get_http().token = self.settings:token()

    local store = self:get_store()
    local ok, result = pcall(client_sync.run, self.http, store,
        store:get_token(), self.settings:client_id())
    self.syncing = false

    if not ok then
        if client_http.is_unauthorized(result) then
            self.settings:forget_login()
            UIManager:show(InfoMessage:new{
                text = "Token expirado.\nEntre de novo para sincronizar.",
            })
            settings_ui.ask_password(self, function() self:sync_now() end)
            return
        end
        UIManager:show(InfoMessage:new{ text = "Falha no sync:\n" .. tostring(result) })
        return
    end

    UIManager:show(InfoMessage:new{ text = self:sync_report(result) })
    if self.reopen_after_sync then
        -- a sincronizacao veio da biblioteca vazia: abre a biblioteca (ou a caixa
        -- de entrada de novo, se o servidor continuar sem conversas) sobre o aviso,
        -- que o usuario dispensa com um toque
        self.reopen_after_sync = nil
        self:onShowChatReader()
    end
end

function ChatReader:sync_report(result)
    local lines = {}
    if result.applied_snapshot then
        lines[#lines + 1] = "Biblioteca recriada do servidor."
    elseif result.changes > 0 then
        lines[#lines + 1] = string.format("%d mudança(s) aplicada(s).", result.changes)
    else
        lines[#lines + 1] = "Tudo em dia."
    end
    if result.ack and result.ack.sent > 0 then
        lines[#lines + 1] = string.format("%d escrita(s) enviada(s).", result.ack.sent)
    end
    if result.ack_error then
        lines[#lines + 1] = "Escritas ficaram na fila: " .. result.ack_error
    end
    if result.rejections and #result.rejections > 0 then
        local reasons = {}
        for _, rejection in ipairs(result.rejections) do
            reasons[#reasons + 1] = rejection.reason
        end
        lines[#lines + 1] = "Recusadas: " .. table.concat(reasons, ", ")
    end
    return table.concat(lines, "\n")
end

-- ---------------------------------------------------------------------------
-- Leitor
-- ---------------------------------------------------------------------------

-- @param seq mensagem para comecar; nil continua de onde parou
-- Abre o leitor com volta: fechar devolve para a lista de onde veio (biblioteca ou
-- resultado de busca), em vez de cair no FileManager. Fechar o leitor e navegar, nao
-- sair do plugin.
function ChatReader:open_chat(chat_id, seq, from_view)
    self.from_view = from_view
    if self.reader then
        self.reader:dismiss()
    end
    self.reader = reader_ui.new(self:get_store(), chat_id, self.positions, seq)
    self.reader.on_close = function()
        self.reader = nil
        local view = self.from_view
        self.from_view = nil
        if view == "library" then
            library_ui.show_chats(self, { only_favorites = self.only_favorites })
        elseif view == "search" and self.last_search then
            library_ui.show_results(self, self.last_search)
        end
    end
    self.reader:show()
end

-- Apagar a biblioteca mantem o schema e o token (o proximo sync e incremental), e
-- descarta o que o usuario so tem aqui: favoritos e bookmarks ainda nao enviados.
function ChatReader:wipe()
    if self.reader then self.reader:dismiss() end
    self:get_store():clear()
    self.positions:clear()
    self.store:close()
    self.store = nil
end

return ChatReader
