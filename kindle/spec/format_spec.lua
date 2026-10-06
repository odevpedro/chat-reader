-- Texto de exibicao: quem fala + markdown -> texto plano.
--
-- Nao depende do KOReader: e logica pura que roda igual no container e no aparelho,
-- e foi a descoberta em dispositivo (TextViewer nao renderiza `text_format = "md"`)
-- que a motivou.

local format = require("client.format")

describe("client/format", function()
    describe("role_label", function()
        it("mapeia os papeis comuns", function()
            assert.equals("Você", format.role_label({ role = "user" }))
            assert.equals("Assistente", format.role_label({ role = "assistant" }))
            assert.equals("Sistema", format.role_label({ role = "system" }))
        end)

        it("papeis desconhecidos viram Mensagem; sem mensagem, nada", function()
            assert.equals("Mensagem", format.role_label({ role = "tool" }))
            assert.equals("Assistente", format.role_label({}))
            assert.equals("", format.role_label(nil))
        end)

        it("o backend entrega o papel em maiusculas e nao pode virar Mensagem", function()
            assert.equals("Você", format.role_label({ role = "USER" }))
            assert.equals("Assistente", format.role_label({ role = "ASSISTANT" }))
            assert.equals("Sistema", format.role_label({ role = "SYSTEM" }))
        end)
    end)

    describe("md_to_plain", function()
        it("tira cabecalho, negrito, italico e codigo inline", function()
            local text = "# Título\n\n**negrito**, *ênfase* e `código`."
            assert.equals("Título\n\nnegrito, ênfase e código.", format.md_to_plain(text))
        end)

        it("nao transforma 3 * 4 nem 5*5 em italico", function()
            assert.equals("3 * 4 e 5*5 sao numeros", format.md_to_plain("3 * 4 e 5*5 sao numeros"))
            assert.equals("um vira italico", format.md_to_plain("*um* vira italico"))
        end)

        it("preserva bloco de codigo inteiro sem marcas de cerca", function()
            local md = "```lua\nlocal x = 1 -- **nao** italico\nprint(x)\n```\n\napos o bloco *ok*."
            local plain = "  local x = 1 -- **nao** italico\n  print(x)\n\napos o bloco ok."
            assert.equals(plain, format.md_to_plain(md))
        end)

        it("link vira texto, citacao no inicio da linha vira marcador", function()
            local text = "[veja](https://ex.com) e citacao\n> dizia o velho."
            assert.equals("veja e citacao\n› dizia o velho.", format.md_to_plain(text))
        end)

        it("aceita conteudo vazio e normaliza fim de linha do Windows", function()
            assert.equals("", format.md_to_plain(""))
            assert.equals("a\nb", format.md_to_plain("a\r\nb"))
            assert.equals("", format.md_to_plain(nil))
        end)
    end)

    describe("display_text", function()
        it("coloca quem fala acima do corpo com regua de separacao", function()
            local msg = { role = "user", content = "minha pergunta" }
            assert.equals("Você\n" .. string.rep("-", 24) .. "\n\nminha pergunta",
                format.display_text(msg))
        end)

        it("corpo vazio mostra so o papel", function()
            assert.equals("Assistente", format.display_text({ role = "assistant", content = "" }))
            assert.equals("", format.display_text(nil))
        end)

        it("pergunta com resposta na proxima pagina avisa onde ela esta", function()
            local pergunta = { role = "user", content = "oi, bom dia!" }
            local resposta = { role = "assistant", content = "oi! tudo bem?" }
            local saida = format.display_text(pergunta, resposta)
            assert.is_truthy(saida:find("próxima página", 1, true))
            -- sem proxima mensagem, ou proxima sendo outra pergunta, a dica nao sai
            assert.is_false(format.display_text(pergunta):find("próxima página", 1, true) ~= nil)
            assert.is_false(format.display_text(pergunta, { role = "user", content = "outra" }):find("próxima página", 1, true) ~= nil)
            -- a resposta em si nao ganha dica
            assert.is_false(format.display_text(resposta, pergunta):find("próxima página", 1, true) ~= nil)
        end)

        it("dica e papel funcionam com o papel maiusculo do backend", function()
            local pergunta = { role = "USER", content = "oi, bom dia!" }
            local resposta = { role = "ASSISTANT", content = "oi! tudo bem?" }
            local saida = format.display_text(pergunta, resposta)
            assert.is_truthy(saida:find("Você", 1, true))
            assert.is_truthy(saida:find("próxima página", 1, true))
            local pagina2 = format.display_text(resposta, pergunta)
            assert.is_truthy(pagina2:find("Assistente", 1, true))
            assert.is_false(pagina2:find("próxima página", 1, true) ~= nil)
        end)
    end)
end)