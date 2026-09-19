package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.client.StormChunksOverTcp.Staged;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fallback half of chunk streaming over TCP. A diverted request never reached the server (its
 * UDP packet went out with count 0) and vanilla has no per-request resend, so every request the TCP
 * path fails to answer has to come back out over UDP or the join dies on the 60 s no-progress
 * abort.
 */
class StormChunksOverTcpTest implements UnitTest {

    private static final StormTcpChannel.Session SESSION =
            new StormTcpChannel.Session("http://127.0.0.1:1", "token", "test");

    @BeforeEach
    void reset() {
        StormChunksOverTcp.reset();
    }

    private static List<Staged> batch(int from, int count) {
        List<Staged> out = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            out.add(new Staged(i, 100 + i, 200 + i, 1000L + i, 0));
        }
        return out;
    }

    private static List<Integer> numbers(List<Staged> requests) {
        List<Integer> out = new ArrayList<>();
        for (Staged request : requests) {
            out.add(request.requestNumber());
        }
        return out;
    }

    @Test
    void transportFailureHandsEveryUnansweredRequestToUdp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // 45 requests = sub-batches of 20, 20, 5. The first is answered, the second throws.
        StormChunksOverTcp.process(
                batch(0, 45),
                SESSION,
                (sub, retries, outstanding) -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new IllegalStateException("chunk batch returned HTTP 404");
                    }
                    for (Staged request : sub) {
                        outstanding.remove(request.requestNumber());
                    }
                });

        assertEquals(2, calls.get(), "no TCP attempt after the failure");
        assertEquals(numbers(batch(20, 25)), numbers(StormChunksOverTcp.drainResendQueue()));
    }

    @Test
    void batchReachedAfterTheBreakerTrippedGoesStraightToUdp() throws Exception {
        StormChunksOverTcp.process(
                batch(0, 3),
                SESSION,
                (sub, retries, outstanding) -> {
                    throw new IllegalStateException("timeout");
                });
        StormChunksOverTcp.drainResendQueue();

        // Dispatched before the failure, reached by the worker after it. Counted rather than
        // failed from inside the fetcher: process() would swallow the assertion as a transport
        // error and resend the batch anyway.
        AtomicInteger fetches = new AtomicInteger();
        StormChunksOverTcp.process(
                batch(3, 4), SESSION, (sub, retries, outstanding) -> fetches.incrementAndGet());

        assertEquals(0, fetches.get(), "a broken session must not be fetched from");
        assertEquals(numbers(batch(3, 4)), numbers(StormChunksOverTcp.drainResendQueue()));
    }

    @Test
    void requestWaitingOnARetryVerdictIsStillOwedWhenTheTransportFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StormChunksOverTcp.process(
                batch(0, 2),
                SESSION,
                (sub, retries, outstanding) -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new IllegalStateException("timeout");
                    }
                    outstanding.remove(0);
                    retries.add(new Staged(1, 101, 201, 1001L, 1));
                });

        assertEquals(List.of(1), numbers(StormChunksOverTcp.drainResendQueue()));
    }

    @Test
    void fullyAnsweredBatchLeavesNothingToResend() throws Exception {
        StormChunksOverTcp.process(
                batch(0, 25),
                SESSION,
                (sub, retries, outstanding) -> {
                    for (Staged request : sub) {
                        outstanding.remove(request.requestNumber());
                    }
                });

        assertTrue(StormChunksOverTcp.drainResendQueue().isEmpty());
    }

    /**
     * Must stay byte-compatible with vanilla {@code RequestZipListPacket.write} / {@code parse}.
     */
    @Test
    void requestListUsesTheVanillaWireFormat() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        StormChunksOverTcp.writeRequestList(buffer, batch(7, 2));
        buffer.flip();

        assertEquals(4 + 2 * 20, buffer.remaining());
        assertEquals(2, buffer.getInt());
        for (int i = 7; i < 9; i++) {
            assertEquals(i, buffer.getInt());
            assertEquals(100 + i, buffer.getInt());
            assertEquals(200 + i, buffer.getInt());
            assertEquals(1000L + i, buffer.getLong());
        }
        assertFalse(buffer.hasRemaining());
    }
}
