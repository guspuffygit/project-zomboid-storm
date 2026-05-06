package io.pzstorm.storm.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.network.ClientChunkRequest;

class ChunkSaveCacheTest implements UnitTest {

    private String savedEnabledProperty;

    @BeforeEach
    void captureState() {
        savedEnabledProperty = System.getProperty(ChunkSaveCache.ENABLED_PROPERTY);
        System.clearProperty(ChunkSaveCache.ENABLED_PROPERTY);
        ChunkSaveCache.resetForTest();
    }

    @AfterEach
    void restoreState() {
        if (savedEnabledProperty == null) {
            System.clearProperty(ChunkSaveCache.ENABLED_PROPERTY);
        } else {
            System.setProperty(ChunkSaveCache.ENABLED_PROPERTY, savedEnabledProperty);
        }
        ChunkSaveCache.resetForTest();
    }

    private static ClientChunkRequest.Chunk newCcrc(ByteBuffer bb) {
        ClientChunkRequest.Chunk c = new ClientChunkRequest.Chunk();
        c.bb = bb;
        return c;
    }

    private static ByteBuffer bufferWith(byte[] bytes) {
        // Returns a buffer whose contents are `bytes` and whose position is bytes.length —
        // matching the post-Save state SaveLoadedChunk leaves ccrc.bb in.
        ByteBuffer bb = ByteBuffer.allocate(Math.max(bytes.length, 1));
        bb.put(bytes);
        return bb;
    }

    // -------- enabled() --------

    @Test
    void enabledDefaultsToFalseWhenPropertyUnset() {
        assertFalse(ChunkSaveCache.enabled());
    }

    @Test
    void enabledIsFalseWhenPropertyIsFalse() {
        System.setProperty(ChunkSaveCache.ENABLED_PROPERTY, "false");
        assertFalse(ChunkSaveCache.enabled());
    }

    @Test
    void enabledIsTrueForExplicitTrue() {
        System.setProperty(ChunkSaveCache.ENABLED_PROPERTY, "true");
        assertTrue(ChunkSaveCache.enabled());
    }

    @Test
    void enabledIsTrueCaseInsensitive() {
        System.setProperty(ChunkSaveCache.ENABLED_PROPERTY, "TRUE");
        assertTrue(ChunkSaveCache.enabled());
        System.setProperty(ChunkSaveCache.ENABLED_PROPERTY, "True");
        assertTrue(ChunkSaveCache.enabled());
    }

    @Test
    void enabledIsFalseForUnrelatedValues() {
        // Opt-in only: anything other than "true" leaves the cache disabled.
        System.setProperty(ChunkSaveCache.ENABLED_PROPERTY, "1");
        assertFalse(ChunkSaveCache.enabled());
        System.setProperty(ChunkSaveCache.ENABLED_PROPERTY, "on");
        assertFalse(ChunkSaveCache.enabled());
        System.setProperty(ChunkSaveCache.ENABLED_PROPERTY, "");
        assertFalse(ChunkSaveCache.enabled());
    }

    // -------- populate() --------

    @Test
    void populateReturnsFalseOnCacheMiss() {
        ClientChunkRequest.Chunk ccrc = newCcrc(ByteBuffer.allocate(64));
        assertFalse(ChunkSaveCache.populate(0, 0, ccrc));
    }

    @Test
    void populateReturnsFalseWhenCcrcIsNull() {
        // Pre-populate to ensure miss isn't the early-out — ccrc-null check is.
        ChunkSaveCache.store(1, 2, bufferWith(new byte[] {1, 2, 3}));
        assertFalse(ChunkSaveCache.populate(1, 2, null));
    }

    @Test
    void populateCopiesCachedBytesAndSetsPosition() {
        byte[] payload = new byte[] {10, 20, 30, 40, 50};
        ChunkSaveCache.store(7, 11, bufferWith(payload));

        ByteBuffer target = ByteBuffer.allocate(128);
        ClientChunkRequest.Chunk ccrc = newCcrc(target);

        assertTrue(ChunkSaveCache.populate(7, 11, ccrc));
        assertSame(target, ccrc.bb, "buffer with sufficient capacity must be reused");
        assertEquals(payload.length, ccrc.bb.position());

        byte[] extracted = new byte[payload.length];
        System.arraycopy(ccrc.bb.array(), 0, extracted, 0, payload.length);
        assertArrayEquals(payload, extracted);
    }

