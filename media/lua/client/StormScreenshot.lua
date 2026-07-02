require("StormBase64")

StormScreenshot = StormScreenshot or {}

-- One putUTF string is length-prefixed with a signed short (GameWindow.StringUTF.save).
-- 24573 raw bytes -> 32764 base64 chars, safely under the 32767 cap and a multiple of 3
-- so non-final pieces never emit '=' padding.
local BYTES_PER_PIECE = 24573

-- Base64 pieces packed into a single sendClientCommand. This is only wire framing (how many
-- pieces ride in one packet); it is NOT the throttle. Disconnect safety comes from the
-- wall-clock throughput cap below, not from packet size. Kept configurable because fewer,
-- larger packets mean fewer sendClientCommand calls. Hard ceiling 28 (~918 KB/packet) keeps a
-- maxed packet under vanilla UdpConnection's 1 MB outbound buffer.
local PIECES_PER_PACKET_DEFAULT = 4
local PIECES_PER_PACKET_MIN = 1
local PIECES_PER_PACKET_MAX = 28

-- Wall-clock cap on how fast base64 is streamed to the server, in KiB/s of wire bytes. This is
-- the disconnect fix: the client's outbound upload must stay well under the player's uplink so
-- RakNet's ACK/keepalive traffic keeps flowing. If the upload outruns the uplink, the reliable
-- send backlog grows past RakNet's ~10s connection timeout and the player is dropped with
-- "Connection Lost" -- and that backlog scales with screenshot resolution, which is why large
-- monitors broke. 128 KiB/s (~1 Mbit) is safe on virtually any home uplink; admins on good
-- networks can raise it for faster uploads.
local UPLOAD_KB_PER_SEC_DEFAULT = 128
local UPLOAD_KB_PER_SEC_MIN = 8
local UPLOAD_KB_PER_SEC_MAX = 4096

-- Ceiling on how many source KiB are base64-encoded per client tick. This is the hitch fix:
-- base64 runs on the single Lua main thread, so a whole 24 KiB piece per tick was a visible
-- per-frame stall for the entire upload. Encoding is also demand-driven (it stops once enough
-- packets are buffered ahead of the throttled sender), so with a small budget most ticks do
-- little or no work.
local ENCODE_KB_PER_TICK_DEFAULT = 4
local ENCODE_KB_PER_TICK_MIN = 1
local ENCODE_KB_PER_TICK_MAX = 64

local POLL_DELAY_TICKS = 60
local POLL_TIMEOUT_TICKS = 600

local pendingCapture = nil

local function readClampedSandbox(name, default, minValue, maxValue)
    local sandbox = SandboxVars and SandboxVars.Storm
    local value = sandbox and sandbox[name]
    if type(value) ~= "number" then
        return default
    end
    if value < minValue then
        return minValue
    end
    if value > maxValue then
        return maxValue
    end
    return value
end

local function getPiecesPerPacket()
    return readClampedSandbox(
        "ScreenshotPiecesPerPacket",
        PIECES_PER_PACKET_DEFAULT,
        PIECES_PER_PACKET_MIN,
        PIECES_PER_PACKET_MAX
    )
end

local function getUploadKbPerSec()
    return readClampedSandbox(
        "ScreenshotUploadKbPerSec",
        UPLOAD_KB_PER_SEC_DEFAULT,
        UPLOAD_KB_PER_SEC_MIN,
        UPLOAD_KB_PER_SEC_MAX
    )
end

local function getEncodeKbPerTick()
    return readClampedSandbox(
        "ScreenshotEncodeKbPerTick",
        ENCODE_KB_PER_TICK_DEFAULT,
        ENCODE_KB_PER_TICK_MIN,
        ENCODE_KB_PER_TICK_MAX
    )
end

local function sendPacket(screenshotId, index, total, pieces)
    sendClientCommand("stormScreenshot", "chunk", {
        id = screenshotId,
        index = index,
        total = total,
        pieces = pieces,
    })
end

