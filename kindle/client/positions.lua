-- Onde parou de ler, por conversa.
--
-- Estado de tela, e nao dado do servidor: mora em LuaSettings (o mesmo mecanismo que
-- o KOReader usa para preferencia), nao no SQLite, que guarda o conteudo sincronizado.
--
-- Recebe o settings ja pronto (o main.lua passa o LuaSettings) para poder ser testado
-- com um duplo em memoria.

local M = {}

local Positions = {}
Positions.__index = Positions

function M.new(settings)
    local data = settings:readSetting("positions", nil) or {}
    return setmetatable({ settings = settings, data = data }, Positions)
end

function Positions:get(chat_id)
    return self.data[chat_id]
end

-- Marca em memoria a cada mudanca de mensagem; o disco so recebe no Save do leitor.
-- Gravar o arquivo a cada virada de pagina desgastaria o cartao a toa — e o Save
-- acontece junto com o fecho do leitor, que e quando a posicao importa.
function Positions:set(chat_id, message_id, seq)
    self.data[chat_id] = { messageId = message_id, seq = seq }
end

-- Usado junto com o "apagar biblioteca local": manter a posicao de leitura de uma
-- conversa que nao existe mais nao ajuda ninguem.
function Positions:clear()
    self.data = {}
    self:save()
end

function Positions:save()
    self.settings:saveSetting("positions", self.data)
    self.settings:flush()
end

return M