    @Test
    void populateAllocatesNewBufferWhenCcrcBbIsNull() {
        byte[] payload = new byte[] {1, 2, 3};
        ChunkSaveCache.store(0, 0, bufferWith(payload));

        ClientChunkRequest.Chunk ccrc = newCcrc(null);
        assertTrue(ChunkSaveCache.populate(0, 0, ccrc));
        assertNotNull(ccrc.bb);
        assertEquals(payload.length, ccrc.bb.position());
        assertTrue(
                ccrc.bb.capacity() >= 16384,
                "fresh allocation should match the pool's minimum buffer size");
    }

    @Test
    void populateGrowsBufferWhenCachedExceedsCapacity() {
        byte[] payload = new byte[20_000];
        Arrays.fill(payload, (byte) 0x5A);
        ChunkSaveCache.store(3, 4, bufferWith(payload));

        ByteBuffer small = ByteBuffer.allocate(1024);
        ClientChunkRequest.Chunk ccrc = newCcrc(small);

        assertTrue(ChunkSaveCache.populate(3, 4, ccrc));
        assertNotSame(small, ccrc.bb, "undersized buffer must be replaced");
        assertTrue(ccrc.bb.capacity() >= payload.length);
        assertEquals(payload.length, ccrc.bb.position());

        byte[] extracted = new byte[payload.length];
        System.arraycopy(ccrc.bb.array(), 0, extracted, 0, payload.length);
        assertArrayEquals(payload, extracted);
    }

    @Test
    void populateOverwritesPriorBufferContents() {
        byte[] payload = new byte[] {7, 7, 7};
        ChunkSaveCache.store(0, 0, bufferWith(payload));

        ByteBuffer target = ByteBuffer.allocate(64);
        // Pre-fill with garbage that should be overwritten or left past the new position.
        for (int i = 0; i < 64; i++) {
            target.put((byte) 0xFF);
        }
        ClientChunkRequest.Chunk ccrc = newCcrc(target);

        assertTrue(ChunkSaveCache.populate(0, 0, ccrc));
        assertEquals(payload.length, ccrc.bb.position());
        for (int i = 0; i < payload.length; i++) {
            assertEquals(
                    payload[i], ccrc.bb.array()[i], "byte " + i + " must match cached payload");
        }
    }

    // -------- store() --------

    @Test
    void storeIgnoresNullBuffer() {
        ChunkSaveCache.store(0, 0, null);
        ClientChunkRequest.Chunk ccrc = newCcrc(ByteBuffer.allocate(64));
        assertFalse(ChunkSaveCache.populate(0, 0, ccrc));
    }

    @Test
    void storeIgnoresEmptyBuffer() {
        ByteBuffer empty = ByteBuffer.allocate(64);
        // position is 0 — nothing was written.
        ChunkSaveCache.store(0, 0, empty);
        ClientChunkRequest.Chunk ccrc = newCcrc(ByteBuffer.allocate(64));
        assertFalse(ChunkSaveCache.populate(0, 0, ccrc));
    }

    @Test
    void storeMakesDefensiveCopy() {
        byte[] original = new byte[] {1, 2, 3, 4};
        ByteBuffer source = bufferWith(original);
        ChunkSaveCache.store(5, 6, source);

        // Mutate the source buffer's backing array after storing.
        source.array()[0] = (byte) 99;
        source.array()[3] = (byte) 99;

        ByteBuffer target = ByteBuffer.allocate(64);
        ClientChunkRequest.Chunk ccrc = newCcrc(target);
        assertTrue(ChunkSaveCache.populate(5, 6, ccrc));
        byte[] extracted = new byte[original.length];
        System.arraycopy(ccrc.bb.array(), 0, extracted, 0, original.length);
        assertArrayEquals(
                original, extracted, "cache must hold a snapshot, not a live reference to source");
    }

    @Test
    void storeOverwritesPriorEntryForSameKey() {
        ChunkSaveCache.store(2, 3, bufferWith(new byte[] {1, 1, 1}));
        ChunkSaveCache.store(2, 3, bufferWith(new byte[] {9, 9}));

        ByteBuffer target = ByteBuffer.allocate(64);
        ClientChunkRequest.Chunk ccrc = newCcrc(target);
        assertTrue(ChunkSaveCache.populate(2, 3, ccrc));
        assertEquals(2, ccrc.bb.position(), "second store must replace the first");
        assertEquals((byte) 9, ccrc.bb.array()[0]);
        assertEquals((byte) 9, ccrc.bb.array()[1]);
    }

    // -------- key independence --------