-- Base64-encode up to `budget` source bytes of the pending screenshot, appending completed
-- BYTES_PER_PIECE pieces to pendingCapture.pieces. Encoding is split into <= budget sub-chunks
-- so the per-tick main-thread cost is bounded regardless of piece/packet size. Sub-chunks are
-- kept 3-byte aligned so concatenated base64 never carries mid-stream '=' padding; only the
-- file's final tail may be a partial group.
local function encodeStep(cap, budget)
    local bytes = cap.bytes
    local totalBytes = cap.totalBytes
    while budget > 0 and cap.encodePos <= totalBytes do
        local pos = cap.encodePos
        local pieceRemaining = BYTES_PER_PIECE - cap.pieceByteCount
        local fileRemaining = totalBytes - pos + 1
        local take = budget
        if pieceRemaining < take then
            take = pieceRemaining
        end
        if fileRemaining < take then
            take = fileRemaining
        end
        local endPos = pos + take - 1
        -- Preserve 3-byte alignment unless this chunk reaches the end of the file.
        if endPos < totalBytes then
            local rem = take % 3
            if rem ~= 0 then
                take = take - rem
                if take <= 0 then
                    break
                end
                endPos = pos + take - 1
            end
        end

        cap.pieceParts[#cap.pieceParts + 1] = StormBase64.encode(bytes, pos, endPos)
        cap.encodePos = endPos + 1
        cap.pieceByteCount = cap.pieceByteCount + take
        budget = budget - take

        if cap.pieceByteCount >= BYTES_PER_PIECE or cap.encodePos > totalBytes then
            cap.pieces[#cap.pieces + 1] = table.concat(cap.pieceParts)
            cap.pieceParts = {}
            cap.pieceByteCount = 0
        end
    end
end

-- Send at most one packet, gated by the wall-clock throughput cap. Returns true when the whole
-- upload has finished (final packet sent).
local function sendStep(cap, nowMs)
    local pieces = cap.pieces
    local atEof = cap.encodePos > cap.totalBytes
    local haveFullPacket = #pieces >= cap.piecesPerPacket
    local haveTailPacket = atEof and #pieces > 0
    if not (haveFullPacket or haveTailPacket) then
        return false
    end

    -- Only send if we are behind the allowed byte schedule (average stays under the cap).
    local elapsedMs = nowMs - cap.startMs
    if elapsedMs < 0 then
        elapsedMs = 0
    end
    local allowedBytes = cap.bytesPerMs * elapsedMs
    if cap.wireBytesSent > allowedBytes then
        return false
    end

    local sendCount = cap.piecesPerPacket
    if #pieces < sendCount then
        sendCount = #pieces
    end
    local packetPieces = {}
    local wire = 0
    for i = 1, sendCount do
        packetPieces[i] = pieces[i]
        wire = wire + #pieces[i]
    end
    for _ = 1, sendCount do
        table.remove(pieces, 1)
    end

    cap.packetIndex = cap.packetIndex + 1
    sendPacket(cap.id, cap.packetIndex, cap.totalPackets, packetPieces)
    cap.wireBytesSent = cap.wireBytesSent + wire

    return atEof and #pieces == 0
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
        pendingCapture.piecesPerPacket = getPiecesPerPacket()
        pendingCapture.encodeBudget = getEncodeKbPerTick() * 1024
        pendingCapture.bytesPerMs = getUploadKbPerSec() * 1024 / 1000
        -- Encode no more than two packets ahead of the throttled sender: bounds Lua string
        -- memory and keeps encode CPU demand-driven so idle ticks stay cheap.
        pendingCapture.maxBufferedPieces = pendingCapture.piecesPerPacket * 2

        local bytesPerPacket = BYTES_PER_PIECE * pendingCapture.piecesPerPacket
        pendingCapture.totalPackets =
            math.max(1, math.ceil(pendingCapture.totalBytes / bytesPerPacket))
        pendingCapture.encodePos = 1
        pendingCapture.packetIndex = 0
        pendingCapture.pieces = {}
        pendingCapture.pieceParts = {}
        pendingCapture.pieceByteCount = 0
        pendingCapture.wireBytesSent = 0
        pendingCapture.startMs = getTimestampMs()
        pendingCapture.state = "streaming"
    elseif state == "streaming" then
        local cap = pendingCapture

        -- Encode ahead only while the send buffer has room; a full buffer means the sender is
        -- the bottleneck and this tick should stay cheap.
        if #cap.pieces < cap.maxBufferedPieces then
            encodeStep(cap, cap.encodeBudget)
        end

        if sendStep(cap, getTimestampMs()) then
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
