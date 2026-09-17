package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;
import zombie.network.packets.connection.QueuePacket;

/**
 * The request/poll/done paths need a live client; this covers the message classification the poller
 * shutdown depends on, against the real {@code QueuePacket.MessageType} ordinals.
 */
class StormLoginQueueOverTcpTest implements UnitTest {

    @Test
    void connectionImmediateIsRecognizedByItsEnumOrdinal() {
        byte ordinal = (byte) QueuePacket.MessageType.ConnectionImmediate.ordinal();

        assertTrue(StormLoginQueueOverTcp.isConnectionImmediate(new byte[] {ordinal}));
        assertTrue(StormLoginQueueOverTcp.isConnectionImmediate(new byte[] {ordinal, 1, 2, 3}));
    }

    @Test
    void placeInQueueAndGarbageAreNotAGrant() {
        byte place = (byte) QueuePacket.MessageType.PlaceInQueue.ordinal();

        assertFalse(StormLoginQueueOverTcp.isConnectionImmediate(new byte[] {place, 3}));
        assertFalse(StormLoginQueueOverTcp.isConnectionImmediate(new byte[0]));
        assertFalse(StormLoginQueueOverTcp.isConnectionImmediate(new byte[] {(byte) 0xFF}));
    }
}
