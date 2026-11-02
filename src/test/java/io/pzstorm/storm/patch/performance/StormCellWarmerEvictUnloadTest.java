package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.bare;
import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.bareChunk;
import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.prometheus.metrics.core.metrics.Counter;
import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.metrics.StormCellWarmingMetrics;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.random.RandAbstract;
import zombie.core.random.RandStandard;
import zombie.iso.IsoChunk;
import zombie.iso.IsoMetaGrid;
import zombie.iso.RoomDef;
import zombie.network.ServerMap;

/**
 * The real {@code evictOverBudget} and {@code ServerCell.Unload} when the unload throws. A real
 * {@code IsoChunk.removeFromWorld} reaches native code, so fixture cells hold no chunks and the
 * throw comes from the last step of {@code Unload}, its walk over {@code unexploredRooms}. What is
 * under test is what the eviction does about a throw, wherever in the unload it came from.
 */
class StormCellWarmerEvictUnloadTest implements UnitTest {

    private static final int WIDTH = 4;

    /** Throws from the first {@code throwsLeft} walks the unload starts over it. */
    private static final class ThrowingRooms extends HashSet<RoomDef> {
        int throwsLeft;
        int walks;
        Runnable onWalk = () -> {};

        ThrowingRooms(int throwsLeft) {
            this.throwsLeft = throwsLeft;
        }

        @Override
        public Iterator<RoomDef> iterator() {
            walks++;
            onWalk.run();
            if (throwsLeft > 0) {
                throwsLeft--;
                throw new RuntimeException("Stored entity mismatch");
            }
            return Collections.emptyIterator();
        }
    }

    /** {@code ServerMap}'s static init draws a random number. */
    @BeforeAll
    static void initServerMap() throws Exception {
        Field rand = RandAbstract.class.getDeclaredField("rand");
        rand.setAccessible(true);
        Object previous = rand.get(RandStandard.INSTANCE);
        rand.set(RandStandard.INSTANCE, new Random());
        try {
            Class.forName(ServerMap.class.getName());
        } finally {
            rand.set(RandStandard.INSTANCE, previous);
        }
    }

    @BeforeEach
    @AfterEach
    void clearState() throws Exception {
        warmCells().clear();
        skipSet().clear();
    }

    @Test
    void anUnloadThatWorksRunsOnce() {
        int[] runs = {0};
        assertTrue(StormCellWarmer.unloadEvicted(() -> runs[0]++, 1, 1));
        assertEquals(1, runs[0]);
    }

    @Test
    void aThrownUnloadIsFinishedInPlace() throws Exception {
        ThrowingRooms rooms = new ThrowingRooms(1);
        ServerMap map = map();
        ServerMap.ServerCell cell = warmCell(map, 1, 1, rooms);

        double failures = unloadFailures();
        assertTrue(evict(map), "the LOS flag survives the throw");
        assertEquals(2, rooms.walks, "one failed try, one retry");
        assertEquals(failures + 1, unloadFailures(), "the failed try is counted");
        assertNull(map.cellMap[index(1, 1)], "the cell is gone from the map");
        assertFalse(map.loadedCells.contains(cell));
    }

    @Test
    void aCellThatKeepsThrowingStaysLoadedAndNothingEscapes() throws Exception {
        ThrowingRooms rooms = new ThrowingRooms(Integer.MAX_VALUE);
        ServerMap map = map();
        ServerMap.ServerCell cell = warmCell(map, 1, 1, rooms);

        assertTrue(evict(map), "nothing escapes, so the LOS flag still comes back");
        assertEquals(3, rooms.walks, "the tries are bounded");
        assertSame(cell, map.cellMap[index(1, 1)], "its last chunk has no save job yet");
        assertTrue(map.loadedCells.contains(cell));
        assertTrue(warmCells().isEmpty(), "it does not stay in the warm set");
    }

