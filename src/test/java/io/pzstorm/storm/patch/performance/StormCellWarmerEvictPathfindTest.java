package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.bare;
import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.bareChunk;
import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.callSequence;
import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.calls;
import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.classBytes;
import static io.pzstorm.storm.patch.performance.StormCellWarmerParkTest.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.iso.IsoChunk;
import zombie.network.ServerMap;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.pathfind.nativeCode.PathfindNativeThread;

/**
 * Eviction no longer round-trips a warm cell's chunks through the native pathfinder: {@code
 * evictLiteReconnect} marks each chunk it used to re-add instead of queueing a {@code
 * ChunkUpdateTask}, and the woven {@code PathfindNative.removeChunkFromWorld} skips exactly the one
 * remove the immediate {@code Unload} sends for that chunk. Until a woven remove has been seen the
 * re-add stays, so an advice that failed to weave can never leave a remove unbalanced.
 *
 * <p>Driven on real engine objects where it can be (the marking against the real pathfinder task
 * queue, the woven remove), pinned at bytecode level where it cannot ({@code evictOverBudget} needs
 * a live {@code ServerMap}). Fixture chunks have an empty level range, so a real {@code
 * ChunkUpdateTask.init} reads no squares and the queue shows exactly which chunks were re-added.
 */
class StormCellWarmerEvictPathfindTest implements UnitTest {

    private static final String WARMER = "io/pzstorm/storm/patch/performance/StormCellWarmer";

    private boolean savedArmed;
    private boolean savedNative;
    private PathfindNativeThread savedThread;

    @BeforeEach
    void captureState() throws Exception {
        savedArmed = armed().getBoolean(null);
        savedNative = PathfindNative.useNativeCode;
        savedThread = PathfindNativeThread.instance;
    }

    @AfterEach
    void restoreState() throws Exception {
        skipSet().clear();
        armed().setBoolean(null, savedArmed);
        PathfindNative.useNativeCode = savedNative;
        PathfindNativeThread.instance = savedThread;
    }

    @Test
    void evictLiteReconnectKeepsTheAnimalAndPolygonalMap2ReAdds() throws Exception {
        byte[] warmer = classBytes(WARMER + ".class");
        assertEquals(
                1,
                calls(
                        warmer,
                        "evictLiteReconnect",
                        "zombie/pathfind/PolygonalMap2",
                        "addChunkToWorld"),
                "the non-native pathfinder keeps its re-add");
        assertEquals(
                1,
                calls(
                        warmer,
                        "evictLiteReconnect",
                        "zombie/characters/animals/AnimalPopulationManager",
                        "addChunkToWorld"),
                "the animal re-add stays: the unload's remove virtualizes the population");
    }

    @Test
    void anyWovenRemoveArmsTheSkip() throws Exception {
        armed().setBoolean(null, false);
        assertFalse(StormCellWarmer.consumeEvictPathfindSkip(bareChunk(40, 40)));
        assertTrue(armed().getBoolean(null));
    }

    @Test
    void armedEvictionQueuesNoPathfinderAddAndMarksExactlyThoseChunks() throws Exception {
        Fixture f = new Fixture();
        armed().setBoolean(null, true);
        f.evict();

        assertTrue(f.queue.isEmpty(), "no ChunkUpdateTask queued for a chunk that is leaving");
        // The others first, while both marks are still pending.
        assertFalse(
                StormCellWarmer.consumeEvictPathfindSkip(f.alreadyReAdded),
                "the failed rewarm re-added it in full, so its remove must still run");
        assertFalse(
                StormCellWarmer.consumeEvictPathfindSkip(f.softReset),
                "never re-added, before or now: its remove runs exactly as it did");
        assertFalse(StormCellWarmer.consumeEvictPathfindSkip(bareChunk(40, 40)));
        assertTrue(StormCellWarmer.consumeEvictPathfindSkip(f.first));
        assertFalse(StormCellWarmer.consumeEvictPathfindSkip(f.first), "one skip per chunk");
        assertTrue(StormCellWarmer.consumeEvictPathfindSkip(f.last));
        assertTrue(skipSet().isEmpty());
    }

    @Test
    void unarmedEvictionKeepsTheReAddAndMarksNothing() throws Exception {
        Fixture f = new Fixture();
        armed().setBoolean(null, false);
        f.evict();

        assertEquals(2, f.queue.size(), "the same two chunks the armed path marks");
        assertTrue(skipSet().isEmpty());
    }

    /**
     * Every eviction's {@code Unload} is followed by the clear on both exits, so a skip the unload
     * did not consume can never swallow a later, unrelated remove of that chunk.
     */
    @Test
    void theSkipSetIsEmptiedAfterEveryEvictionUnloadEvenIfItThrows() throws Exception {
        List<String> sequence = callSequence(classBytes(WARMER + ".class"), "evictOverBudget");
        int unload = sequence.indexOf(WARMER + ".unloadEvicted");
        assertTrue(unload >= 0);
        assertEquals(unload, sequence.lastIndexOf(WARMER + ".unloadEvicted"));
        List<String> after = sequence.subList(unload + 1, sequence.size());
        assertEquals(
                2,
                after.stream().filter("java/util/Set.clear"::equals).count(),
                "normal exit plus the finally's exception path");
    }

