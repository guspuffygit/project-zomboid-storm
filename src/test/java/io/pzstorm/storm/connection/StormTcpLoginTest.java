package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.RandStandard;
import zombie.network.PacketTypes;
import zombie.network.packets.RequestDataPacket;

class StormTcpLoginTest implements UnitTest {

    private static final byte DETAILS_ID =
            (byte) RequestDataPacket.RequestID.ConnectionDetails.ordinal();

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
        StormTcpLogin.reset();
    }

    @Test
    void encodeWritesIdLengthAndBodyPerFrame() throws Exception {
        byte[] encoded =
                StormTcpLogin.encode(
                        List.of(
                                new StormPacketDivert.Frame(
                                        PacketTypes.PacketType.AccessDenied, new byte[] {1, 2}),
                                new StormPacketDivert.Frame(
                                        PacketTypes.PacketType.MetaGrid, new byte[0])));

        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        assertEquals(PacketTypes.PacketType.AccessDenied.getId(), buffer.getShort());
        assertEquals(2, buffer.getInt());
        assertEquals(1, buffer.get());
        assertEquals(2, buffer.get());
        assertEquals(PacketTypes.PacketType.MetaGrid.getId(), buffer.getShort());
        assertEquals(0, buffer.getInt());
        assertFalse(buffer.hasRemaining());
    }

    @Test
    void partDataConnectionDetailsCollapseToOneFullDataFrame() {
        UdpConnection connection = new UdpConnection(null, 7L, 0);
        byte[] details = {9, 8, 7, 6, 5};
        ByteBuffer previous = RequestDataPacket.largeFileBb;
        RequestDataPacket.largeFileBb = ByteBuffer.allocate(64).put(details);
        try {
            byte[] part1 = {StormTcpLogin.REQUEST_TYPE_PART_DATA, DETAILS_ID, 0, 0, 0, 5};
            byte[] part2 = {StormTcpLogin.REQUEST_TYPE_PART_DATA, DETAILS_ID, 0, 0, 0, 5};
            byte[] spawn = {1, 2, 3};
            List<StormPacketDivert.Frame> out =
                    StormTcpLogin.collapseConnectionDetails(
                            connection,
                            List.of(
                                    new StormPacketDivert.Frame(
                                            PacketTypes.PacketType.RequestData, part1),
                                    new StormPacketDivert.Frame(
                                            PacketTypes.PacketType.RequestData, part2),
                                    new StormPacketDivert.Frame(
                                            PacketTypes.PacketType.SpawnRegion, spawn)));

            assertEquals(2, out.size());
            assertEquals(PacketTypes.PacketType.RequestData, out.get(0).type());
            byte[] expected = new byte[2 + details.length];
            expected[0] = StormTcpLogin.REQUEST_TYPE_FULL_DATA;
            expected[1] = DETAILS_ID;
            System.arraycopy(details, 0, expected, 2, details.length);
            assertArrayEquals(expected, out.get(0).payload());
            assertEquals(PacketTypes.PacketType.SpawnRegion, out.get(1).type());
            assertArrayEquals(spawn, out.get(1).payload());
        } finally {
            RequestDataPacket.largeFileBb = previous;
        }
    }

    @Test
    void fullDataAndOtherRequestDataFramesPassThrough() {
        UdpConnection connection = new UdpConnection(null, 8L, 0);
        byte[] full = {StormTcpLogin.REQUEST_TYPE_FULL_DATA, DETAILS_ID, 4, 4};
        byte[] otherPart = {StormTcpLogin.REQUEST_TYPE_PART_DATA, (byte) (DETAILS_ID + 1), 1};
        List<StormPacketDivert.Frame> out =
                StormTcpLogin.collapseConnectionDetails(
                        connection,
                        List.of(
                                new StormPacketDivert.Frame(
                                        PacketTypes.PacketType.RequestData, full),
                                new StormPacketDivert.Frame(
                                        PacketTypes.PacketType.RequestData, otherPart)));

        assertEquals(2, out.size());
        assertArrayEquals(full, out.get(0).payload());
        assertArrayEquals(otherPart, out.get(1).payload());
    }

    @Test
    void duplicateGuardOnlyFiresForLoggedInConnections() {
        UdpConnection fresh = new UdpConnection(null, 9L, 0);
        assertFalse(StormTcpLogin.isDuplicateLogin(fresh));
        assertFalse(StormTcpLogin.isDuplicateLogin(null));
        assertFalse(StormTcpLogin.isDuplicateLogin("not a connection"));

        UdpConnection loggedIn = new UdpConnection(null, 10L, 0);
        loggedIn.setUserName("bob");
        assertTrue(StormTcpLogin.isDuplicateLogin(loggedIn));
    }
}
