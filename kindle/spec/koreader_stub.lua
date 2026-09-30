-- Stubs dos modulos do KOReader, para que as telas sejam *executadas* nos specs.
--
-- O problema que isto resolve: ate agora `main.lua` e `views/*.lua` passavam apenas
-- pelo `syntax_spec`, que usa `loadfile` — compila sem executar. Isso pega erro de
-- sintaxe e nada mais. Um `require("views.lerra")`, um campo errado num
-- `ButtonDialog:new`, um `require` de modulo inexistente no aparelho: nada disso
-- apareceria ate voce abrir o menu no Kindle e ver tela branca.
--
-- Aqui cada `require("ui/...")` devolve um objeto minimo com os mesmos campos que o
-- KOReader aceita. O que importa nao e a tela sair bonita — e o fato de os callbacks
-- rodarem de verdade aqui, no container, onde um erro aparece em milissegundos e com
-- stack trace.
--
-- Os stubs sao deliberadamente burros: guardam o que receberam e devolvem. Quem
-- verifica comportamento de tela e o `ui_spec`; este arquivo so precisa fazer o
-- `require` funcionar e a hierarquia de widgets existir.

local M = {}

-- Registro de tudo que foi mostrado, para o spec inspecionar o que abriu.
M.shown = {}
M.closed = {}

function M.reset()
    M.shown = {}
    M.closed = {}
end

-- Ultimo widget com um certo campo, util para assertar sobre uma tela especifica.
function M.last_shown(pred)
    for i = #M.shown, 1, -1 do
        if not pred or pred(M.shown[i]) then return M.shown[i] end
    end
    return nil
end

-- ---------------------------------------------------------------------------
-- Base de widgets
-- ---------------------------------------------------------------------------

-- `extend` fiel o suficiente para o uso real do plugin: cria uma classe, copia o
-- defaults, e `new` injeta os argumentos em cima. `self:extend` em subclasse (que o
-- Reader usa) precisa continuar funcionando.
local function make_class(base)
    local class = {}
    class.__index = class

    function class:new(fields)
        local obj = setmetatable({}, self)
        if fields then
            for k, v in pairs(fields) do obj[k] = v end
        end
        obj._class = self
        if obj.init then obj:init() end
        return obj
    end

    function class:extend(defaults)
        local parent = self
        local child = make_class(parent)
        for k, v in pairs(defaults or {}) do child[k] = v end
        child.__index = child
        -- `parent._class` e o nome estavel do widget, util em mensagens de erro.
        child._super = parent
        return child
    end

    if base then
        for k, v in pairs(base) do if class[k] == nil then class[k] = v end end
    end
    return class
end

M.make_class = make_class

-- Atalho: registra um modulo no package.preload devolvendo a classe direto, como o
-- KOReader faz — `require("ui/widget/buttondialog")` retorna ButtonDialog, nao um
-- modulo que o embrulha. Devolver `{ widget = ... }` faria `WidgetContainer:extend`
-- falhar com "attempt to call method 'extend'".
local function widget_module(name, defaults)
    local class = make_class(defaults)
    package.preload[name] = function() return class end
    return class
end

-- ---------------------------------------------------------------------------
-- Modulos do KOReader usados pelo plugin
-- ---------------------------------------------------------------------------

local WidgetContainer = widget_module("ui/widget/container/widgetcontainer")

local UIManager = {}
function UIManager:show(w) table.insert(M.shown, w) end
function UIManager:close(w)
    for i, v in ipairs(M.shown) do
        if v == w then table.remove(M.shown, i) break end
    end
    table.insert(M.closed, w)
end
function UIManager:schedule() end
function UIManager:unschedule() end
package.preload["ui/uimanager"] = function() return UIManager end
M.UIManager = UIManager

local InfoMessage = widget_module("ui/widget/infomessage", { text = "" })
M.InfoMessage = InfoMessage

local ButtonDialog = widget_module("ui/widget/buttondialog", {
    title = nil, buttons = nil, dismissable = false, width = nil,
})
M.ButtonDialog = ButtonDialog

local ConfirmBox = widget_module("ui/widget/confirmbox", {
    text = "", ok_text = "OK", cancel_text = "Cancel", ok_callback = function() end,
})
M.ConfirmBox = ConfirmBox

local TextViewer = widget_module("ui/widget/textviewer", {
    title = nil, text = "", text_format = nil, buttons_table = nil,
    add_default_buttons = nil, page_turn_callback_prev = nil,
    page_turn_callback_next = nil, close_callback = nil,
})
M.TextViewer = TextViewer

local Menu = widget_module("ui/widget/menu", { title = "", item_table = {} })
M.Menu = Menu

local InputDialog = widget_module("ui/widget/inputdialog", {
    title = nil, input = nil, input_hint = nil, ok_text = nil,
    callback = nil, ok_callback = nil,
})
M.InputDialog = InputDialog

-- DataStorage: no aparelho, /mnt/sd/koreader/settings e /mnt/sd/koreader/data.
-- Aqui, /tmp — o spec precisa de um lugar que exista e nao fique no repo.
local DataStorage = {}
function DataStorage:getSettingsDir() return M.settings_dir or "/tmp/kr-spec-settings" end
function DataStorage:getDataDir() return M.data_dir or "/tmp/kr-spec-data" end
package.preload["datastorage"] = function() return DataStorage end
M.DataStorage = DataStorage

-- LuaSettings: guarda os valores em memoria, que e tudo que o spec precisa.
local function new_settings(path)
    local self = { _path = path, _values = {} }
    function self:readSetting(key, default)
        local v = self._values[key]
        if v == nil then return default end
        return v
    end
    function self:saveSetting(key, value) self._values[key] = value end
    function self:flush() end
    function self:close() end
    return self
end
package.preload["luasettings"] = function()
    return { open = function(_, path) return new_settings(path) end }
end

-- NetworkMgr: o aparelho decide se ha rede; aqui o spec define.
local NetworkMgr = {}
NetworkMgr.online = true
function NetworkMgr:isOnline() return NetworkMgr.online end
function NetworkMgr:runWhenOnline(_, cb) if NetworkMgr.online then cb() end end
package.preload["ui/network/manager"] = function() return NetworkMgr end
M.NetworkMgr = NetworkMgr

M.WidgetContainer = WidgetContainer

-- ---------------------------------------------------------------------------
-- Carga
-- ---------------------------------------------------------------------------

-- Instala os stubs e limpa o cache de modulos do plugin, para que um `require` de
-- teste veja o `package.path` limpo e carregue main.lua do zero.
function M.install(opts)
    opts = opts or {}
    if opts.data_dir then M.data_dir = opts.data_dir end
    if opts.settings_dir then M.settings_dir = opts.settings_dir end
    for name in pairs(package.loaded) do
        if name:match("^views%.") or name:match("^client%.")
            or name == "main" or name:match("^ui/") or name == "luasettings"
            or name == "datastorage" or name:match("^store%.") then
            package.loaded[name] = nil
        end
    end
    M.reset()
    return M
end

return M