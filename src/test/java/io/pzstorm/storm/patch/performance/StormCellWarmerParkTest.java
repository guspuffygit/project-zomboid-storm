package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.vehicles.StormVehicleSleep;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.reflect.ReflectionFactory;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/**
 * The two per-tick lists cell warming now parks: the cell-global {@code IsoCell.processIsoObject}
 * list and the warm cell's vehicles.
 *
 * <p>Three kinds of case, deliberately separated:
 *
 * <ul>
 *   <li><b>Controls against the unpatched engine.</b> {@link
 *       #controlEngineTicksProcessObjectsWithNoRelevanceGate} and {@link
 *       #controlVanillaRemovalIsAppliedBeforeTheNextPass} drive the real {@code IsoCell} and prove
 *       the premise: the list is ticked unconditionally and only a deferred removal stops it.
 *       {@link #controlStovesAndVehiclesStillBookImportantAreas} pins, at bytecode level, that a
 *       stove registers on that list and books an important area from {@code update()}, and that a
 *       vehicle books from the tail of {@code BaseVehicle.update()}. If any of these stop holding,
 *       the engine has changed and the patch must be re-read before it is trusted.
 *   <li><b>The parking helpers</b>, driven on real engine objects: drain picks exactly the cell's
 *       objects and they stop ticking on the next pass; restore puts them back and they tick again;
 *       a vehicle in a warm cell has its whole update skipped by {@code StormVehicleSleep} and runs
 *       again once released. Each patched case is paired with the same call on an unparked object,
 *       which is the control that shows the skip is the parking and not the throttle.
 *   <li><b>Wiring</b>, at bytecode level: {@code warm()} calls both drains, {@code
 *       reconnectAndRestore} both restores, {@code evictLiteReconnect} the release, and {@code
 *       StormVehicleSleep.enterUpdate} the parked check. {@code warm()} itself needs a live {@code
 *       ServerMap}, a bound {@code ServerCell} class and native collision to run, so it is not
 *       driven here; the wiring pin is what says the tested helpers are the ones it calls.
 * </ul>
 *
 * <p>Engine objects are allocated without running their constructors (the same {@code
 * ReflectionFactory} route {@code StormCellWarmerEvictionSelectionTest} uses for {@code
 * UdpConnection}); only the fields the code under test reads are populated.
 */
class StormCellWarmerParkTest implements UnitTest {

    private boolean savedServerFlag;
    private long savedTick;

    /** ⛔ {@code GameServer.server} is process-wide; see {@code ZombieRainWanderPatchTest}. */
    @BeforeEach
    void captureState() {
        savedServerFlag = GameServer.server;
        savedTick = StormVehicleSleep.tick;
    }

    @AfterEach
    void restoreState() {
        GameServer.server = savedServerFlag;
        StormVehicleSleep.tick = savedTick;
    }

    // ------------------------------------------------------------- engine controls

    /**
     * The premise of the whole patch: {@code IsoCell.ProcessIsoObject} ticks every registered
     * object, asking nothing about which ServerCell it is in or whether that cell is relevant.
     */
    @Test
    void controlEngineTicksProcessObjectsWithNoRelevanceGate() throws Exception {
        IsoCell cell = bareCell();
        IsoChunk chunk = bareChunk(10, 10);
        Ticker a = tickerOn(cell, squareOn(chunk));
        Ticker b = tickerOn(cell, squareOn(chunk));
        Ticker noSquare = tickerOn(cell, null);

        processIsoObject(cell);
        processIsoObject(cell);

        assertEquals(2, a.ticks);
        assertEquals(2, b.ticks);
        assertEquals(2, noSquare.ticks, "a square is not a precondition for being ticked");
    }

    /**
     * Vanilla's only exit from the list is deferred: {@code addToProcessIsoObjectRemove} queues,
     * and the next {@code ProcessIsoObject} applies the queue <em>before</em> it iterates. That
     * ordering is what lets a warm() at postupdate stop the object from the very next tick.
     */
    @Test
    void controlVanillaRemovalIsAppliedBeforeTheNextPass() throws Exception {
        IsoCell cell = bareCell();
        Ticker t = tickerOn(cell, squareOn(bareChunk(1, 1)));
        processIsoObject(cell);
        assertEquals(1, t.ticks);

        cell.addToProcessIsoObjectRemove(t);
        assertTrue(cell.getProcessIsoObjectRemove().contains(t));
        processIsoObject(cell);
        assertEquals(1, t.ticks, "queued removal must be applied before the pass, not after it");
        assertFalse(cell.getProcessIsoObjects().contains(t));

        // And re-registering is vanilla's own set-guarded add: back on, ticking again.
        cell.addToProcessIsoObject(t);
        processIsoObject(cell);
        assertEquals(2, t.ticks);
    }

