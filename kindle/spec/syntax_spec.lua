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
    "ui/library.lua",
    "ui/reader.lua",
    "ui/settings.lua",
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
    it("ui/ e client/library.lua nao dependem de modulos nativos do KOReader", function()
        -- rapidjson e o JSON nativo: um require no topo de um arquivo de logica
        -- quebraria o container de teste e nao o aparelho, que e o caminho errado
        -- para descobrir o problema.
        for _, arquivo in ipairs({ "ui/library.lua", "ui/reader.lua", "ui/settings.lua",
                                   "client/library.lua", "client/positions.lua" }) do
            local fonte = assert(io.open(arquivo)):read("*a")
            assert.is_nil(fonte:find("rapidjson", 1, true), arquivo .. " puxa rapidjson")
            assert.is_nil(fonte:find('require("lfs")', 1, true), arquivo .. " puxa lfs")
        end
    end)
end)
