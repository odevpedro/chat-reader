-- JSON minimo em Lua puro para o contrato ponta a ponta (scripts/contract_test.lua).
--
-- Nao e um parser de producao: serve para conversar com o backend real no teste de
-- contrato, que roda no container (sem rapidjson, que e modulo nativo do KOReader).
-- Cobre o que o protocolo usa: objetos, arrays, strings com escapes, numeros e
-- true/false/null. Numeros viram numeros, strings viram strings — nada de float fantasma
-- que o Luajit arredondaria para um syncToken errado.

local M = {}

local function is_array(t)
    if type(t) ~= "table" or next(t) == nil then return false end
    local n = #t
    for k in pairs(t) do
        if type(k) ~= "number" or k < 1 or k > n or math.floor(k) ~= k then
            return false
        end
    end
    return n > 0
end

local function escape(s)
    return (s:gsub('[%z\1-\31\\"]', function(c)
        local map = { ['"'] = '\\"', ["\\"] = "\\\\", ["\b"] = "\\b",
                      ["\f"] = "\\f", ["\n"] = "\\n", ["\r"] = "\\r",
                      ["\t"] = "\\t" }
        local e = map[c]
        if e then return e end
        return string.format("\\u%04x", c:byte())
    end))
end

local function encode_value(v)
    local t = type(v)
    if t == "nil" then return "null" end
    if t == "boolean" then return v and "true" or "false" end
    if t == "number" then
        if v ~= v or v == math.huge or v == -math.huge then return "null" end
        -- Jackson espera inteiros sem ".0" (syncToken/version); o resto fica ok.
        if v == math.floor(v) and math.abs(v) < 2 ^ 53 then
            return string.format("%d", v)
        end
        return string.format("%.14g", v)
    end
    if t == "string" then return '"' .. escape(v) .. '"' end
    if t == "table" then
        if is_array(v) then
            local parts = {}
            for i = 1, #v do parts[i] = encode_value(v[i]) end
            return "[" .. table.concat(parts, ",") .. "]"
        end
        local parts = {}
        for k, value in pairs(v) do
            if type(k) == "string" then
                parts[#parts + 1] = '"' .. escape(k) .. '":' .. encode_value(value)
            end
        end
        return "{" .. table.concat(parts, ",") .. "}"
    end
    error("nao sei serializar " .. t)
end

function M.encode(value)
    return encode_value(value)
end

-- ---------------------------------------------------------------------------
-- Decoder
-- ---------------------------------------------------------------------------

local decode

local function decode_hex4(s, i)
    return tonumber(s:sub(i, i + 3), 16)
end

-- \uXXXX com possivel par substituto (JS encodou acentos em \ud83c\udf89) -> UTF-8
local function utf8_from_codepoint(cp)
    if cp < 0x80 then
        return string.char(cp)
    elseif cp < 0x800 then
        return string.char(0xC0 + math.floor(cp / 0x40),
                            0x80 + cp % 0x40)
    elseif cp < 0x10000 then
        return string.char(0xE0 + math.floor(cp / 0x1000),
                           0x80 + math.floor(cp % 0x1000 / 0x40),
                           0x80 + cp % 0x40)
    else
        return string.char(0xF0 + math.floor(cp / 0x40000),
                           0x80 + math.floor(cp % 0x40000 / 0x1000),
                           0x80 + math.floor(cp % 0x1000 / 0x40),
                           0x80 + cp % 0x40)
    end
end

local function decode_string(s, i)
    -- s[i] == '"'
    local out = {}
    i = i + 1
    while i <= #s do
        local c = s:sub(i, i)
        if c == '"' then return table.concat(out), i + 1 end
        if c ~= "\\" then
            out[#out + 1] = c
            i = i + 1
        else
            local e = s:sub(i + 1, i + 1)
            if e == "u" then
                local cp = decode_hex4(s, i + 2)
                if cp >= 0xD800 and cp <= 0xDBFF and s:sub(i + 6, i + 7) == "\\u" then
                    local lo = decode_hex4(s, i + 8)
                    cp = 0x10000 + (cp - 0xD800) * 0x400 + (lo - 0xDC00)
                    i = i + 12
                else
                    i = i + 6
                end
                out[#out + 1] = utf8_from_codepoint(cp)
            else
                local map = { ['"'] = '"', ["\\"] = "\\", ["/"] = "/",
                              b = "\b", f = "\f", n = "\n", r = "\r", t = "\t" }
                if not map[e] then error("escape invalido em " .. i) end
                out[#out + 1] = map[e]
                i = i + 2
            end
        end
    end
    error("string sem fechamento")
end

local function decode_value(s, i)
    while s:sub(i, i) == " " do i = i + 1 end
    local c = s:sub(i, i)
    if c == "{" then
        local obj = {}
        i = i + 1
        while true do
            while s:sub(i, i) == " " do i = i + 1 end
            if s:sub(i, i) == "}" then return obj, i + 1 end
            local key
            key, i = decode_string(s, i)
            while s:sub(i, i) == " " do i = i + 1 end
            if s:sub(i, i) ~= ":" then error("esperava ':' em " .. i) end
            i = i + 1
            obj[key], i = decode_value(s, i)
            while s:sub(i, i) == " " do i = i + 1 end
            if s:sub(i, i) == "," then i = i + 1 end
        end
    elseif c == "[" then
        local arr = {}
        i = i + 1
        local k = 1
        while true do
            while s:sub(i, i) == " " do i = i + 1 end
            if s:sub(i, i) == "]" then return arr, i + 1 end
            arr[k], i = decode_value(s, i)
            k = k + 1
            while s:sub(i, i) == " " do i = i + 1 end
            if s:sub(i, i) == "," then i = i + 1 end
        end
    elseif c == '"' then
        return decode_string(s, i)
    elseif c == "t" and s:sub(i, i + 3) == "true" then
        return true, i + 4
    elseif c == "f" and s:sub(i, i + 4) == "false" then
        return false, i + 5
    elseif c == "n" and s:sub(i, i + 3) == "null" then
        return nil, i + 4
    else
        local j = i
        while s:sub(j, j):match("[0-9eE%+%-%.]") do j = j + 1 end
        local raw = s:sub(i, j - 1)
        if raw ~= "" and raw:match("^%-?%d") then
            local num = tonumber(raw)
            if num == nil then error("numero invalido em " .. i .. ": " .. raw) end
            return num, j
        end
        error("JSON inesperado em " .. i)
    end
end

function M.decode(s)
    local value, i = decode_value(s, 1)
    return value
end

return M