    /**
     * The two engine callers of {@code ImportantAreaManager.updateOrAdd}, and how each reaches it,
     * pinned so a game update that moves them fails here instead of leaving the patch parking the
     * wrong list.
     */
    @Test
    void controlStovesAndVehiclesStillBookImportantAreas() throws Exception {
        byte[] stove = classBytes("zombie/iso/objects/IsoStove.class");
        assertTrue(
                calls(stove, "addToWorld", "zombie/iso/IsoCell", "addToProcessIsoObject") >= 1,
                "IsoStove.addToWorld must register on IsoCell.processIsoObject");
        assertTrue(
                calls(stove, "update", "zombie/core/ImportantAreaManager", "updateOrAdd") >= 1,
                "IsoStove.update must book an important area");

        byte[] vehicle = classBytes("zombie/vehicles/BaseVehicle.class");
        assertTrue(
                calls(vehicle, "update", "zombie/vehicles/BaseVehicle", "updateImportantAreas")
                        >= 1,
                "BaseVehicle.update must reach updateImportantAreas");
        assertTrue(
                calls(
                                vehicle,
                                "updateImportantAreas",
                                "zombie/core/ImportantAreaManager",
                                "updateOrAdd")
                        >= 1,
                "BaseVehicle.updateImportantAreas must book an important area");

        byte[] object = classBytes("zombie/iso/IsoObject.class");
        assertTrue(
                calls(
                                object,
                                "removeFromWorld",
                                "zombie/iso/IsoCell",
                                "addToProcessIsoObjectRemove")
                        >= 1,
                "IsoObject.removeFromWorld is vanilla's only exit from the list");
        byte[] chunk = classBytes("zombie/iso/IsoChunk.class");
        assertTrue(
                calls(chunk, "removeFromWorld", "zombie/iso/IsoObject", "removeFromWorldToMeta")
                        >= 1,
                "IsoChunk.removeFromWorld is what reaches it on a destructive unload");
    }

    // ------------------------------------------------------------- process objects

    @Test
    void drainTakesExactlyTheCellsObjectsAndTheyStopTicking() throws Exception {
        IsoCell cell = bareCell();
        IsoChunk inA = bareChunk(8, 8);
        IsoChunk inB = bareChunk(9, 8);
        IsoChunk outside = bareChunk(80, 80);
        Ticker a1 = tickerOn(cell, squareOn(inA));
        Ticker a2 = tickerOn(cell, squareOn(inA));
        Ticker b = tickerOn(cell, squareOn(inB));
        Ticker far = tickerOn(cell, squareOn(outside));
        Ticker noSquare = tickerOn(cell, null);

        List<IsoObject> stash = new ArrayList<>();
        StormCellWarmer.drainProcessObjects(cell, grid(inA, inB), stash);

        assertEquals(List.of(a1, a2, b), stash, "only the cell's own objects, in list order");
        assertTrue(cell.getProcessIsoObjectRemove().containsAll(stash));
        assertFalse(cell.getProcessIsoObjectRemove().contains(far));
        assertFalse(cell.getProcessIsoObjectRemove().contains(noSquare));
        assertEquals(5, cell.getProcessIsoObjects().size(), "removal is deferred, as vanilla's is");

        processIsoObject(cell);
        assertEquals(0, a1.ticks);
        assertEquals(0, a2.ticks);
        assertEquals(0, b.ticks);
        assertEquals(1, far.ticks, "an object outside the cell is untouched");
        assertEquals(1, noSquare.ticks);
        assertEquals(2, cell.getProcessIsoObjects().size());
    }

