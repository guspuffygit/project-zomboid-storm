-- Storm setHalo: shows a temporary speech bubble over a (possibly remote) player's
-- head when the server sends a ("Storm", "setHalo") command. Uses PZ's native
-- IsoGameCharacter:addLineChatElement -- the same speech-bubble system the game uses
-- for player chat -- so it renders over remote players with no chat-log entry.
--
-- Server side: io.pzstorm.storm.halo.StormHalo (setHalo / setHaloFor).
--
-- Args table: { onlineID = <number>, text = <string>, r/g/b = <0-255, optional> }.
-- Note: PZ does not draw speech bubbles over invisible characters (e.g. god mode),
-- so a bubble over such a player will not appear to other clients.

local MODULE = "Storm"
local COMMAND = "setHalo"

local function onServerCommand(module, command, args)
    if module ~= MODULE or command ~= COMMAND then
        return
    end
    if not args or not args.onlineID or not args.text then
        return
    end

    local target = getPlayerByOnlineID(args.onlineID)
    if not target then
        return
    end

    if args.r and args.g and args.b then
        target:addLineChatElement(args.text, args.r / 255, args.g / 255, args.b / 255)
    else
        target:addLineChatElement(args.text)
    end
end

Events.OnServerCommand.Add(onServerCommand)
