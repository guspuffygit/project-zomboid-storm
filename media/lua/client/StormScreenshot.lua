require("StormBase64")

StormScreenshot = StormScreenshot or {}

-- One putUTF string is length-prefixed with a signed short (GameWindow.StringUTF.save).
-- 24573 raw bytes -> 32764 base64 chars, safely under the 32767 cap and a multiple of 3
-- so non-final pieces never emit '=' padding.
local BYTES_PER_PIECE = 24573

-- Number of base64 pieces packed into a single sendClientCommand. UdpConnection's outbound
-- ByteBuffer is 1 MB; 28 maxed pieces serialize to ~918 KB (including outer-table overhead),
-- leaving ~82 KB of headroom under the cap. 30 is the hard upper bound; beyond that the
-- ByteBufferWriter throws BufferOverflowException mid-send.
local PIECES_PER_PACKET = 28

-- Minimum ticks between consecutive packet sends. With PIECES_PER_PACKET pieces encoded
-- one-per-tick this is normally exceeded by the encoding phase alone, but keeps a floor
-- for small PIECES_PER_PACKET values so RakNet's keepalive ping has room to interleave.
local TICKS_PER_PACKET = 1

local POLL_DELAY_TICKS = 60
local POLL_TIMEOUT_TICKS = 600

local pendingCapture = nil

local function sendPacket(screenshotId, index, total, pieces)
    sendClientCommand("stormScreenshot", "chunk", {
        id = screenshotId,
        index = index,
        total = total,
        pieces = pieces,
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
        local bytesPerPacket = BYTES_PER_PIECE * PIECES_PER_PACKET
        pendingCapture.totalPackets =
            math.max(1, math.ceil(pendingCapture.totalBytes / bytesPerPacket))
        pendingCapture.encodePos = 1
        pendingCapture.packetIndex = 0
        pendingCapture.tickGap = TICKS_PER_PACKET
        pendingCapture.pieces = {}
        pendingCapture.state = "streaming"
    elseif state == "streaming" then
        local bytes = pendingCapture.bytes
        local totalBytes = pendingCapture.totalBytes
        local pieces = pendingCapture.pieces

        -- Amortize Lua base64 work across ticks: encode at most one piece per tick.
        -- Encoding all PIECES_PER_PACKET pieces in a single tick caused multi-100ms
        -- main-thread stalls visible as client lag during the screenshot stream.
        if pendingCapture.encodePos <= totalBytes and #pieces < PIECES_PER_PACKET then
            local pos = pendingCapture.encodePos
            local endPos = math.min(pos + BYTES_PER_PIECE - 1, totalBytes)
            pieces[#pieces + 1] = StormBase64.encode(bytes, pos, endPos)
            pendingCapture.encodePos = endPos + 1
        end

        local bufferFull = #pieces >= PIECES_PER_PACKET
        local atEof = pendingCapture.encodePos > totalBytes
        if not (bufferFull or (atEof and #pieces > 0)) then
            return
        end

        pendingCapture.tickGap = pendingCapture.tickGap + 1
        if pendingCapture.tickGap < TICKS_PER_PACKET then
            return
        end
        pendingCapture.tickGap = 0

        pendingCapture.packetIndex = pendingCapture.packetIndex + 1
        sendPacket(
            pendingCapture.id,
            pendingCapture.packetIndex,
            pendingCapture.totalPackets,
            pieces
        )
        pendingCapture.pieces = {}

        if atEof then
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
