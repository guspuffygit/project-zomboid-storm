local MODULE = "stormBootstrap"
local PING = "ping"
local PONG = "pong"

local function onClientCommand(module, command, player, _args)
    if module ~= MODULE or command ~= PING then
        return
    end
    if not Storm then
        return
    end
    sendServerCommand(player, MODULE, PONG, {})
end

Events.OnClientCommand.Add(onClientCommand)
