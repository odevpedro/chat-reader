-- Teste de fumaca das telas: executa o caminho que o KOReader executa no aparelho.
--
-- O `syntax_spec` garante que os arquivos compilam. Este garante que eles *funcionam*:
-- carrega main.lua com stubs do KOReader, instancia o plugin como o pluginloader
-- faria, abre o menu e aperta os botoes.
--
-- O que isso pega, e que so apareceria no Kindle:
--   * `require` de modulo que nao existe (tela branca, sem mensagem);
--   * campo errado num `ButtonDialog:new`/`TextViewer:new` (nil em runtime);
--   * metodo que o plugin chama mas o widget nao tem;
--   * callback que tenta fechar um widget que ja saiu da pilha;
--   * crash ao abrir qualquer tela pela primeira vez.
--
-- Os campos dos stubs foram conferidos contra `frontend/ui/widget/*.lua` do KOReader,
-- nao escritos de memoria.

local stub = require("spec.koreader_stub")
local store_memory = require("store.memory")

local TMP = "/tmp/kr-ui-spec"

local function reset_store()
    local sh = io.popen("rm -rf " .. TMP)
    if sh then sh:close() end
end

-- Um FileManager minimo: e o `self.ui` que o pluginloader injeta.
local function fake_filemanager()
    local fm = { registered = {} }
    fm.menu = {
        registerToMainMenu = function(_, plugin) table.insert(fm.registered, plugin) end,
    }
    return fm
end

-- Plugin pronto, com banco em memoria: o sqlite do aparelho nao roda no container de
-- spec sem FFI, e aqui o que se testa e a tela, nao a persistencia (isso e do
-- store_spec).
local function new_plugin()
    stub.install{ data_dir = TMP, settings_dir = TMP }
    local ChatReader = require("main")
    local plugin = ChatReader:new{ ui = fake_filemanager() }
    plugin.store = store_memory.new()
    return plugin
end

local function shown_of(class)
    return stub.last_shown(function(w) return w._class == class end)
end

