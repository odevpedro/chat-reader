-- Texto de exibicao das mensagens: quem fala + corpo legivel no e-ink.
--
-- Descoberta de verdade no aparelho: o `text_format = "md"` do TextViewer nao existe
-- no v2026.03 (frontend/ui/widget/textviewer.lua nao tem essa opcao), entao o markdown
-- chegava cru na tela — asteriscos, `#`, cercas de codigo. Em vez de renderizar HTML
-- (o luamd do KOReader devolve HTML, e o TextViewer mostra texto puro), aqui o
-- markdown vira **texto plano** deterministico e testado no container.
--
-- Regras de bom senso para conversa:
--   * bloco de codigo (```) e preservado integral e levemente recuado — nada de
--     markdown dentro dele;
--   * "3 * 4" e 5*5 nao viram italico: so pares com o marcador colado ao texto;
--   * listas, links e citacoes ficam como linhas legiveis;
--   * o corpo do usuario nao e markdown intencional, mas o texto nao distingue
--     "queria que *assim* nao virasse italico" de "use **fim** aqui" — aceitamos o
--     risco e seguimos com a heuristica acima.

local M = {}

local ROLE_LABELS = {
    ["user"] = "Você",
    ["assistant"] = "Assistente",
    ["system"] = "Sistema",
}

local function normalize_role(role)
    if role == nil then return "" end
    return role:lower()
end

function M.role_label(message)
    if not message then return "" end
    local role = normalize_role(message.role)
    -- dados antigos podem vir sem papel; convencao: e o outro lado da conversa
    if role == nil or role == "" then return "Assistente" end
    return ROLE_LABELS[role] or "Mensagem"
end

-- Sentaquinas sentinelas para blocos de codigo: impossiveis de aparecer em texto.
local SENTINEL_OPEN = "\1B"
local SENTINEL_CLOSE = "\1E"

--- Markdown -> texto plano, uma linha por vez.
-- @param text string|nil
-- @return string
function M.md_to_plain(text)
    text = (text or ""):gsub("\r\n", "\n"):gsub("\r", "\n")

    -- Blocos de codigo sao guardados antes de qualquer outra regra; dentro deles
    -- nada e interpretado (nem bullet, nem negrito), e as marcas de cerca ``` sobem.
    local blocks = {}
    text = text:gsub("```[^\n]*\n(.-)```", function(code)
        blocks[#blocks + 1] = code:gsub("%s+$", "")
        return SENTINEL_OPEN .. tostring(#blocks) .. SENTINEL_CLOSE
    end)

    local lines = {}
    for line in (text .. "\n"):gmatch("(.-)\n") do
        local l = line
        if l:match("^" .. SENTINEL_OPEN .. "%d+" .. SENTINEL_CLOSE .. "$") then
            -- linha-sentinela: restaurada por inteiro la no fim.
            lines[#lines + 1] = l
        else
            l = l:gsub("^[#]+%s*", "")  -- "## Titulo" -> "Titulo"
            l = l:gsub("^%s*>%s?", "› ") -- citacao vira um marcador simples
            l = l:gsub("%*%*([^%*\n]*)%*%*", "%1") -- negrito
            l = l:gsub("%*([^%*%s\n]+)%*", "%1") -- italico: um run sem espacos, senao "3 * 4" vira lixo
            l = l:gsub("`([^`\n]*)`", "%1") -- codigo inline
            l = l:gsub("%[(.-)%]%((.-)%)", "%1") -- [texto](url) -> texto
            lines[#lines + 1] = l:gsub("%s+$", "")
        end
    end
    text = table.concat(lines, "\n")

    -- Restaura os blocos de codigo com um recuo de dois espacos para evidenciar
    -- que e codigo (o tipo mono do TextViewer nao e controlavel daqui).
    text = text:gsub(SENTINEL_OPEN .. "(%d+)" .. SENTINEL_CLOSE, function(i)
        local code = blocks[tonumber(i)]
        if not code then return "" end
        return "  " .. code:gsub("\n", "\n  ")
    end)

    return text
end

-- Texto final da mensagem: cabecalho com o papel + corpo convertido. O cabecalho
-- ganha uma regua de hifens: em texto plano e a separacao mais barata de distinguir
-- a troca de quem fala (o TextViewer nao deixa estilizar so um trecho).
local HEADER_RULE = string.rep("-", 24)

-- O leitor mostra uma mensagem por pagina: a pergunta precisa avisar que a resposta
-- esta na proxima pagina, senao parece que o chat respondeu em branco.
local NEXT_PAGE_HINT = "\n\n(→ a resposta está na próxima página: botão Próxima ›)"

function M.display_text(message, next_message)
    if not message then return "" end
    local who = M.role_label(message)
    local body = M.md_to_plain(message.content)
    if body == "" then return who end
    local text = who .. "\n" .. HEADER_RULE .. "\n\n" .. body
    local role = normalize_role(message.role)
    if role == "user" and next_message
        and normalize_role(next_message.role) ~= "user"
        and normalize_role(next_message.role) ~= "" then
        text = text .. NEXT_PAGE_HINT
    end
    return text
end

return M