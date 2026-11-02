package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PopManZombieTest implements UnitTest {

    private static ByteBuffer vanillaBuffer() {
        return ByteBuffer.allocateDirect(PopManZombie.BUFFER_BYTES);
    }

    /**
     * Vanilla never calls {@code order(...)}, so the whole protocol rides on the JDK default. If
     * that ever stopped being big-endian every record would silently invert.
     */
    @Test
    void vanillaBufferIsBigEndian() {
        assertEquals(ByteOrder.BIG_ENDIAN, vanillaBuffer().order());
    }

    @Test
    void recordCapsMatchTheThousandTwentyFourByteBuffer() {
        assertEquals(31, PopManZombie.MAX_ADD_RECORDS);
        assertTrue(32 * PopManZombie.ADD_RECORD_BYTES > PopManZombie.BUFFER_BYTES);
    }

    @Test
    void addRecordRoundTripsAtEveryPackedIndex() {
        ByteBuffer buf = vanillaBuffer();
        PopManZombie w = new PopManZombie();

        for (int i = 0; i < PopManZombie.MAX_ADD_RECORDS; i++) {
            w.x = 100.5F + i;
            w.y = -200.25F - i;
            w.z = i % 3;
            w.dir = (byte) (i % 8);
            w.descriptorID = 0x0BADF00D + i;
            w.stateFlags = 0x5EED0000 | i;
            w.pathTargetX = 7000 + i;
            w.pathTargetY = -7000 - i;
            w.id = 0x10000 + i;
            w.writeAddRecord(buf, i);
        }

        PopManZombie r = new PopManZombie();
        for (int i = 0; i < PopManZombie.MAX_ADD_RECORDS; i++) {
            r.readAddRecord(buf, i);
            assertEquals(100.5F + i, r.x);
            assertEquals(-200.25F - i, r.y);
            assertEquals((float) (i % 3), r.z);
            assertEquals((byte) (i % 8), r.dir);
            assertEquals(0x0BADF00D + i, r.descriptorID);
            assertEquals(0x5EED0000 | i, r.stateFlags);
            assertEquals(7000 + i, r.pathTargetX);
            assertEquals(-7000 - i, r.pathTargetY);
            assertEquals(0x10000 + i, r.id);
            assertTrue(r.hasPathTarget());
        }
    }

    /**
     * The disk record writes state before the outfit id; the wire record writes them the other way
     * round. Swapping them produces a file that parses cleanly and mis-dresses every zombie, so the
     * two orders are asserted against each other rather than each in isolation.
     */
    @Test
    void diskAndWireRecordsDisagreeOnFieldOrder() {
        ByteBuffer buf = vanillaBuffer();
        PopManZombie w = new PopManZombie();
        w.x = 1.0F;
        w.y = 2.0F;
        w.z = 0.0F;
        w.dir = 3;
        w.stateFlags = 0xAAAA0001;
        w.descriptorID = 0xBBBB0002;

        w.writeDiskRecord(buf, 0);
        assertEquals(0xAAAA0001, buf.getInt(10), "disk holds state at +10");
        assertEquals(0xBBBB0002, buf.getInt(14), "disk holds the outfit id at +14");

        w.writeAddRecord(buf, 0);
        assertEquals(0xBBBB0002, buf.getInt(13), "the wire record holds the outfit id first");
        assertEquals(0xAAAA0001, buf.getInt(17), "and state second");
    }

    @Test
    void diskRecordNarrowsAltitudeToAFlooredByte() {
        ByteBuffer buf = vanillaBuffer();
        PopManZombie w = new PopManZombie();
        w.z = 2.75F;
        w.writeDiskRecord(buf, 0);
        assertEquals((byte) 2, buf.get(8));

        w.z = -1.25F;
        w.writeDiskRecord(buf, 0);
        assertEquals((byte) -2, buf.get(8), "floor, not truncation, below zero");

        PopManZombie r = new PopManZombie();
        r.readDiskRecord(buf, 0, 7);
        assertEquals(-2.0F, r.z);
    }

    @Test
    void diskRecordRoundTripsBackToBack() {
        ByteBuffer buf = vanillaBuffer();
        PopManZombie w = new PopManZombie();
        for (int i = 0; i < 20; i++) {
            w.x = 4000.5F + i;
            w.y = 9000.25F + i;
            w.z = i % 4;
            w.dir = (byte) (i % 8);
            w.stateFlags = 0x1000 + i;
            w.descriptorID = 0x2000 + i;
            w.id = 0x3000 + i;
            w.writeDiskRecord(buf, i * PopManZombie.DISK_RECORD_BYTES);
        }

        PopManZombie r = new PopManZombie();
        for (int i = 0; i < 20; i++) {
            r.readDiskRecord(buf, i * PopManZombie.DISK_RECORD_BYTES, 7);
            assertEquals(4000.5F + i, r.x);
            assertEquals(9000.25F + i, r.y);
            assertEquals((float) (i % 4), r.z);
            assertEquals((byte) (i % 8), r.dir);
            assertEquals(0x1000 + i, r.stateFlags);
            assertEquals(0x2000 + i, r.descriptorID);
            assertEquals(0x3000 + i, r.id);
        }
    }

    @Test
    void loadedIdRaisesTheCounterPastIt() {
        PopManZombieIds.resetForTest(0);
        ByteBuffer buf = vanillaBuffer();
        PopManZombie w = new PopManZombie();
        w.id = 500;
        w.writeDiskRecord(buf, 0);

        new PopManZombie().readDiskRecord(buf, 0, 7);

        assertEquals(501, PopManZombieIds.next());
    }

    @Test
    void preIdRecordDrawsAFreshId() {
        PopManZombieIds.resetForTest(0);
        PopManZombieIds.raiseTo(40);
        ByteBuffer buf = vanillaBuffer();
        PopManZombie w = new PopManZombie();
        w.id = 9999;
        w.writeDiskRecord(buf, 0);

        PopManZombie r = new PopManZombie();
        r.readDiskRecord(buf, 0, 6);

        assertEquals(40, r.id, "a v6 record has no id field, whatever the bytes after it hold");
    }

    @Test
    void zeroIdOnDiskDrawsAFreshId() {
        PopManZombieIds.resetForTest(0);
        PopManZombieIds.raiseTo(12);
        ByteBuffer buf = vanillaBuffer();
        new PopManZombie().writeDiskRecord(buf, 0);

        PopManZombie r = new PopManZombie();
        r.readDiskRecord(buf, 0, 7);

        assertEquals(12, r.id);
    }

    /** Absolute addressing must not depend on the buffer's position, which vanilla never resets. */
    @Test
    void recordsIgnoreBufferPosition() {
        ByteBuffer buf = vanillaBuffer();
        PopManZombie w = new PopManZombie();
        w.x = 5.0F;
        w.descriptorID = 77;
        w.writeAddRecord(buf, 0);

        buf.position(500);
        PopManZombie r = new PopManZombie();
        r.readAddRecord(buf, 0);

        assertEquals(5.0F, r.x);
        assertEquals(77, r.descriptorID);
        assertEquals(500, buf.position(), "absolute access must not move the position");
    }
}