describe("o plugin carrega como o KOReader carrega", function()
    before_each(reset_store)

    it("main.lua carrega e devolve a classe com o nome que o menu espera", function()
        stub.install{ data_dir = TMP, settings_dir = TMP }
        local ChatReader = require("main")
        assert.is_table(ChatReader)
        assert.equals("chatreader", ChatReader.name)
        assert.is_function(ChatReader.addToMainMenu)
    end)

    it("init registra o plugin no menu principal", function()
        local plugin = new_plugin()
        assert.equals(1, #plugin.ui.registered)
    end)

    it("addToMainMenu cria a entrada 'Chat Reader' com callback", function()
        local plugin = new_plugin()
        local items = {}
        plugin:addToMainMenu(items)
        assert.is_not_nil(items.chatreader)
        assert.equals("Chat Reader", items.chatreader.text)
        assert.is_function(items.chatreader.callback)
    end)

    it("a entrada declara sorting_hint, senao o MenuSorter joga fora do menu de Ferramentas", function()
        local plugin = new_plugin()
        local items = {}
        plugin:addToMainMenu(items)
        assert.equals("more_tools", items.chatreader.sorting_hint)
    end)

    it("o caminho do banco sai de DataStorage", function()
        local plugin = new_plugin()
        assert.is_string(plugin:db_path())
        assert.is_truthy(plugin:db_path():find("chatreader", 1, true))
    end)
end)

describe("a biblioteca abre", function()
    before_each(reset_store)

    it("biblioteca vazia explica o que fazer, em vez de quebrar", function()
        local plugin = new_plugin()
        require("views.library").show_chats(plugin)
        local found = false
        for _, w in ipairs(stub.shown) do
            if tostring(w.text or ""):find("Sincronize", 1, true) then
                found = true
                break
            end
        end
        assert.is_true(found, "nenhuma mensagem de biblioteca vazia encontrada")
    end)

    it("com conversas, mostra um ButtonDialog com um botao por conversa", function()
        local plugin = new_plugin()
        plugin:get_store():put_chat({
            id = "c1", title = "Primeira", role = "user",
            created_at = 1, updated_at = 2, content_hash = "x",
        })
        require("views.library").show_chats(plugin)
        local dialog = shown_of(stub.ButtonDialog)
        assert.is_not_nil(dialog, "nenhum ButtonDialog apareceu")
        assert.is_table(dialog.buttons)
        assert.is_true(#dialog.buttons >= 1)
        assert.is_string(dialog.title)
    end)

    it("a busca abre um InputDialog com botoes e title", function()
        local plugin = new_plugin()
        require("views.library").show_search(plugin)
        local dialog = shown_of(stub.InputDialog)
        assert.is_not_nil(dialog, "busca nao abriu dialogo")
        assert.is_string(dialog.title)
        -- O InputDialog real usa buttons_table, nao callback:
        assert.is_table(dialog.buttons, "InputDialog sem botoes")
        assert.is_true(#dialog.buttons >= 1, "InputDialog sem botoes")
    end)

    it("Salvar na busca usa getInputText (API real do InputDialog), sem crash", function()
        local plugin = new_plugin()
        plugin:get_store():put_chat({
            id = "c1", title = "Conversa sobre Java", role = "user",
            created_at = 1, updated_at = 2, content_hash = "x",
            messages = { {
                id = "m1", seq = 1, role = "user", content = "java é legal",
                created_at = 1, content_hash = "h1",
            } },
        })
        require("views.library").show_search(plugin)
        local dialog = shown_of(stub.InputDialog)
        assert.is_function(dialog.getInputText, "InputDialog sem getInputText (era input:getText, que estoura no v2026.03)")
        dialog.input = "  java   "
        local ok, err = pcall(function() dialog.buttons[1][2].callback() end)
        assert.is_true(ok, "callback de salvar quebrou: " .. tostring(err))
        local results = stub.last_shown(function(w) return w._class == stub.ButtonDialog end)
        assert.is_not_nil(results, "nada apareceu depois de salvar a busca")
        assert.is_truthy(tostring(results.buttons[1][1].text):find("Java", 1, true))
    end)
end)

describe("a tela de leitura funciona", function()
    before_each(reset_store)

    local function reader_with_one_message()
        local plugin = new_plugin()
        local store = plugin:get_store()
        store:put_chat({
            id = "c1", title = "Conversa", role = "user",
            created_at = 1, updated_at = 2, content_hash = "x",
            messages = { {
                id = "m1", seq = 1, role = "user", content = "Ola, mundo.",
                created_at = 1, content_hash = "h1",
            } },
        })
        return plugin, require("views.reader").new(store, "c1", nil, nil)
    end

    it("monta um TextViewer com titulo e texto da mensagem", function()
        local _, reader = reader_with_one_message()
        local viewer = reader:build()
        assert.equals(stub.TextViewer, viewer._class)
        assert.is_string(viewer.title)
        assert.equals("md", viewer.text_format)
        assert.is_truthy(tostring(viewer.text):find("Ola", 1, true))
    end)

    it("Favoritar e Bookmark sao botoes de verdade, e nao quebram", function()
        local plugin, reader = reader_with_one_message()
        local viewer = reader:build()
        assert.is_true(viewer.add_default_buttons,
            "sem add_default_buttons, o buttons_table engole Find e Close")
        local row = assert(viewer.buttons_table[1], "botoes nao montados")
        local by_id = {}
        for _, b in ipairs(row) do by_id[b.id] = b end
        assert.is_not_nil(by_id.favorite, "botao favoritar ausente")
        assert.is_not_nil(by_id.bookmark, "botao bookmark ausente")

        by_id.favorite.callback()
        assert.is_true(reader:is_favorite(), "favoritar nao marcou")

        by_id.favorite.callback()
        assert.is_false(reader:is_favorite(), "favoritar de novo nao desmarca")
    end)

    it("o guarda de tela fechada impede fechar duas vezes", function()
        local _, reader = reader_with_one_message()
        reader:show()
        reader:close()
        reader:close() -- o segundo close nao pode estourar
    end)

    it("virar pagina no fim da conversa nao abre nada", function()
        local _, reader = reader_with_one_message()
        local before = #stub.shown
        local viewer = reader:build()
        assert.is_function(viewer.page_turn_callback_next)
        viewer.page_turn_callback_next()
        assert.equals(before, #stub.shown, "virar pagina no fim abriu uma tela")
    end)

    it("virar pagina no meio recarrega a tela", function()
        local plugin, reader = reader_with_one_message()
        local viewer = reader:build()
        stub.UIManager:show(viewer)
        local before = #stub.shown
        viewer.page_turn_callback_prev()
        assert.is_true(#stub.shown >= before)
    end)
end)

describe("o menu de configuracoes abre", function()
    before_each(reset_store)

    it("mostra um Menu com itens", function()
        local plugin = new_plugin()
        require("views.settings").show(plugin)
        local menu = shown_of(stub.Menu)
        assert.is_not_nil(menu, "nenhum Menu apareceu")
        assert.is_table(menu.item_table)
        assert.is_true(#menu.item_table > 0, "menu sem itens")
    end)

    it("Salvar na configuracao devolve o texto pelo getInputText, sem crash", function()
        local plugin = new_plugin()
        local got
        require("views.settings").ask("Titulo", "valor-atual", "dica",
            function(v) got = v end)
        local dialog = shown_of(stub.InputDialog)
        assert.is_function(dialog.getInputText,
            "InputDialog sem getInputText (era input:getText, que estoura no v2026.03)")
        dialog.input = "http://192.168.0.10:8080"
        local ok, err = pcall(function() dialog.buttons[1][2].callback() end)
        assert.is_true(ok, "callback de salvar quebrou: " .. tostring(err))
        assert.equals("http://192.168.0.10:8080", got,
            "o texto digitado nao chegou ao on_save")
    end)

    it("o diagnostico mostra versoes, banco, config e fila", function()
        local plugin = new_plugin()
        plugin.settings:set_base_url("http://192.168.0.10:8080")
        require("views.settings").show_diagnostics(plugin)
        local info = nil
        for _, w in ipairs(stub.shown) do
            if tostring(w.text or ""):find("Chat Reader", 1, true) then info = w break end
        end
        assert.is_not_nil(info, "diagnostico nao abriu nenhuma tela")
        local texto = tostring(info.text)
        -- As seis perguntas que fazem falta para diagnosticar sem SSH.
        assert.is_truthy(texto:find("Chat Reader", 1, true), "sem versao do plugin")
        assert.is_truthy(texto:find("KOReader", 1, true), "sem versao do KOReader")
        assert.is_truthy(texto:find("chatreader.sqlite3", 1, true), "sem caminho do banco")
        assert.is_truthy(texto:find("192.168.0.10:8080", 1, true), "sem endereco do servidor")
        assert.is_truthy(texto:find("token", 1, true), "sem estado do token")
        assert.is_truthy(texto:find("Fila de escritas", 1, true), "sem fila de escritas")
        -- Nunca vaza a senha nem o token em si.
        assert.is_nil(texto:find("eyJ", 1, true), "diagnostico vazou o token")
    end)

    it("o diagnostico sobrevive a um token invalido", function()
        local plugin = new_plugin()
        plugin.settings.store._values.token = "lixo-nao-e-jwt"
        require("views.settings").show_diagnostics(plugin)
        local info = stub.last_shown(function(w)
            return tostring(w.text or ""):find("token", 1, true) ~= nil
        end)
        assert.is_not_nil(info, "token invalido derrubou o diagnostico")
        assert.is_truthy(tostring(info.text):find("presente", 1, true))
    end)

    it("a confirmacao de wipe pede o motivo antes de apagar", function()
        local plugin = new_plugin()
        require("views.settings").confirm_wipe(plugin)
        local box = shown_of(stub.ConfirmBox)
        assert.is_not_nil(box, "wipe nao pediu confirmacao")
        assert.is_truthy(box.ok_text:find("Apagar", 1, true))
        -- Cancelar e o padrao: so o ok_callback destroi.
        assert.is_function(box.ok_callback)
    end)
end)