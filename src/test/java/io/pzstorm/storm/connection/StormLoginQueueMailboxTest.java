package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StormLoginQueueMailboxTest implements UnitTest {

    private static final long GUID = 77L;

    @AfterEach
    void tearDown() {
        StormLoginQueueMailbox.reset();
    }

    @Test
    void offerIsDroppedUntilEnabled() throws Exception {
        StormLoginQueueMailbox.offer(GUID, new byte[] {1});

        assertFalse(StormLoginQueueMailbox.isEnabled(GUID));
        assertNull(StormLoginQueueMailbox.poll(GUID, 0));
    }

    @Test
    void messagesComeOutInOrder() throws Exception {
        StormLoginQueueMailbox.enable(GUID);
        StormLoginQueueMailbox.offer(GUID, new byte[] {1});
        StormLoginQueueMailbox.offer(GUID, new byte[] {2});

        assertEquals(2, StormLoginQueueMailbox.pendingCount(GUID));
        assertArrayEquals(new byte[] {1}, StormLoginQueueMailbox.poll(GUID, 0));
        assertArrayEquals(new byte[] {2}, StormLoginQueueMailbox.poll(GUID, 0));
        assertNull(StormLoginQueueMailbox.poll(GUID, 0));
        assertTrue(StormLoginQueueMailbox.isEnabled(GUID), "polling empty keeps the box");
    }

    @Test
    void enableIsIdempotent() {
        StormLoginQueueMailbox.enable(GUID);
        StormLoginQueueMailbox.offer(GUID, new byte[] {1});
        StormLoginQueueMailbox.enable(GUID);

        assertEquals(1, StormLoginQueueMailbox.pendingCount(GUID));
        assertEquals(1, StormLoginQueueMailbox.size());
    }

    @Test
    void pollWaitsForAnOffer() throws Exception {
        StormLoginQueueMailbox.enable(GUID);
        CountDownLatch polling = new CountDownLatch(1);
        byte[][] result = new byte[1][];
        Thread consumer =
                new Thread(
                        () -> {
                            try {
                                polling.countDown();
                                result[0] = StormLoginQueueMailbox.poll(GUID, 5000);
                            } catch (InterruptedException ignored) {
                                // test fails on the null below
                            }
                        });
        consumer.setDaemon(true);
        consumer.start();
        assertTrue(polling.await(2, TimeUnit.SECONDS));
        StormLoginQueueMailbox.offer(GUID, new byte[] {3});
        consumer.join(2000);

        assertFalse(consumer.isAlive());
        assertArrayEquals(new byte[] {3}, result[0]);
    }

    @Test
    void pollTimesOutEmpty() throws Exception {
        StormLoginQueueMailbox.enable(GUID);
        long started = System.nanoTime();

        assertNull(StormLoginQueueMailbox.poll(GUID, 50));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(40));
    }

    @Test
    void disableWakesWaitersAndReturnsLeftovers() throws Exception {
        StormLoginQueueMailbox.enable(GUID);
        StormLoginQueueMailbox.offer(GUID, new byte[] {1});
        StormLoginQueueMailbox.offer(GUID, new byte[] {2});
        CountDownLatch polling = new CountDownLatch(1);
        Thread waiter =
                new Thread(
                        () -> {
                            try {
                                StormLoginQueueMailbox.poll(GUID, 0);
                                StormLoginQueueMailbox.poll(GUID, 0);
                                polling.countDown();
                                StormLoginQueueMailbox.poll(GUID, 5000);
                            } catch (InterruptedException ignored) {
                                // asserted via isAlive below
                            }
                        });
        waiter.setDaemon(true);
        waiter.start();
        assertTrue(polling.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);

        List<byte[]> leftover = StormLoginQueueMailbox.disable(GUID);
        waiter.join(2000);

        assertFalse(waiter.isAlive(), "disable must wake a blocked poll");
        assertTrue(leftover.isEmpty());
        assertFalse(StormLoginQueueMailbox.isEnabled(GUID));
    }

    @Test
    void disableReturnsPendingMessagesForReplay() {
        StormLoginQueueMailbox.enable(GUID);
        StormLoginQueueMailbox.offer(GUID, new byte[] {1});
        StormLoginQueueMailbox.offer(GUID, new byte[] {2});

        List<byte[]> leftover = StormLoginQueueMailbox.disable(GUID);

        assertEquals(2, leftover.size());
        assertArrayEquals(new byte[] {1}, leftover.get(0));
        assertArrayEquals(new byte[] {2}, leftover.get(1));
        assertEquals(0, StormLoginQueueMailbox.pendingCount(GUID));
        assertTrue(StormLoginQueueMailbox.disable(GUID).isEmpty(), "second disable is a no-op");
    }

    @Test
    void offerAfterDisableIsDropped() throws Exception {
        StormLoginQueueMailbox.enable(GUID);
        StormLoginQueueMailbox.disable(GUID);
        StormLoginQueueMailbox.offer(GUID, new byte[] {1});

        assertNull(StormLoginQueueMailbox.poll(GUID, 0));
        assertEquals(0, StormLoginQueueMailbox.size());
    }

    @Test
    void capDropsTheOldestMessage() throws Exception {
        StormLoginQueueMailbox.enable(GUID);
        for (int i = 0; i <= StormLoginQueueMailbox.MAX_PENDING; i++) {
            StormLoginQueueMailbox.offer(GUID, new byte[] {(byte) i});
        }

        assertEquals(StormLoginQueueMailbox.MAX_PENDING, StormLoginQueueMailbox.pendingCount(GUID));
        assertArrayEquals(new byte[] {1}, StormLoginQueueMailbox.poll(GUID, 0));
    }

    @Test
    void boxesAreIndependentPerConnection() throws Exception {
        StormLoginQueueMailbox.enable(GUID);
        StormLoginQueueMailbox.enable(GUID + 1);
        StormLoginQueueMailbox.offer(GUID, new byte[] {1});

        assertNull(StormLoginQueueMailbox.poll(GUID + 1, 0));
        assertArrayEquals(new byte[] {1}, StormLoginQueueMailbox.poll(GUID, 0));
        StormLoginQueueMailbox.disable(GUID);
        assertTrue(StormLoginQueueMailbox.isEnabled(GUID + 1));
    }
}
