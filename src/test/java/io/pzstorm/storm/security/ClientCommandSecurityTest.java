package io.pzstorm.storm.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import sun.misc.Unsafe;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;

class ClientCommandSecurityTest implements UnitTest {

    private static final Unsafe UNSAFE = unsafe();

    @Test
    void unrelatedEventsArePassedThrough() throws Exception {
        IsoPlayer player = newPlayer("alice", (short) 1, new Role("User"));
        assertTrue(
                ClientCommandSecurity.isAllowed(
                        "SomeOtherEvent", "vehicle", "remove", player, null));
    }

    @Test
    void nonPlayerSendersArePassedThrough() {
        assertTrue(ClientCommandSecurity.isAllowed("OnClientCommand", "vehicle", "remove", null, null));
    }

    @Test
    void unrelatedCommandsArePassedThrough() throws Exception {
        IsoPlayer player = newPlayer("alice", (short) 1, new Role("User"));
        assertTrue(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand", "vehicle", "startEngine", player, null));
        assertTrue(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand", "player", "onSomethingElse", player, null));
    }

    @Test
    void vehicleRemoveBlockedWithoutCapability() throws Exception {
        IsoPlayer player = newPlayer("alice", (short) 1, new Role("User"));
        KahluaTable args = tableWith("vehicle", 42.0);
        assertFalse(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand", "vehicle", "remove", player, args));
    }

    @Test
    void vehicleRemoveAllowedWithCapability() throws Exception {
        Role role = new Role("Admin");
        role.addCapability(Capability.ManipulateVehicle);
        IsoPlayer player = newPlayer("alice", (short) 1, role);
        assertTrue(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand", "vehicle", "remove", player, tableWith("vehicle", 42.0)));
    }

    @Test
    void vehicleRemoveBlockedWhenRoleIsNull() throws Exception {
        IsoPlayer player = newPlayer("alice", (short) 1, null);
        assertFalse(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand", "vehicle", "remove", player, null));
    }

    @Test
    void healthCheatAllowedWhenTargetingSelf() throws Exception {
        IsoPlayer player = newPlayer("alice", (short) 7, new Role("User"));
        KahluaTable args = tableWith("id", 7.0);
        assertTrue(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand",
                        "player",
                        "onHealthCheatCurrentPlayer",
                        player,
                        args));
    }

    @Test
    void healthCheatBlockedWhenTargetingOther() throws Exception {
        IsoPlayer player = newPlayer("alice", (short) 7, new Role("User"));
        KahluaTable args = tableWith("id", 9.0);
        args.rawset("action", "bleeding");
        assertFalse(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand",
                        "player",
                        "onHealthCheatCurrentPlayer",
                        player,
                        args));
    }

    @Test
    void healthCheatAllowedForOtherWithCapability() throws Exception {
        Role role = new Role("Moderator");
        role.addCapability(Capability.UseHealthCheat);
        IsoPlayer player = newPlayer("alice", (short) 7, role);
        KahluaTable args = tableWith("id", 9.0);
        assertTrue(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand",
                        "player",
                        "onHealthCheatCurrentPlayer",
                        player,
                        args));
    }

    @Test
    void healthCheatBlockedWhenArgsMissing() throws Exception {
        IsoPlayer player = newPlayer("alice", (short) 7, new Role("User"));
        assertFalse(
                ClientCommandSecurity.isAllowed(
                        "OnClientCommand",
                        "player",
                        "onHealthCheatCurrentPlayer",
                        player,
                        null));
    }

    private static IsoPlayer newPlayer(String username, short onlineId, Role role) throws Exception {
        IsoPlayer player = (IsoPlayer) UNSAFE.allocateInstance(IsoPlayer.class);
        setField(IsoPlayer.class, player, "username", username);
        setField(IsoPlayer.class, player, "onlineId", onlineId);
        setField(IsoPlayer.class, player, "role", role);
        return player;
    }

    private static KahluaTable tableWith(String key, Object value) {
        KahluaTable t = new KahluaTableImpl(new HashMap<>());
        t.rawset(key, value);
        return t;
    }

    private static void setField(Class<?> declaringClass, Object target, String name, Object value)
            throws Exception {
        Field f = declaringClass.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Unsafe u = (Unsafe) f.get(null);
            assertNotNull(u);
            return u;
        } catch (Exception e) {
            throw new RuntimeException("Unable to acquire sun.misc.Unsafe", e);
        }
    }
}
