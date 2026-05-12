package io.pzstorm.storm.security;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;

/**
 * Server-side capability gate for client-issued Lua commands.
 *
 * <p>Replaces the (broken) Lua hardening that used to live in {@code StormSecurityPatch.lua}: in
 * vanilla PZ, {@code Commands} is a file-local in each command module rather than a global, so the
 * Lua wrappers could never actually intercept the command dispatch. Vanilla also gates {@code
 * vehicle.remove} behind {@code NetworkPlayerAI.isDismantleAllowed()} which is hard-coded to return
 * {@code true} — effectively no gate. There is no vanilla gate on {@code
 * player.onHealthCheatCurrentPlayer}.
 *
 * <p>{@link #gatedTriggerOnClientCommand} is substituted into the only {@code
 * LuaEventManager.triggerEvent("OnClientCommand", ...)} call site, in {@code
 * GameServer.receiveClientCommand}, by {@code GameServerReceiveClientCommandPatch}.
 */
public final class ClientCommandSecurity {

    private ClientCommandSecurity() {}

    /**
     * Substitution target for the {@code LuaEventManager.triggerEvent} call inside {@code
     * GameServer.receiveClientCommand}. Signature must match the 5-arg overload {@code (String,
     * Object, Object, Object, Object)} exactly. Delegates to the original event dispatch only when
     * {@link #isAllowed} permits.
     */
    public static void gatedTriggerOnClientCommand(
            String event, Object module, Object command, Object player, Object args) {
        if (!isAllowed(event, module, command, player, args)) {
            return;
        }
        LuaEventManager.triggerEvent(event, module, command, player, args);
    }

    /**
     * Returns {@code true} iff the given {@code OnClientCommand} dispatch should be forwarded to
     * Lua handlers. Non-{@code OnClientCommand} events and non-player senders pass through
     * untouched. Visible for testing.
     */
    public static boolean isAllowed(
            String event, Object module, Object command, Object player, Object args) {
        if (!"OnClientCommand".equals(event) || !(player instanceof IsoPlayer p)) {
            return true;
        }
        if ("vehicle".equals(module) && "remove".equals(command)) {
            return checkVehicleRemove(p, args);
        }
        if ("player".equals(module) && "onHealthCheatCurrentPlayer".equals(command)) {
            return checkHealthCheatCurrentPlayer(p, args);
        }
        return true;
    }

    private static boolean checkVehicleRemove(IsoPlayer player, Object args) {
        if (hasCapability(player, Capability.ManipulateVehicle)) {
            return true;
        }
        Object vehicleId = (args instanceof KahluaTable t) ? t.rawget("vehicle") : null;
        LOGGER.warn(
                "[StormSecurityPatch] BLOCKED vehicle.remove from {} vehicle={}",
                player.getUsername(),
                vehicleId);
        return false;
    }

    private static boolean checkHealthCheatCurrentPlayer(IsoPlayer player, Object args) {
        if (args instanceof KahluaTable t) {
            Object idObj = t.rawget("id");
            if (idObj instanceof Number n && n.intValue() == player.getOnlineID()) {
                return true;
            }
        }
        if (hasCapability(player, Capability.UseHealthCheat)) {
            return true;
        }
        Object targetId = (args instanceof KahluaTable t) ? t.rawget("id") : null;
        Object action = (args instanceof KahluaTable t) ? t.rawget("action") : null;
        LOGGER.warn(
                "[StormSecurityPatch] BLOCKED player.onHealthCheatCurrentPlayer from {} target={} action={}",
                player.getUsername(),
                targetId,
                action);
        return false;
    }

    private static boolean hasCapability(IsoPlayer player, Capability capability) {
        Role role = player.getRole();
        return role != null && role.hasCapability(capability);
    }
}