    @Test
    void drainLeavesAnObjectThatIsAlreadyLeaving() throws Exception {
        IsoCell cell = bareCell();
        IsoChunk chunk = bareChunk(3, 3);
        Ticker leaving = tickerOn(cell, squareOn(chunk));
        Ticker staying = tickerOn(cell, squareOn(chunk));
        cell.addToProcessIsoObjectRemove(leaving);

        List<IsoObject> stash = new ArrayList<>();
        StormCellWarmer.drainProcessObjects(cell, grid(chunk), stash);

        assertEquals(List.of(staying), stash, "a pending vanilla removal is not ours to restore");
        assertTrue(cell.getProcessIsoObjectRemove().contains(leaving));
    }

    @Test
    void drainWithNoCellOrNoChunksOrEmptyListIsANoOp() throws Exception {
        IsoCell cell = bareCell();
        List<IsoObject> stash = new ArrayList<>();
        StormCellWarmer.drainProcessObjects(null, grid(bareChunk(1, 1)), stash);
        StormCellWarmer.drainProcessObjects(cell, new IsoChunk[8][8], stash);
        StormCellWarmer.drainProcessObjects(cell, grid(bareChunk(1, 1)), stash);
        assertTrue(stash.isEmpty());
        assertTrue(cell.getProcessIsoObjectRemove().isEmpty());
    }

    @Test
    void restorePutsThemBackAndTheyTickAgainAndItIsIdempotent() throws Exception {
        IsoCell cell = bareCell();
        IsoChunk chunk = bareChunk(4, 4);
        Ticker a = tickerOn(cell, squareOn(chunk));
        Ticker b = tickerOn(cell, squareOn(chunk));
        List<IsoObject> stash = new ArrayList<>();
        StormCellWarmer.drainProcessObjects(cell, grid(chunk), stash);
        processIsoObject(cell);
        assertEquals(0, a.ticks);
        assertTrue(cell.getProcessIsoObjects().isEmpty());

        StormCellWarmer.restoreProcessObjects(cell, stash);
        assertTrue(stash.isEmpty(), "the stash is drained so a retried rewarm cannot re-add");
        assertEquals(2, cell.getProcessIsoObjects().size());
        processIsoObject(cell);
        assertEquals(1, a.ticks);
        assertEquals(1, b.ticks);

        // A second restore of the same objects (say a warm/rewarm cycle that raced a vanilla
        // addToWorld) goes through vanilla's set guard: no duplicates, no double ticks.
        List<IsoObject> again = new ArrayList<>(List.of(a, b));
        StormCellWarmer.restoreProcessObjects(cell, again);
        assertEquals(2, cell.getProcessIsoObjects().size());
        processIsoObject(cell);
        assertEquals(2, a.ticks);
    }

    @Test
    void restoreLeavesAnObjectWhoseSquareOrChunkIsGone() throws Exception {
        IsoCell cell = bareCell();
        IsoChunk chunk = bareChunk(5, 5);
        Ticker squareGone = tickerOn(cell, squareOn(chunk));
        Ticker chunkGone = tickerOn(cell, squareOn(chunk));
        Ticker intact = tickerOn(cell, squareOn(chunk));
        List<IsoObject> stash = new ArrayList<>();
        StormCellWarmer.drainProcessObjects(cell, grid(chunk), stash);
        processIsoObject(cell);

        squareGone.square = null;
        chunkGone.square.chunk = null;
        StormCellWarmer.restoreProcessObjects(cell, stash);

        assertEquals(List.of(intact), cell.getProcessIsoObjects());
        assertTrue(stash.isEmpty());
    }

    @Test
    void restoreWithoutACellStillDrainsTheStash() throws Exception {
        IsoCell cell = bareCell();
        Ticker t = tickerOn(cell, squareOn(bareChunk(6, 6)));
        List<IsoObject> stash = new ArrayList<>(List.of(t));
        StormCellWarmer.restoreProcessObjects(null, stash);
        assertTrue(stash.isEmpty());
    }

    // ------------------------------------------------------------- vehicles