    /**
     * The woven method, run: a marked chunk returns without entering vanilla's body, any other
     * chunk enters it. The woven copy is defined in a child loader, so vanilla's body announces
     * itself with an {@link IllegalAccessError} at its first touch of the package-private {@code
     * ChunkRemoveTask} (a different runtime package from the child's view); nothing is queued.
     */
    @Test
    void wovenRemoveSkipsOnlyAMarkedChunkAndOnlyOnce() throws Exception {
        byte[] woven =
                new PathfindNativeRemoveChunkPatch()
                        .transform(classBytes("zombie/pathfind/nativeCode/PathfindNative.class"));
        assertNotNull(woven);
        assertEquals(1, calls(woven, "removeChunkFromWorld", WARMER, "consumeEvictPathfindSkip"));
        assertEquals(
                1,
                calls(
                        woven,
                        "removeChunkFromWorld",
                        "io/pzstorm/storm/metrics/MainLoopStepTimings",
                        "record"),
                "the timing advice is still woven");
        assertEquals(
                1,
                calls(
                        woven,
                        "removeChunkFromWorld",
                        "zombie/pathfind/nativeCode/ChunkRemoveTask",
                        "alloc"),
                "vanilla's body is kept behind the skip");

        Class<?> patched = define("zombie.pathfind.nativeCode.PathfindNative", woven);
        Object pathfind = bare(patched);
        Method remove = patched.getMethod("removeChunkFromWorld", IsoChunk.class);
        IsoChunk marked = bareChunk(1, 1);
        IsoChunk other = bareChunk(2, 2);
        // Non-null so vanilla's body goes on past its null check.
        PathfindNativeThread.instance = bare(PathfindNativeThread.class);
        skipSet().add(marked);
        assertEnteredVanilla(remove, pathfind, other);
        remove.invoke(pathfind, marked);
        assertTrue(skipSet().isEmpty(), "the skip was consumed");
        assertEnteredVanilla(remove, pathfind, marked);
    }

    private static void assertEnteredVanilla(Method remove, Object pathfind, IsoChunk chunk) {
        InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, () -> remove.invoke(pathfind, chunk));
        assertInstanceOf(IllegalAccessError.class, thrown.getCause());
        assertTrue(
                thrown.getCause().getMessage().contains("ChunkRemoveTask"),
                "thrown by vanilla's body, not by either advice: " + thrown.getCause());
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A warm cell as eviction finds it after a rewarm that threw on chunk 1: chunk 0 already
     * re-added in full (cursor on 1), chunk 1 soft-resetting, chunks 2 and 63 still detached, the
     * rest empty. The real pathfinder thread object is swapped for one with a fresh task queue.
     */
    private static final class Fixture {
        final IsoChunk[][] grid = new IsoChunk[8][8];
        final IsoChunk alreadyReAdded = chunk(grid, 0, IsoChunk.JobType.None);
        final IsoChunk softReset = chunk(grid, 1, IsoChunk.JobType.SoftReset);
        final IsoChunk first = chunk(grid, 2, IsoChunk.JobType.None);
        final IsoChunk last = chunk(grid, 63, IsoChunk.JobType.None);
        final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();

        Fixture() throws Exception {}

        void evict() throws Exception {
            ServerMap.ServerCell cell = bare(ServerMap.ServerCell.class);
            set(cell, ServerMap.ServerCell.class, "chunks", grid);
            Object warm = warmCell(cell);
            set(warm, warm.getClass(), "reconnectCursor", 1);
            PathfindNativeThread thread = bare(PathfindNativeThread.class);
            set(thread, PathfindNativeThread.class, "chunkTaskQueue", queue);
            set(thread, PathfindNativeThread.class, "notifier", new Object());
            PathfindNativeThread.instance = thread;
            PathfindNative.useNativeCode = true;

            AnimalPopulationManager animals = AnimalPopulationManager.getInstance();
            Field client = AnimalPopulationManager.class.getDeclaredField("client");
            client.setAccessible(true);
            boolean savedClient = client.getBoolean(animals);
            try {
                // Off the server its addChunkToWorld is a no-op instead of the native n_loadChunk.
                client.setBoolean(animals, true);
                Method evict =
                        StormCellWarmer.class.getDeclaredMethod(
                                "evictLiteReconnect", ServerMap.ServerCell.class, warm.getClass());
                evict.setAccessible(true);
                evict.invoke(null, cell, warm);
            } finally {
                client.setBoolean(animals, savedClient);
            }
        }
    }

    private static IsoChunk chunk(IsoChunk[][] grid, int index, IsoChunk.JobType jobType) {
        try {
            IsoChunk chunk = bareChunk(index / 8, index % 8);
            chunk.jobType = jobType;
            chunk.minLevel = 1;
            chunk.maxLevel = 0;
            grid[index / 8][index % 8] = chunk;
            return chunk;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object warmCell(ServerMap.ServerCell cell) throws Exception {
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
        return ctor.newInstance(
                cell,
                0L,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private static Set<IsoChunk> skipSet() throws Exception {
        Field f = StormCellWarmer.class.getDeclaredField("EVICT_PATHFIND_SKIP");
        f.setAccessible(true);
        return (Set<IsoChunk>) f.get(null);
    }

    private static Field armed() throws Exception {
        Field f = StormCellWarmer.class.getDeclaredField("evictPathfindSkipArmed");
        f.setAccessible(true);
        return f;
    }

    private static Class<?> define(String name, byte[] bytes) {
        return new ClassLoader(StormCellWarmerEvictPathfindTest.class.getClassLoader()) {
            Class<?> defineIt() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.defineIt();
    }
}
