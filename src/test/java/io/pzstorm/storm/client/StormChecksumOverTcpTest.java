package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The live loop reads client statics and the packet class, so tests cover the pure driver: the
 * request → reply → next-request loop, its termination, the round cap and transport failure.
 */
class StormChecksumOverTcpTest implements UnitTest {

    @Test
    void loopEndsWhenNoRequestIsPending() {
        int rounds = StormChecksumOverTcp.drive(() -> null, r -> r, r -> {}, 10);

        assertEquals(0, rounds);
    }

    @Test
    void eachReplyMayQueueTheNextRequest() {
        ArrayDeque<byte[]> requests = new ArrayDeque<>();
        requests.add(new byte[] {1});
        List<byte[]> sent = new ArrayList<>();

        int rounds =
                StormChecksumOverTcp.drive(
                        requests::poll,
                        request -> {
                            sent.add(request);
                            return new byte[] {(byte) (request[0] + 10)};
                        },
                        reply -> {
                            // Replies 11 and 12 queue a follow-up; 13 ends the exchange.
                            if (reply[0] < 13) {
                                requests.add(new byte[] {(byte) (reply[0] - 9)});
                            }
                        },
                        10);

        assertEquals(3, rounds);
        assertEquals(3, sent.size());
        assertEquals(1, sent.get(0)[0]);
        assertEquals(2, sent.get(1)[0]);
        assertEquals(3, sent.get(2)[0]);
        assertTrue(requests.isEmpty());
    }

    @Test
    void nullReplyFailsTheExchange() {
        ArrayDeque<byte[]> requests = new ArrayDeque<>();
        requests.add(new byte[] {1});

        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> StormChecksumOverTcp.drive(requests::poll, r -> null, r -> {}, 10));

        assertTrue(e.getMessage().contains("round 1"));
    }

    @Test
    void roundCapStopsARunawayExchange() {
        int[] applied = new int[1];

        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                StormChecksumOverTcp.drive(
                                        () -> new byte[] {1}, r -> r, r -> applied[0]++, 5));

        assertTrue(e.getMessage().contains("5 rounds"));
        assertEquals(5, applied[0]);
    }

    @Test
    void productionCapCoversALargeModList() {
        // 20 files per group, 10 groups per round: 512 rounds is >100k checksummed files.
        assertTrue(StormChecksumOverTcp.MAX_ROUNDS * 10 * 20 > 100_000);
    }
}