    @Test
    void theOtherEvictionsOfTheTickStillRunAfterACellGivesUp() throws Exception {
        ServerMap map = map();
        ServerMap.ServerCell bad = warmCell(map, 1, 1, new ThrowingRooms(Integer.MAX_VALUE));
        ServerMap.ServerCell good = warmCell(map, 2, 1, new ThrowingRooms(0));

        assertTrue(evict(map));
        assertSame(bad, map.cellMap[index(1, 1)]);
        assertNull(map.cellMap[index(2, 1)]);
        assertFalse(map.loadedCells.contains(good));
    }

    /** A retry still has chunks to reach, so their pathfinder skips must outlive it. */
    @Test
    void aRetryStillHasTheSkipsOfTheChunksItHasNotReached() throws Exception {
        IsoChunk later = bareChunk(0, 2);
        ThrowingRooms rooms = new ThrowingRooms(1);
        ServerMap map = map();
        warmCell(map, 1, 1, rooms);
        skipSet().add(later);
        boolean[] markedOnRetry = {false};
        rooms.onWalk =
                () -> {
                    try {
                        markedOnRetry[0] = rooms.walks == 2 && skipSet().contains(later);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                };

        assertTrue(evict(map));

        assertTrue(markedOnRetry[0], "cleared only when the unload is over");
        assertTrue(skipSet().isEmpty(), "and then cleared");
    }

    // --- fixtures ------------------------------------------------------------------------------

    private static int index(int wx, int wy) {
        return wy * WIDTH + wx;
    }

    private static ServerMap map() throws Exception {
        ServerMap map = bare(ServerMap.class);
        IsoMetaGrid grid = bare(IsoMetaGrid.class);
        set(map, ServerMap.class, "grid", grid);
        map.cellMap = new ServerMap.ServerCell[WIDTH * WIDTH];
        map.loadedCells = new ArrayList<>();
        return map;
    }

    /** A loaded cell with no chunks at the back of the warm set, nothing left to reconnect. */
    private static ServerMap.ServerCell warmCell(ServerMap map, int wx, int wy, ThrowingRooms rooms)
            throws Exception {
        ServerMap.ServerCell cell = bare(ServerMap.ServerCell.class);
        cell.wx = wx;
        cell.wy = wy;
        cell.isLoaded = true;
        set(cell, ServerMap.ServerCell.class, "chunks", new IsoChunk[8][8]);
        set(cell, ServerMap.ServerCell.class, "unexploredRooms", rooms);
        map.cellMap[index(wx, wy)] = cell;
        map.loadedCells.add(cell);

        Class<?> type = Class.forName(StormCellWarmer.class.getName() + "$WarmCell");
        Constructor<?> ctor =
                type.getDeclaredConstructor(
                        ServerMap.ServerCell.class,
                        long.class,
                        List.class,
                        List.class,
                        List.class,
                        List.class);
        ctor.setAccessible(true);
        Object warm =
                ctor.newInstance(
                        cell,
                        0L,
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>());
        set(warm, type, "reconnectCursor", 64);
        Method key = StormCellWarmer.class.getDeclaredMethod("key", int.class, int.class);
        key.setAccessible(true);
        warmCells().put((Long) key.invoke(null, wx, wy), warm);
        return cell;
    }

    /** With warming off the eviction cap is 0, so every warm cell is a victim. */
    private static boolean evict(ServerMap map) throws Exception {
        Method evict =
                StormCellWarmer.class.getDeclaredMethod(
                        "evictOverBudget", ServerMap.class, boolean.class);
        evict.setAccessible(true);
        return (boolean) evict.invoke(null, map, true);
    }

    private static double unloadFailures() throws Exception {
        Field field = StormCellWarmingMetrics.class.getDeclaredField("EVICT_UNLOAD_FAILURES");
        field.setAccessible(true);
        return ((Counter) field.get(null)).get();
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Object> warmCells() throws Exception {
        Field field = StormCellWarmer.class.getDeclaredField("WARM_CELLS");
        field.setAccessible(true);
        return (Map<Long, Object>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<IsoChunk> skipSet() throws Exception {
        Field field = StormCellWarmer.class.getDeclaredField("EVICT_PATHFIND_SKIP");
        field.setAccessible(true);
        return (Set<IsoChunk>) field.get(null);
    }
}