    @Test
    void parkMarksTheCellsVehiclesOnlyAndReleaseClearsThem() throws Exception {
        IsoChunk inA = bareChunk(8, 8);
        IsoChunk inB = bareChunk(8, 9);
        IsoChunk outside = bareChunk(40, 40);
        BaseVehicle v1 = bareVehicle((short) 1);
        BaseVehicle v2 = bareVehicle((short) 2);
        BaseVehicle v3 = bareVehicle((short) 3);
        BaseVehicle elsewhere = bareVehicle((short) 4);
        inA.vehicles.add(v1);
        inA.vehicles.add(v2);
        inB.vehicles.add(v3);
        outside.vehicles.add(elsewhere);

        List<BaseVehicle> stash = new ArrayList<>();
        StormCellWarmer.parkVehicles(grid(inA, inB), stash);
        try {
            assertEquals(List.of(v1, v2, v3), stash);
            assertTrue(StormCellWarmer.isWarmedVehicle(v1));
            assertTrue(StormCellWarmer.isWarmedVehicle(v3));
            assertFalse(StormCellWarmer.isWarmedVehicle(elsewhere));
            assertFalse(StormCellWarmer.isWarmedVehicle("not a vehicle"));
            assertFalse(StormCellWarmer.isWarmedVehicle(null));

            // Parking the same chunk again (a neighbouring cell listing the same vehicle, or a
            // retry) stashes nothing twice, so a release cannot un-park another cell's vehicle.
            List<BaseVehicle> second = new ArrayList<>();
            StormCellWarmer.parkVehicles(grid(inA), second);
            assertTrue(second.isEmpty());
        } finally {
            StormCellWarmer.releaseVehicles(stash);
        }
        assertTrue(stash.isEmpty());
        assertFalse(StormCellWarmer.isWarmedVehicle(v1));
        assertFalse(StormCellWarmer.isWarmedVehicle(v2));
        assertFalse(StormCellWarmer.isWarmedVehicle(v3));
    }

    /**
     * The skip is the parking, not the throttle: the same vehicle on a tick where {@code
     * StormVehicleSleep}'s stagger gives it a full update runs (control), is skipped while parked,
     * and runs again once released.
     */
    @Test
    void aParkedVehicleSkipsItsWholeUpdateAndRunsAgainOnceReleased() throws Exception {
        GameServer.server = true;
        StormVehicleSleep.tick = 0;
        BaseVehicle vehicle = bareVehicle((short) 0);
        // (tick + vehicleId) % SLEEP_TICKS == 0: the throttle's own full-update tick, so the
        // control below never reaches the field reads a bare vehicle could not answer.
        assertNotEquals(0L, StormVehicleSleep.enterUpdate(vehicle), "control: unparked, it runs");

        IsoChunk chunk = bareChunk(2, 2);
        chunk.vehicles.add(vehicle);
        List<BaseVehicle> stash = new ArrayList<>();
        StormCellWarmer.parkVehicles(grid(chunk), stash);
        try {
            long before = io.pzstorm.storm.metrics.StormCellWarmingMetrics.vehicleUpdatesSkipped;
            assertEquals(0L, StormVehicleSleep.enterUpdate(vehicle), "parked: the body is skipped");
            assertEquals(0L, StormVehicleSleep.enterUpdate(vehicle), "and stays skipped");
            assertEquals(
                    before + 2,
                    io.pzstorm.storm.metrics.StormCellWarmingMetrics.vehicleUpdatesSkipped);
        } finally {
            StormCellWarmer.releaseVehicles(stash);
        }
        assertNotEquals(0L, StormVehicleSleep.enterUpdate(vehicle), "released: it runs again");
    }

    @Test
    void parkingNeverReachesAClientVehicle() throws Exception {
        GameServer.server = false;
        BaseVehicle vehicle = bareVehicle((short) 7);
        IsoChunk chunk = bareChunk(2, 2);
        chunk.vehicles.add(vehicle);
        List<BaseVehicle> stash = new ArrayList<>();
        StormCellWarmer.parkVehicles(grid(chunk), stash);
        try {
            assertEquals(
                    -1L, StormVehicleSleep.enterUpdate(vehicle), "client JVM: vanilla, untimed");
        } finally {
            StormCellWarmer.releaseVehicles(stash);
        }
    }

    // ------------------------------------------------------------- wiring