    @Test
    void differentKeysAreIndependent() {
        ChunkSaveCache.store(1, 2, bufferWith(new byte[] {0x11}));
        ChunkSaveCache.store(2, 1, bufferWith(new byte[] {0x22}));
        ChunkSaveCache.store(0, 0, bufferWith(new byte[] {0x33}));

        ClientChunkRequest.Chunk a = newCcrc(ByteBuffer.allocate(8));
        ClientChunkRequest.Chunk b = newCcrc(ByteBuffer.allocate(8));
        ClientChunkRequest.Chunk c = newCcrc(ByteBuffer.allocate(8));

        assertTrue(ChunkSaveCache.populate(1, 2, a));
        assertTrue(ChunkSaveCache.populate(2, 1, b));
        assertTrue(ChunkSaveCache.populate(0, 0, c));

        assertEquals((byte) 0x11, a.bb.array()[0]);
        assertEquals((byte) 0x22, b.bb.array()[0]);
        assertEquals((byte) 0x33, c.bb.array()[0]);
    }

    @Test
    void negativeCoordinatesProduceDistinctKeys() {
        // The key encoding shifts wx into the upper 32 bits and masks wy into the lower 32 —
        // negative values must still round-trip without colliding.
        ChunkSaveCache.store(-1, 0, bufferWith(new byte[] {(byte) 0xA1}));
        ChunkSaveCache.store(0, -1, bufferWith(new byte[] {(byte) 0xB2}));
        ChunkSaveCache.store(-1, -1, bufferWith(new byte[] {(byte) 0xC3}));

        ClientChunkRequest.Chunk a = newCcrc(ByteBuffer.allocate(8));
        ClientChunkRequest.Chunk b = newCcrc(ByteBuffer.allocate(8));
        ClientChunkRequest.Chunk c = newCcrc(ByteBuffer.allocate(8));

        assertTrue(ChunkSaveCache.populate(-1, 0, a));
        assertTrue(ChunkSaveCache.populate(0, -1, b));
        assertTrue(ChunkSaveCache.populate(-1, -1, c));

        assertEquals((byte) 0xA1, a.bb.array()[0]);
        assertEquals((byte) 0xB2, b.bb.array()[0]);
        assertEquals((byte) 0xC3, c.bb.array()[0]);
    }

    // -------- size() --------

    @Test
    void sizeReflectsDistinctEntries() {
        assertEquals(0, ChunkSaveCache.size());
        ChunkSaveCache.store(1, 1, bufferWith(new byte[] {1}));
        ChunkSaveCache.store(2, 2, bufferWith(new byte[] {2}));
        assertEquals(2, ChunkSaveCache.size());
        // Same key — size unchanged.
        ChunkSaveCache.store(1, 1, bufferWith(new byte[] {3}));
        assertEquals(2, ChunkSaveCache.size());
    }

    // -------- peek() --------

    @Test
    void peekReturnsNullForMissingKey() {
        assertNull(ChunkSaveCache.peek(0, 0));
    }

    @Test
    void peekReturnsCachedBytes() {
        byte[] payload = new byte[] {4, 5, 6};
        ChunkSaveCache.store(8, 9, bufferWith(payload));
        byte[] peeked = ChunkSaveCache.peek(8, 9);
        assertNotNull(peeked);
        assertArrayEquals(payload, peeked);
    }

    @Test
    void peekDoesNotMutateCache() {
        byte[] payload = new byte[] {1, 2, 3};
        ChunkSaveCache.store(0, 0, bufferWith(payload));
        ChunkSaveCache.peek(0, 0);
        assertEquals(1, ChunkSaveCache.size());
        ClientChunkRequest.Chunk ccrc = newCcrc(ByteBuffer.allocate(64));
        assertTrue(ChunkSaveCache.populate(0, 0, ccrc));
    }

    // -------- invalidate() --------

    @Test
    void invalidateRemovesEntry() {
        ChunkSaveCache.store(3, 4, bufferWith(new byte[] {1}));
        assertEquals(1, ChunkSaveCache.size());
        ChunkSaveCache.invalidate(3, 4);
        assertEquals(0, ChunkSaveCache.size());
        assertNull(ChunkSaveCache.peek(3, 4));
    }

    @Test
    void invalidateMissingKeyIsNoOp() {
        ChunkSaveCache.invalidate(99, 99);
        assertEquals(0, ChunkSaveCache.size());
    }

    @Test
    void invalidateOnlyRemovesTargetKey() {
        ChunkSaveCache.store(1, 1, bufferWith(new byte[] {1}));
        ChunkSaveCache.store(2, 2, bufferWith(new byte[] {2}));
        ChunkSaveCache.invalidate(1, 1);
        assertNull(ChunkSaveCache.peek(1, 1));
        assertNotNull(ChunkSaveCache.peek(2, 2));
    }
}
