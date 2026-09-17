package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.RandStandard;
import zombie.network.PacketTypes;

/**
 * Drives {@link StormPacketDivert} against a real {@link UdpConnection} (no engine: {@code
 * startPacket}/{@code cancelPacket} only touch the buffer and its lock). The private {@code bb}
 * field and the 3-byte header layout are exactly what a game update could change, so this doubles
 * as the update tripwire.
 */
class StormPacketDivertTest implements UnitTest {

    private static final long GUID = 4242L;

    /**
     * {@code UdpConnection} class-initializes {@code PacketType}, whose anti-cheat settings draw
     * from the game RNG.
     */
    @BeforeAll
    static void seedGameRng() {
        RandStandard.INSTANCE.init();
    }

    @AfterEach
    void tearDown() {
        StormLoginQueueMailbox.reset();
        StormPacketDivert.Scope leaked = StormPacketDivert.activeScope();
        if (leaked != null) {
            leaked.close();
        }
    }

    @Test
    void scopeCapturesPayloadWithoutHeaderAndReleasesTheBuffer() throws Exception {
        UdpConnection connection = new UdpConnection(null, GUID, 0);
        try (StormPacketDivert.Scope scope =
                StormPacketDivert.open(connection, PacketTypes.PacketType.Checksum)) {
            writePacket(connection, PacketTypes.PacketType.Checksum, 0xCAFEBABE);

            assertTrue(StormPacketDivert.tryDivert(PacketTypes.PacketType.Checksum, connection));
            byte[] payload = scope.takeOne();
            assertArrayEquals(ByteBuffer.allocate(4).putInt(0xCAFEBABE).array(), payload);
            assertNull(scope.takeOne());
        }
        assertBufferLockReleased(connection);
    }

    @Test
    void scopeIgnoresOtherTypesAndOtherConnections() {
        UdpConnection connection = new UdpConnection(null, GUID, 0);
        UdpConnection other = new UdpConnection(null, GUID + 1, 1);
        try (StormPacketDivert.Scope scope =
                StormPacketDivert.open(connection, PacketTypes.PacketType.Checksum)) {
            writePacket(connection, PacketTypes.PacketType.LoginQueueDone, 1);
            assertFalse(
                    StormPacketDivert.tryDivert(PacketTypes.PacketType.LoginQueueDone, connection));
            connection.cancelPacket();

            writePacket(other, PacketTypes.PacketType.Checksum, 2);
            assertFalse(StormPacketDivert.tryDivert(PacketTypes.PacketType.Checksum, other));
            other.cancelPacket();

            assertEquals(0, scope.pending());
        }
    }

    @Test
    void headerMismatchFallsThroughToVanillaSend() {
        UdpConnection connection = new UdpConnection(null, GUID, 0);
        try (StormPacketDivert.Scope scope =
                StormPacketDivert.open(connection, PacketTypes.PacketType.Checksum)) {
            // Header says LoginQueueDone, send() is asked for Checksum: never trust the copy.
            writePacket(connection, PacketTypes.PacketType.LoginQueueDone, 7);
            assertFalse(StormPacketDivert.tryDivert(PacketTypes.PacketType.Checksum, connection));
            connection.cancelPacket();

            assertEquals(0, scope.pending());
        }
    }

    @Test
    void emptyBufferFallsThroughToVanillaSend() {
        UdpConnection connection = new UdpConnection(null, GUID, 0);
        try (StormPacketDivert.Scope ignored =
                StormPacketDivert.open(connection, PacketTypes.PacketType.Checksum)) {
            connection.startPacket();
            assertFalse(StormPacketDivert.tryDivert(PacketTypes.PacketType.Checksum, connection));
            connection.cancelPacket();
        }
    }

    @Test
    void nonPacketArgumentsFallThrough() {
        assertFalse(StormPacketDivert.tryDivert("Checksum", new Object()));
        assertFalse(StormPacketDivert.tryDivert(null, null));
    }

    @Test
    void scopesNestAndRestoreTheOuterOnClose() {
        UdpConnection connection = new UdpConnection(null, GUID, 0);
        StormPacketDivert.Scope outer =
                StormPacketDivert.open(connection, PacketTypes.PacketType.Checksum);
        try (StormPacketDivert.Scope inner =
                StormPacketDivert.open(connection, PacketTypes.PacketType.LoginQueueDone)) {
            writePacket(connection, PacketTypes.PacketType.Checksum, 1);
            // Inner scope does not cover Checksum; the outer one is shadowed, so vanilla sends.
            assertFalse(StormPacketDivert.tryDivert(PacketTypes.PacketType.Checksum, connection));
            connection.cancelPacket();
            assertEquals(inner, StormPacketDivert.activeScope());
        }
        assertEquals(outer, StormPacketDivert.activeScope());
        writePacket(connection, PacketTypes.PacketType.Checksum, 1);
        assertTrue(StormPacketDivert.tryDivert(PacketTypes.PacketType.Checksum, connection));
        assertEquals(1, outer.pending());
        outer.close();
        assertNull(StormPacketDivert.activeScope());
    }

    @Test
    void loginQueueRequestGoesToTheMailboxWhenEnabled() throws Exception {
        UdpConnection connection = new UdpConnection(null, GUID, 0);
        StormLoginQueueMailbox.enable(GUID);
        writePacket(connection, PacketTypes.PacketType.LoginQueueRequest, 9);

        assertTrue(
                StormPacketDivert.tryDivert(PacketTypes.PacketType.LoginQueueRequest, connection));
        assertEquals(1, StormLoginQueueMailbox.pendingCount(GUID));
        assertArrayEquals(
                ByteBuffer.allocate(4).putInt(9).array(), StormLoginQueueMailbox.poll(GUID, 0));
        assertBufferLockReleased(connection);
    }

    @Test
    void loginQueueRequestIsSentNormallyWhenMailboxIsDisabled() {
        UdpConnection connection = new UdpConnection(null, GUID, 0);
        writePacket(connection, PacketTypes.PacketType.LoginQueueRequest, 9);

        assertFalse(
                StormPacketDivert.tryDivert(PacketTypes.PacketType.LoginQueueRequest, connection));
        connection.cancelPacket();
        assertEquals(0, StormLoginQueueMailbox.pendingCount(GUID));
    }

    @Test
    void sendOverUdpRebuildsTheHeaderAndBody() {
        UdpConnection connection = new UdpConnection(null, GUID, 0);
        byte[] body = {1, 2, 3};
        // Capture the rebuilt packet with a scope: proves header + body round-trip byte for byte.
        try (StormPacketDivert.Scope scope =
                StormPacketDivert.open(connection, PacketTypes.PacketType.LoginQueueRequest)) {
            StormPacketDivert.sendOverUdp(
                    connection, PacketTypes.PacketType.LoginQueueRequest, body);
            assertArrayEquals(body, scope.takeOne());
        }
    }

    private static void writePacket(UdpConnection connection, PacketTypes.PacketType type, int v) {
        ByteBufferWriter writer = connection.startPacket();
        type.doPacket(writer);
        writer.putInt(v);
    }

    /**
     * {@code bufferLock} is a ReentrantLock; a second thread can only take it if divert unlocked.
     */
    private static void assertBufferLockReleased(UdpConnection connection) throws Exception {
        Thread probe =
                new Thread(
                        () -> {
                            connection.startPacket();
                            connection.cancelPacket();
                        });
        probe.setDaemon(true);
        probe.start();
        probe.join(2000);
        assertFalse(probe.isAlive(), "divert must release the packet buffer lock");
    }
}
