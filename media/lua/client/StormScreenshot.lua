require "StormBase64"

StormScreenshot = StormScreenshot or {}

local LUA_CACHE_PREFIX = "../Lua/"
local CHUNK_SIZE = 30000
local ENCODE_BATCH = 199998
local POLL_DELAY_TICKS = 60
local POLL_TIMEOUT_TICKS = 600

local pendingCapture = nil

local function sendChunks(screenshotId, base64str)
    local totalLen = string.len(base64str)
    local totalChunks = math.ceil(totalLen / CHUNK_SIZE)

    for i = 1, totalChunks do
        local startPos = (i - 1) * CHUNK_SIZE + 1
        local endPos = math.min(i * CHUNK_SIZE, totalLen)
        local chunk = string.sub(base64str, startPos, endPos)

        sendClientCommand("stormScreenshot", "chunk", {
            id = screenshotId,
            index = i,
            total = totalChunks,
            data = chunk,
        })
    end
end

local function processCapture()
    if not pendingCapture then return end

    local state = pendingCapture.state

    if state == "polling" then
        pendingCapture.ticks = pendingCapture.ticks + 1
        if pendingCapture.ticks < POLL_DELAY_TICKS then return end
        if pendingCapture.ticks > POLL_TIMEOUT_TICKS then
            print("[Storm] Screenshot capture timed out: " .. pendingCapture.filename)
            pendingCapture = nil
            return
        end
        local stream = getFileInput(pendingCapture.filename)
        if not stream then return end
        pendingCapture.bytes = stream:readAllBytes()
        stream:close()
        print("[Storm] Read " .. #pendingCapture.bytes .. " bytes from screenshot")
        pendingCapture.encodePos = 1
        pendingCapture.encodedParts = {}
        pendingCapture.state = "encoding"

    elseif state == "encoding" then
        local bytes = pendingCapture.bytes
        local pos = pendingCapture.encodePos
        local endPos = math.min(pos + ENCODE_BATCH - 1, #bytes)

        local part = StormBase64.encode(bytes, pos, endPos)
        local parts = pendingCapture.encodedParts
        parts[#parts + 1] = part

        pendingCapture.encodePos = endPos + 1
        if pendingCapture.encodePos > #bytes then
            local base64str = table.concat(parts)
            print("[Storm] Encoded to " .. string.len(base64str) .. " Base64 chars")
            pendingCapture.bytes = nil
            pendingCapture.encodedParts = nil
            sendChunks(pendingCapture.id, base64str)
            print("[Storm] Sent screenshot in " .. math.ceil(string.len(base64str) / CHUNK_SIZE) .. " chunks")
            pendingCapture = nil
        end
    end
end

function StormScreenshot.captureAndSend(screenshotId)
    if pendingCapture then
        print("[Storm] Screenshot capture already in progress")
        return false
    end

    local filename = "storm_screenshot_" .. screenshotId .. ".png"

    takeScreenshot(LUA_CACHE_PREFIX .. filename)

    pendingCapture = {
        id = screenshotId,
        filename = filename,
        ticks = 0,
        state = "polling",
    }

    print("[Storm] Screenshot capture started: " .. screenshotId)
    return true
end

local function onServerCommand(module, command, args)
    if module ~= "stormScreenshot" then return end
    if command == "request" and args and args.requestId then
        StormScreenshot.captureAndSend(args.requestId)
    end
end

Events.OnTick.Add(processCapture)
Events.OnServerCommand.Add(onServerCommand)
