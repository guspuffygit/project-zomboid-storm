require "StormBase64"

StormScreenshot = StormScreenshot or {}

local LUA_CACHE_PREFIX = "../Lua/"
local CHUNK_SIZE = 30000
local POLL_DELAY_TICKS = 60
local POLL_TIMEOUT_TICKS = 600

local pendingCapture = nil

local function readFileBytes(filename)
    local stream = getFileInput(filename)
    if not stream then return nil end

    local bytes = stream:readAllBytes()
    stream:close()

    for i = 1, #bytes do
        if bytes[i] < 0 then bytes[i] = bytes[i] + 256 end
    end

    return bytes
end

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

    pendingCapture.ticks = pendingCapture.ticks + 1

    if pendingCapture.ticks < POLL_DELAY_TICKS then return end

    if pendingCapture.ticks > POLL_TIMEOUT_TICKS then
        print("[Storm] Screenshot capture timed out: " .. pendingCapture.filename)
        pendingCapture = nil
        return
    end

    local bytes = readFileBytes(pendingCapture.filename)
    if not bytes or #bytes == 0 then return end

    print("[Storm] Read " .. #bytes .. " bytes from screenshot")

    local base64str = StormBase64.encode(bytes)
    print("[Storm] Encoded to " .. string.len(base64str) .. " Base64 chars")

    sendChunks(pendingCapture.id, base64str)
    print("[Storm] Sent screenshot in " .. math.ceil(string.len(base64str) / CHUNK_SIZE) .. " chunks")

    pendingCapture = nil
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
