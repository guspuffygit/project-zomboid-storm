-- Injected into the server Lua env by StormEventHandler#onZomboidGlobalsLoad
-- (lua/server/** is gated to server JVMs only). Shipping it in storm.jar
-- instead of media/lua keeps it out of the mod-file checksum, so it reaches
-- servers through the CDN jar self-update with no workshop publish.
--
-- Vanilla ISMilkAnimal:animEvent fires sendServerCommandV("animal", "setMilk",
-- ...) on every milking tick (~800 ms), and the no-target sendServerCommand
-- overload broadcasts it to every connection — but no client handler for that
-- command exists (client ServerCommands.lua defines only
-- Commands.animal.removeDung), so every packet is dead traffic. Keep the
-- server-side milk tick, drop the broadcast; the visible state (bucket fill)
-- already syncs via sendSyncEntity inside milk().

if not ISMilkAnimal then
    return
end

function ISMilkAnimal:animEvent(event, parameter)
    if isServer() and event == "update" then
        self:milk()
    end
end
