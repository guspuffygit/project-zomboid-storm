package io.pzstorm.storm.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoZombie;
import zombie.config.BooleanConfigOption;
import zombie.iso.CorpseCount;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;

/** Real native objects/getters, compared against a collect-then-filter oracle. */
class StormCorpseZombieCountTest implements UnitTest {
    private static Unsafe unsafe;
    private static StormChunkIndex index;
    private static int originalMax;
    private static Field squareMovingObjects;

    @BeforeAll
    static void prepare() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = (Unsafe) field.get(null);
        field = StormSpatialIndex.class.getDeclaredField("INDEX");
        field.setAccessible(true);
        index = (StormChunkIndex) field.get(null);
        squareMovingObjects = IsoGridSquare.class.getDeclaredField("movingObjects");
        squareMovingObjects.setAccessible(true);
        originalMax = CorpseCount.maxCorpseCount;
    }

    @BeforeEach
    @AfterEach
    void reset() {
        StormSpatialIndex.resetForTest();
        index.beginTick(MovingObjectUpdateScheduler.instance.getFrameCounter());
        StormCorpseZombieCount.resetForTest();
        CorpseCount.maxCorpseCount = originalMax;
    }

    private static void addZombie(
            float x,
            float y,
            int squareX,
            int squareY,
            int z,
            IsoBuilding building,
            boolean hasSquare)
            throws Exception {
        IsoZombie zombie = (IsoZombie) unsafe.allocateInstance(IsoZombie.class);
        if (hasSquare) {
            IsoGridSquare square = (IsoGridSquare) unsafe.allocateInstance(IsoGridSquare.class);
            square.x = squareX;
            square.y = squareY;
            square.z = z;
            // Unsafe skips the field initializer. Native setMovingSquare maintains this list,
            // so supply the same empty backing list its real constructor would initialize.
            squareMovingObjects.set(square, new ArrayList<IsoMovingObject>());
            if (building != null) {
                IsoRoom room = (IsoRoom) unsafe.allocateInstance(IsoRoom.class);
                room.building = building;
                square.setRoom(room);
            }
            zombie.setMovingSquare(square);
            assertEquals(1, square.getZombieCount());
        }
        index.add(zombie, x, y, StormChunkIndex.TYPE_ZOMBIE);
    }

    private static int collectAndFilter(int count, int wx, int wy, int z, IsoBuilding building) {
        int x = wx * 8;
        int y = wy * 8;
        StormObjectList candidates = new StormObjectList(64);
        index.collectChunkRect(
                StormChunkIndex.chunkOf(x - 12) - 1,
                StormChunkIndex.chunkOf(y - 12) - 1,
                StormChunkIndex.chunkOf(x + 12) + 1,
                StormChunkIndex.chunkOf(y + 12) + 1,
                StormChunkIndex.MASK_ZOMBIE,
                candidates);
        for (int i = 0; i < candidates.size() && count < CorpseCount.maxCorpseCount; i++) {
            IsoGridSquare square = ((IsoMovingObject) candidates.get(i)).getMovingSquare();
            if (square != null
                    && square.getZ() == z
                    && square.getX() - x >= -12
                    && square.getX() - x <= 12
                    && square.getY() - y >= -12
                    && square.getY() - y <= 12
                    && square.getBuilding() == building) count++;
        }
        return count;
    }

    private static int serve(int count, int wx, int wy, int z, IsoBuilding building) {
        assertFalse(
                StormCorpseZombieCount.readZombieHealthImpact(
                        new BooleanConfigOption("impact", true)));
        return StormCorpseZombieCount.augment(count, wx, wy, z, building);
    }

    @Test
    void denseAndRandomWorldsMatchPreviousTraversalWithNativeSquareChecks() throws Exception {
        Random random = new Random(20261006);
        IsoBuilding first = (IsoBuilding) unsafe.allocateInstance(IsoBuilding.class);
        IsoBuilding second = (IsoBuilding) unsafe.allocateInstance(IsoBuilding.class);
        IsoBuilding[] buildings = {null, first, second};
        for (int world = 0; world < 8; world++) {
            index.beginTick(MovingObjectUpdateScheduler.instance.getFrameCounter());
            int range = world % 2 == 0 ? 25 : 200;
            for (int i = 0; i < 2_000; i++) {
                int x = random.nextInt(range) - range / 2;
                int y = random.nextInt(range) - range / 2;
                // Snapshot-to-live-square drift is retained; the live square decides membership.
                addZombie(
                        x + random.nextFloat(),
                        y + random.nextFloat(),
                        x + random.nextInt(3) - 1,
                        y + random.nextInt(3) - 1,
                        random.nextInt(3) - 1,
                        buildings[random.nextInt(3)],
                        random.nextInt(8) != 0);
                if (i % 11 == 0) index.add(new Object(), x, y, StormChunkIndex.TYPE_OTHER);
            }
            index.endTick();
            for (int query = 0; query < 80; query++) {
                int wx = query % 2 == 0 ? 0 : random.nextInt(30) - 15;
                int wy = query % 2 == 0 ? 0 : random.nextInt(30) - 15;
                int z = random.nextInt(3) - 1;
                int count = random.nextInt(31);
                CorpseCount.maxCorpseCount = 1 + random.nextInt(30);
                IsoBuilding building = buildings[random.nextInt(3)];
                assertEquals(
                        collectAndFilter(count, wx, wy, z, building),
                        serve(count, wx, wy, z, building));
                // The per-call handshake is consumed exactly once.
                assertEquals(count, StormCorpseZombieCount.augment(count, wx, wy, z, building));
            }
        }
    }

    @Test
    void cappedTraversalDoesNotDereferenceLaterCandidates() throws Exception {
        for (int i = 0; i < 25; i++) addZombie(0, 0, 0, 0, 0, null, true);
        // A non-world sentinel in the same bucket detects a traversal past the cap; the cursor
        // must never inspect its payload.
        index.add(new Object(), 0, 0, StormChunkIndex.TYPE_ZOMBIE);
        index.endTick();
        CorpseCount.maxCorpseCount = 25;
        assertEquals(25, serve(0, 0, 0, 0, null));
        assertFalse(
                StormCorpseZombieCount.readZombieHealthImpact(
                        new BooleanConfigOption("healthy", true)),
                "reading past the cap would trip the failure latch");
        assertEquals(25, StormCorpseZombieCount.augment(25, 0, 0, 0, null));
    }

    @Test
    void boundariesCapAndUnavailableIndexKeepExistingBehavior() throws Exception {
        for (int x : new int[] {-13, -12, 12, 13}) addZombie(x, 0, x, 0, 0, null, true);
        addZombie(0, -12, 0, -12, 0, null, true);
        addZombie(0, 12, 0, 12, 0, null, true);
        addZombie(0, 0, 0, 0, 1, null, true);
        addZombie(0, 0, 0, 0, 0, null, false);
        index.endTick();
        assertEquals(4, serve(0, 0, 0, 0, null));
        CorpseCount.maxCorpseCount = 2;
        assertEquals(2, serve(0, 0, 0, 0, null));
        assertEquals(3, serve(3, 0, 0, 0, null));
        assertFalse(
                StormCorpseZombieCount.readZombieHealthImpact(
                        new BooleanConfigOption("off", false)));
        assertEquals(5, StormCorpseZombieCount.augment(5, 0, 0, 0, null));
        index.invalidate();
        assertTrue(
                StormCorpseZombieCount.readZombieHealthImpact(new BooleanConfigOption("on", true)));
        assertEquals(5, StormCorpseZombieCount.augment(5, 0, 0, 0, null));
    }
}
