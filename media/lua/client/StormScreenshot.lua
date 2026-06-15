require("StormBase64")

StormScreenshot = StormScreenshot or {}

-- BYTES_PER_CHUNK must be a multiple of 3 so non-final chunks never emit `=` padding.
-- 22500 bytes -> exactly 30000 base64 chars per chunk, matching the wire payload size
-- that was used before this was split over ticks.
local BYTES_PER_CHUNK = 22500
local CHUNKS_PER_TICK = 1
local POLL_DELAY_TICKS = 60
local POLL_TIMEOUT_TICKS = 600

local pendingCapture = nil

local function sendChunk(screenshotId, index, total, data)
    sendClientCommand("stormScreenshot", "chunk", {
        id = screenshotId,
        index = index,
        total = total,
        data = data,
    })
end

local function processCapture()
    if not pendingCapture then
        return
    end

    local state = pendingCapture.state

    if state == "polling" then
        pendingCapture.ticks = pendingCapture.ticks + 1
        if pendingCapture.ticks < POLL_DELAY_TICKS then
            return
        end
        if pendingCapture.ticks > POLL_TIMEOUT_TICKS then
            if pendingCapture.texture then
                pendingCapture.texture:destroy()
            end
            pendingCapture = nil
            return
        end

        if not pendingCapture.texture then
            if not fileExists(pendingCapture.screenshotPath) then
                return
            end
            local texture = getTexture(pendingCapture.screenshotPath)
            if not texture then
                print("[Storm] Failed to load screenshot texture")
                pendingCapture = nil
                return
            end
            pendingCapture.texture = texture
            return
        end

        if not pendingCapture.texture:isReady() then
            return
        end

        pendingCapture.texture:saveToZomboidDirectory("Lua/" .. pendingCapture.filename)
        pendingCapture.texture:destroy()
        pendingCapture.texture = nil

        local stream = getFileInput(pendingCapture.filename)
        if not stream then
            print("[Storm] Failed to open Lua copy of screenshot")
            pendingCapture = nil
            return
        end
        pendingCapture.bytes = stream:readAllBytes()
        stream:close()

        pendingCapture.totalBytes = #pendingCapture.bytes
        pendingCapture.totalChunks =
            math.max(1, math.ceil(pendingCapture.totalBytes / BYTES_PER_CHUNK))
        pendingCapture.encodePos = 1
        pendingCapture.chunkIndex = 0
        pendingCapture.state = "streaming"
    elseif state == "streaming" then
        local bytes = pendingCapture.bytes
        local totalBytes = pendingCapture.totalBytes
        for _ = 1, CHUNKS_PER_TICK do
            if pendingCapture.encodePos > totalBytes then
                break
            end
            local pos = pendingCapture.encodePos
            local endPos = math.min(pos + BYTES_PER_CHUNK - 1, totalBytes)
            local data = StormBase64.encode(bytes, pos, endPos)
            pendingCapture.chunkIndex = pendingCapture.chunkIndex + 1
            sendChunk(
                pendingCapture.id,
                pendingCapture.chunkIndex,
                pendingCapture.totalChunks,
                data
            )
            pendingCapture.encodePos = endPos + 1
        end
        if pendingCapture.encodePos > totalBytes then
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
    local screenshotPath = getMyDocumentFolder() .. "/Screenshots/" .. filename

    takeScreenshot(filename)

    pendingCapture = {
        id = screenshotId,
        filename = filename,
        screenshotPath = screenshotPath,
        ticks = 0,
        state = "polling",
    }

    print("[Storm] Screenshot capture started: " .. screenshotId)
    return true
end

local function onServerCommand(module, command, args)
    if module ~= "stormScreenshot" then
        return
    end
    if command == "request" and args and args.requestId then
        StormScreenshot.captureAndSend(args.requestId)
    end
end

Events.OnTick.Add(processCapture)
Events.OnServerCommand.Add(onServerCommand)
