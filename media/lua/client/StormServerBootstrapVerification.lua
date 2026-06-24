local MODULE = "stormBootstrap"
local PING = "ping"
local PONG = "pong"

-- 600 ticks ~= 10 seconds at 60 FPS. Generous for slow links / loaded servers.
local TIMEOUT_TICKS = 600

local pendingCheck = nil

local function showMisconfiguredModal()
    local core = getCore()

    local descriptionLines = {
        "STORM IS NOT CONFIGURED ON THIS SERVER",
        "The Storm mod is installed but the server admin has not enabled the bootstrap agent. Ask them to add the following JVM flags to start-server.sh (Linux) or StartServer64.bat (Windows):",
        "    -javaagent:./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar",
        "    -Dstorm.server=true",
        "You will be disconnected.",
    }

    local description = table.concat(descriptionLines, "\n\n")

    local width, height = ISModalDialog.CalcSize(0, 0, description)
    height = height + 40
    local x = (core:getScreenWidth() / 2) - (width / 2)
    local y = (core:getScreenHeight() / 2) - (height / 2)

    local modal = ISModalDialog:new(x, y, width, height, description, false, nil, function()
        forceDisconnect()
    end, nil)
    modal:initialise()
    modal:addToUIManager()

    if JoypadState.players[1] then
        setJoypadFocus(0, modal)
    end
end

local function tickCheck()
    if not pendingCheck then
        return
    end

    pendingCheck.ticks = pendingCheck.ticks + 1
    if pendingCheck.ticks < TIMEOUT_TICKS then
        return
    end

    pendingCheck = nil
    Events.OnTick.Remove(tickCheck)
    showMisconfiguredModal()
end

local function onConnected()
    if pendingCheck then
        return
    end
    pendingCheck = { ticks = 0 }
    Events.OnTick.Add(tickCheck)
    sendClientCommand(MODULE, PING, {})
end

local function onServerCommand(module, command, _args)
    if module ~= MODULE or command ~= PONG then
        return
    end
    if not pendingCheck then
        return
    end
    pendingCheck = nil
    Events.OnTick.Remove(tickCheck)
end

-- Events.OnConnected.Add(onConnected)
-- Events.OnServerCommand.Add(onServerCommand)
