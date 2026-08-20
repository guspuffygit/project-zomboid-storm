package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.lua.OnServerStartedEvent;
import io.pzstorm.storm.event.zomboid.OnPacketReceivedEvent;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;
import zombie.characters.Role;
import zombie.characters.Roles;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/**
 * Drives {@link RolePositionPin} through the real {@link StormEventDispatcher} registration and
 * dispatch paths: class-registered static handlers, the {@code OnServerStarted} Lua event and the
 * packet event bus keyed on the packet's simple class name.
 */
class RolePositionPinIntegrationTest implements IntegrationTest {

    /** Only the simple name matters to the packet event bus. */
    static class RolesEditPacket {}

    private List<Role> savedRoles;
    private boolean savedServerFlag;

    @BeforeAll
    static void registerHandler() {
        StormEventDispatcher.registerEventHandler(RolePositionPin.class);
    }

    @BeforeEach
    void seedRoles() {
        savedRoles = new ArrayList<>(Roles.getRoles());
        savedServerFlag = GameServer.server;
        GameServer.server = true;
        Roles.getRoles().clear();
        Roles.addStatic();
    }

    @AfterEach
    void restore() {
        GameServer.server = savedServerFlag;
        Roles.getRoles().clear();
        Roles.getRoles().addAll(savedRoles);
    }

    private static Role custom(String name, int position) {
        Role r = new Role(name);
        r.setPosition(position);
        Roles.getRoles().add(r);
        return r;
    }

    private static OnPacketReceivedEvent rolesEditEvent() throws Exception {
        // UdpConnection's constructor drags in PacketsCache → PacketTypes → AntiCheat clinit;
        // allocate without running it, the event only reads username/steamId off it.
        Constructor<?> ctor =
                ReflectionFactory.getReflectionFactory()
                        .newConstructorForSerialization(
                                UdpConnection.class, Object.class.getDeclaredConstructor());
        UdpConnection connection = (UdpConnection) ctor.newInstance();
        return new OnPacketReceivedEvent(new RolesEditPacket(), connection);
    }

    @Test
    void onServerStartedPinsDbLoadedPositions() {
        int userPos = Roles.getRole("user").getPosition();
        Role a = custom("veteran", 0);
        Role b = custom("builder", 1);

        StormEventDispatcher.dispatchEvent(new OnServerStartedEvent());

        assertEquals(userPos, a.getPosition());
        assertEquals(userPos, b.getPosition());
    }

    @Test
    void onServerStartedDoesNothingOffServer() {
        GameServer.server = false;
        Role a = custom("veteran", 0);

        StormEventDispatcher.dispatchEvent(new OnServerStartedEvent());

        assertEquals(0, a.getPosition());
    }

    @Test
    void rolesEditPacketRePinsAfterVanillaRenumber() throws Exception {
        int userPos = Roles.getRole("user").getPosition();
        Role a = custom("veteran", 0);
        Role fresh = custom("fresh", -1);

        StormEventDispatcher.dispatchEvent(rolesEditEvent());

        assertEquals(userPos, a.getPosition());
        assertEquals(userPos, fresh.getPosition());
    }

    @Test
    void rolesEditPacketDoesNothingOffServer() throws Exception {
        GameServer.server = false;
        Role a = custom("veteran", 0);

        StormEventDispatcher.dispatchEvent(rolesEditEvent());

        assertEquals(0, a.getPosition());
    }
}