    @Test
    void warmRewarmAndEvictionAreWiredToTheHelpers() throws Exception {
        byte[] warmer = classBytes("io/pzstorm/storm/patch/performance/StormCellWarmer.class");
        String owner = "io/pzstorm/storm/patch/performance/StormCellWarmer";
        assertEquals(1, calls(warmer, "warm", owner, "drainProcessObjects"));
        assertEquals(1, calls(warmer, "warm", owner, "parkVehicles"));
        assertEquals(
                1,
                calls(warmer, "warm", owner, "restoreProcessObjects"),
                "the rollback path restores what a failed warm drained");
        assertEquals(1, calls(warmer, "warm", owner, "releaseVehicles"));
        assertEquals(1, calls(warmer, "reconnectAndRestore", owner, "restoreProcessObjects"));
        assertEquals(1, calls(warmer, "reconnectAndRestore", owner, "releaseVehicles"));
        assertEquals(1, calls(warmer, "evictLiteReconnect", owner, "releaseVehicles"));
        assertEquals(
                0,
                calls(warmer, "evictLiteReconnect", owner, "restoreProcessObjects"),
                "an evicted cell's objects stay off the list; the unload takes them vanilla's way");

        byte[] sleep = classBytes("io/pzstorm/storm/vehicles/StormVehicleSleep.class");
        assertEquals(1, calls(sleep, "enterUpdate", owner, "isWarmedVehicle"));
    }

    // ------------------------------------------------------------- fixtures

    /** A ticking static object: the role a stove, generator or washer plays on the list. */
    static final class Ticker extends IsoObject {
        int ticks;

        @Override
        public void update() {
            ticks++;
        }
    }

    private static <T> T bare(Class<T> type) throws Exception {
        Constructor<?> ctor =
                ReflectionFactory.getReflectionFactory()
                        .newConstructorForSerialization(
                                type, Object.class.getDeclaredConstructor());
        return type.cast(ctor.newInstance());
    }

    private static void set(Object target, Class<?> declaring, String field, Object value)
            throws Exception {
        Field f = declaring.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static IsoCell bareCell() throws Exception {
        IsoCell cell = bare(IsoCell.class);
        set(cell, IsoCell.class, "processIsoObject", new ArrayList<IsoObject>());
        set(cell, IsoCell.class, "processIsoObjectSet", new HashSet<IsoObject>());
        set(cell, IsoCell.class, "processIsoObjectRemove", new HashSet<IsoObject>());
        return cell;
    }

    private static IsoChunk bareChunk(int wx, int wy) throws Exception {
        IsoChunk chunk = bare(IsoChunk.class);
        chunk.wx = wx;
        chunk.wy = wy;
        set(chunk, IsoChunk.class, "vehicles", new ArrayList<BaseVehicle>());
        return chunk;
    }

    private static IsoGridSquare squareOn(IsoChunk chunk) throws Exception {
        IsoGridSquare sq = bare(IsoGridSquare.class);
        sq.chunk = chunk;
        return sq;
    }

    private static Ticker tickerOn(IsoCell cell, IsoGridSquare square) throws Exception {
        Ticker t = bare(Ticker.class);
        t.square = square;
        cell.addToProcessIsoObject(t);
        return t;
    }

    private static BaseVehicle bareVehicle(short id) throws Exception {
        BaseVehicle v = bare(BaseVehicle.class);
        v.vehicleId = id;
        return v;
    }

    private static IsoChunk[][] grid(IsoChunk... chunks) {
        IsoChunk[][] g = new IsoChunk[8][8];
        for (int i = 0; i < chunks.length; i++) {
            g[i / 8][i % 8] = chunks[i];
        }
        return g;
    }

    /** The real private per-tick pass, exactly as {@code IsoCell.updateInternal} calls it. */
    private static void processIsoObject(IsoCell cell) throws Exception {
        Method m = IsoCell.class.getDeclaredMethod("ProcessIsoObject");
        m.setAccessible(true);
        m.invoke(cell);
    }

    private static byte[] classBytes(String resource) throws Exception {
        try (InputStream is =
                StormCellWarmerParkTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(is, resource + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** Invocations of {@code owner.name} inside {@code method}, any invoke opcode. */
    private static int calls(byte[] classBytes, String method, String owner, String name) {
        int[] count = {0};
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String mName,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (!method.equals(mName)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String mOwner,
                                            String calledName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (owner.equals(mOwner) && name.equals(calledName)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }
}
