-- O plugin tem duas metades: a logica, que roda no container e tem spec, e a UI, que
-- so roda no KOReader. A UI nao tem como ser testada aqui — mas ela pode ser
-- *compilada*, e um erro de sintaxe ou um `end` faltando em um arquivo de 150 linhas
-- e o tipo de coisa que so apareceria no aparelho, na mao, na hora de abrir o menu.
--
-- loadfile compila sem executar, entao os `require("ui/widget/...")` — que so existem
-- no aparelho — nao sao acionados aqui.

local ARQUIVOS = {
    "main.lua",
    "client/http.lua",
    "client/library.lua",
    "client/positions.lua",
    "client/settings.lua",
    "client/sync.lua",
    "store/memory.lua",
    "store/sqlite.lua",
    "views/library.lua",
    "views/reader.lua",
    "views/settings.lua",
}

describe("todo arquivo do plugin compila", function()
    for _, arquivo in ipairs(ARQUIVOS) do
        it(arquivo, function()
            local chunk, err = loadfile(arquivo)
            assert.is_not_nil(chunk, arquivo .. ": " .. tostring(err))
        end)
    end
end)

describe("a UI nao carrega o que nao precisa", function()
    it("views/ e client/library.lua so usam modulos nativos dentro de funcao", function()
        -- rapidjson e o JSON nativo do KOReader. Um require no topo do arquivo
        -- quebraria o container de teste e nao o aparelho — o caminho errado para
        -- descobrir o problema. Dentro de uma funcao, so e carregado se a tela
        -- realmente precisar (e o ui_spec cobre esse caminho com stub).
        --
        -- A excecao e views/settings.lua, que le o JWT para mostrar quanto tempo ele
        -- ainda vale: la o require precisa ser tardio, nao ausente.
        for _, arquivo in ipairs({ "views/library.lua", "views/reader.lua",
                                   "client/library.lua", "client/positions.lua" }) do
            local fonte = assert(io.open(arquivo)):read("*a")
            assert.is_nil(fonte:find("rapidjson", 1, true), arquivo .. " puxa rapidjson")
            assert.is_nil(fonte:find('require("lfs")', 1, true), arquivo .. " puxa lfs")
        end

        -- views/settings.lua: usa rapidjson para ler a validade do JWT, mas so pode
        -- ser dentro de uma funcao — no topo, quebraria o container de teste.
        local fonte = assert(io.open("views/settings.lua")):read("*a")
        for linha in fonte:gmatch("[^\n]+") do
            if linha:find("rapidjson", 1, true) then
                assert.is_truthy(linha:match("^%s"),
                    "views/settings.lua tem require de rapidjson fora de funcao: " .. linha)
            end
        end
    end)
end)
