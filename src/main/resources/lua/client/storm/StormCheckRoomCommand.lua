-- Loaded by StormEventHandler#onZomboidGlobalsLoad through discoverLuaResources
-- (lua/client/** is gated to client JVMs only).
--
-- /checkroom [radius]      paints IsoObjects on any cell whose live walls
--                          disagree with IsoRegions' cached DataChunk flags.
--                          Those leaks are why an enclosed room can fail
--                          isFogMask and cutaway stops hiding walls.
--
-- /clearcheckroom [radius] removes the highlight from every previously-painted
--                          cell.
--
-- Java backend: io.pzstorm.storm.debugging.StormIsoRegionCheck (exposed to the
-- client Lua env by the same handler that loads this file).

if ISChat._stormCheckRoomInstalled then
    return
end
ISChat._stormCheckRoomInstalled = true

local DEFAULT_RADIUS = 32

local function parseRadius(rest)
    if not rest or rest == "" then
        return DEFAULT_RADIUS
    end
    local n = tonumber(rest)
    if n and n > 0 then
        return math.floor(n)
    end
    return DEFAULT_RADIUS
end

local function report(text)
    print("[checkroom] " .. text)
    local player = getPlayer()
    if player and HaloTextHelper then
        HaloTextHelper.addText(player, text, "", HaloTextHelper.getColorWhite())
    end
end

local function runCheck(radius)
    if not StormIsoRegionCheck then
        report("bridge missing — StormIsoRegionCheck was not exposed to the client Lua env")
        return
    end
    report(StormIsoRegionCheck.check(radius))
end

local function runClear(radius)
    if not StormIsoRegionCheck then
        report("bridge missing — StormIsoRegionCheck was not exposed to the client Lua env")
        return
    end
    report(StormIsoRegionCheck.clear(radius))
end

local originalOnCommandEntered = ISChat.onCommandEntered

-- Note: ISChat.onCommandEntered is copied into textEntry.onCommandEntered by
-- ISChat:createChildren (see project-zomboid-base ISChat.lua ~line 195), and
-- UITextBox2 invokes it with the textEntry's own Lua table as `self` — not the
-- ISChat instance. Route everything through ISChat.instance to match vanilla.
function ISChat:onCommandEntered()
    local chat = ISChat.instance
    if not chat or not chat.textEntry then
        return originalOnCommandEntered(self)
    end
    local text = chat.textEntry:getText() or ""
    local head, rest = text:match("^(/%S+)%s*(.-)%s*$")
    if head == "/checkroom" then
        chat:unfocus()
        chat:logChatCommand(text)
        chat.textEntry:setText("")
        runCheck(parseRadius(rest))
        return
    elseif head == "/clearcheckroom" then
        chat:unfocus()
        chat:logChatCommand(text)
        chat.textEntry:setText("")
        runClear(parseRadius(rest))
        return
    end
    return originalOnCommandEntered(self)
end
