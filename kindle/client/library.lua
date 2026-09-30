-- Logica de tela sem KOReader: vira linha o que o store devolve.
--
-- Fica separado de `ui/` de proposito. A UI e a camada que depende do framework e
-- nao da para testar no container; o que decide o que aparece na tela — o que entra
-- na biblioteca, a ordem, o subtitulo, o trecho em volta do termo buscado — e logica
-- pura e testavel, e e ela que mora aqui.

local M = {}

-- ---------------------------------------------------------------------------
-- Datas
-- ---------------------------------------------------------------------------

-- O backend manda ISO-8601 com Z (UTC). So a data interessa na lista, entao corta
-- antes de converter: `os.time` nao aceita a string inteira.
function M.parse_when(iso)
    if type(iso) ~= "string" then return nil end
    local y, mo, d = iso:match("^(%d%d%d%d)-(%d%d)-(%d%d)")
    if not y then return nil end
    return { year = tonumber(y), month = tonumber(mo), day = tonumber(d) }
end

function M.when_label(iso)
    local when = M.parse_when(iso)
    if not when then return nil end
    return string.format("%02d/%02d/%04d", when.day, when.month, when.year)
end

-- ---------------------------------------------------------------------------
-- Biblioteca
-- ---------------------------------------------------------------------------

local function plural(n, one, many)
    return n == 1 and one or many
end

function M.chat_subtitle(chat)
    local parts = {}
    local count = chat.messageCount or 0
    if count > 0 then
        parts[#parts + 1] = string.format("%d %s", count,
            plural(count, "mensagem", "mensagens"))
    end
    local when = M.when_label(chat.updatedAt)
    if when then parts[#parts + 1] = when end
    return #parts > 0 and table.concat(parts, " · ") or "sem mensagens"
end

-- Uma linha por conversa. O titulo vai como vier: cortar no meio da palavra e pior
-- do que deixar o botao do KOReader encolher.
function M.chat_row(chat)
    return { id = chat.id, title = chat.title or "(sem titulo)",
             subtitle = M.chat_subtitle(chat), favorite = chat.favorite and true or false }
end

-- @param opts { only_favorites = boolean, limit = number, offset = number }
function M.chat_rows(store, opts)
    opts = opts or {}
    local chats = store:list_chats(opts.limit, opts.offset, opts.only_favorites)
    local rows = {}
    for i, chat in ipairs(chats) do rows[i] = M.chat_row(chat) end
    return rows
end

-- ---------------------------------------------------------------------------
-- Busca
-- ---------------------------------------------------------------------------

-- Recorta em volta do termo, e nao do inicio da mensagem: o que interessa ver e o
-- trecho que casou, e ele costuma estar no meio de uma resposta longa.
function M.snippet(content, term, width)
    width = width or 120
    local text = (content or ""):gsub("\n+", " "):gsub("%s+", " ")
    local plain = text:lower()
    local at = term and term ~= "" and plain:find(term:lower(), 1, true) or nil

    if not at or #text <= width then
        if #text <= width then return text end
        return text:sub(1, width) .. "..."
    end

    local from = math.max(1, at - math.floor(width / 3))
    local to = math.min(#text, from + width)
    local out = text:sub(from, to)
    if from > 1 then out = "..." .. out end
    if to < #text then out = out .. "..." end
    return out
end

-- Uma linha por resultado. `chatId`/`seq` e o que o leitor usa para abrir a
-- mensagem certa, e nao o offset da lista de resultados.
function M.search_rows(results, term, width)
    local rows = {}
    for i, hit in ipairs(results) do
        rows[i] = { chatId = hit.chat_id, seq = hit.seq, messageId = hit.message_id,
                    title = hit.title or "(sem titulo)",
                    snippet = M.snippet(hit.content, term, width) }
    end
    return rows
end

return M
