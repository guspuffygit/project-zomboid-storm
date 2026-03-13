StormBase64 = StormBase64 or {}

local CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

local ENCODE = {}
for i = 0, 63 do ENCODE[i] = string.byte(CHARS, i + 1) end

local DECODE_LOOKUP = nil

local function getDecodeLookup()
    if not DECODE_LOOKUP then
        DECODE_LOOKUP = {}
        for i = 1, #CHARS do
            DECODE_LOOKUP[string.byte(CHARS, i)] = i - 1
        end
    end
    return DECODE_LOOKUP
end

function StormBase64.encode(bytes)
    local result = {}
    local n = #bytes
    for i = 1, n, 3 do
        local b1 = bytes[i]
        local b2 = bytes[i + 1] or 0
        local b3 = bytes[i + 2] or 0

        local idx1 = math.floor(b1 / 4)
        local idx2 = (b1 % 4) * 16 + math.floor(b2 / 16)
        local idx3 = (b2 % 16) * 4 + math.floor(b3 / 64)
        local idx4 = b3 % 64

        local c3 = (i + 1 <= n) and ENCODE[idx3] or 61
        local c4 = (i + 2 <= n) and ENCODE[idx4] or 61

        result[#result + 1] = string.char(ENCODE[idx1], ENCODE[idx2], c3, c4)
    end
    return table.concat(result)
end

function StormBase64.decode(str)
    local lookup = getDecodeLookup()
    local bytes = {}
    local len = #str
    for i = 1, len, 4 do
        local c1 = lookup[string.byte(str, i)] or 0
        local c2 = lookup[string.byte(str, i + 1)] or 0
        local c3 = lookup[string.byte(str, i + 2)] or 0
        local c4 = lookup[string.byte(str, i + 3)] or 0

        bytes[#bytes + 1] = c1 * 4 + math.floor(c2 / 16)
        if i + 2 <= len and string.byte(str, i + 2) ~= 61 then
            bytes[#bytes + 1] = (c2 % 16) * 16 + math.floor(c3 / 4)
        end
        if i + 3 <= len and string.byte(str, i + 3) ~= 61 then
            bytes[#bytes + 1] = (c3 % 4) * 64 + c4
        end
    end
    return bytes
end
