require "StormBase64"

local pendingScreenshots = {}

local function onScreenshotChunk(player, args)
    local id = args.id
    local index = args.index
    local total = args.total
    local data = args.data

    if not id or not index or not total or not data then
        print("[Storm] Invalid screenshot chunk received")
        return
    end

    local playerName = player:getUsername()
    local key = playerName .. "_" .. id

    if not pendingScreenshots[key] then
        pendingScreenshots[key] = {
            chunks = {},
            total = total,
            received = 0,
            player = playerName,
            id = id,
        }
    end

    local pending = pendingScreenshots[key]

    if total ~= pending.total then
        print("[Storm] Chunk total mismatch for " .. key)
        return
    end

    local idx = tonumber(index)
    if not pending.chunks[idx] then
        pending.chunks[idx] = data
        pending.received = pending.received + 1
    end

    print("[Storm] Screenshot chunk " .. idx .. "/" .. pending.total .. " from " .. playerName)

    if pending.received >= pending.total then
        local parts = {}
        for i = 1, pending.total do
            parts[i] = pending.chunks[i]
        end
        local base64str = table.concat(parts)

        print("[Storm] Screenshot complete from " .. playerName .. ": " .. string.len(base64str) .. " Base64 chars")

        local bytes = StormBase64.decode(base64str)
        local filename = "storm_screenshot_" .. playerName .. "_" .. id .. ".png"
        local stream = getFileOutput(filename)
        if stream then
            local WRITE_CHUNK = 500
            for i = 1, #bytes, WRITE_CHUNK do
                local j = math.min(i + WRITE_CHUNK - 1, #bytes)
                stream:writeBytes(string.char(unpack(bytes, i, j)))
            end
            stream:close()
            print("[Storm] Saved screenshot: " .. filename)
        end

        pendingScreenshots[key] = nil
    end
end

local function onClientCommand(module, command, player, args)
    if module == "stormScreenshot" and command == "chunk" then
        onScreenshotChunk(player, args)
    end
end

Events.OnClientCommand.Add(onClientCommand)
