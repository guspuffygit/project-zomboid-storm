-- Vanilla Commands.remove (vehicle module) calls vehicle:permanentlyRemove()
-- with no permission check, letting any client delete any vehicle.
local _origVehicleRemove = Commands.remove
Commands.remove = function(player, args)
    if isAdmin(player) or player:getRole():hasCapability(Capability.ManipulateVehicle) then
        return _origVehicleRemove(player, args)
    end
    print(
        string.format(
            "[StormSecurityPatch] BLOCKED vehicle.remove from %s vehicle=%s",
            player:getUsername(),
            tostring(args and args.vehicle)
        )
    )
end

-- Vanilla Commands.player.onHealthCheatCurrentPlayer applies bleeding/wound/etc
-- to any target onlineID with no capability check; the sibling onHealthCheat IS
-- gated. Self-targeting is harmless; cross-targeting requires UseHealthCheat.
local _origHealthCheat = Commands.player.onHealthCheatCurrentPlayer
Commands.player.onHealthCheatCurrentPlayer = function(player, args)
    if
        (args and args.id == player:getOnlineID())
        or checkPermissions(player, Capability.UseHealthCheat)
    then
        return _origHealthCheat(player, args)
    end
    print(
        string.format(
            "[StormSecurityPatch] BLOCKED player.onHealthCheatCurrentPlayer from %s target=%s action=%s",
            player:getUsername(),
            tostring(args and args.id),
            tostring(args and args.action)
        )
    )
end

print("[StormSecurityPatch] vanilla command hardening installed.")
