package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.pzstorm.storm.IntegrationTest;
import java.lang.reflect.Field;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.characters.IsoZombie;
import zombie.core.random.RandAbstract;
import zombie.core.random.RandStandard;
import zombie.network.ServerMap;

/**
 * Runtime integration test for {@link IsoZombieMapInvariant#ensureMapEntry(Object)}. Drives the
 * helper against a <em>real</em> {@link IsoZombie} instance (created via {@link
 * Unsafe#allocateInstance(Class)} so we skip the heavyweight constructor — no cell, no animations,
 * no state machine) and a <em>real</em> {@link ServerMap#instance} / {@code zombieMap}.
 *
 * <p>Coverage versus the other two test files:
 *
 * <ul>
 *   <li>{@link IsoZombieMapInvariantTest} — pure decision logic, no game classes.
 *   <li>{@link IsoZombieUpdateFixPatchTest} — bytecode shape: confirms the advice is inlined into
 *       {@code update()}.
 *   <li>This test — runtime semantics: confirms the helper actually mutates {@code zombieMap} and
 *       {@code onlineId} the way the design says it should. Catches regressions where {@code
 *       IsoZombie.onlineId} gets renamed, {@code ServerMap.zombieMap} changes type, or the helper's
 *       {@code Unsafe}-friendly contract breaks.
 * </ul>
 *
 * <p>{@code Unsafe.allocateInstance} is used because instantiating {@code IsoZombie} through its
 * constructor would require a fully-populated {@code IsoCell} and the entire animation /
 * state-machine graph — orders of magnitude more setup than a unit test should carry. The helper
 * only reads {@code onlineId} and the {@code zombieMap}, so a field-zeroed instance is enough.
 */
class IsoZombieMapInvariantIntegrationTest implements IntegrationTest {

    private static Unsafe unsafe;

    @BeforeAll
    static void setUpUnsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (Unsafe) f.get(null);

        // ServerMap's static init creates an IsoObjectID, whose constructor calls Rand.Next via
        // RandStandard.INSTANCE.rand — but that field is only populated by RandStandard.init() at
        // game boot, never in tests. Inject a deterministic Random so the static init survives.
        // Same workaround as IsoObjectIDAllocateFixPatchTest.
        Field randField = RandAbstract.class.getDeclaredField("rand");
        randField.setAccessible(true);
        if (randField.get(RandStandard.INSTANCE) == null) {
            randField.set(RandStandard.INSTANCE, new Random(0));
        }
    }

    @BeforeEach
    void clearMap() {
        // ServerMap.instance is a static-initialized singleton; the same zombieMap persists across
        // tests. Clear so each test starts from a clean address space.
        ServerMap.instance.zombieMap.clear();
    }

    private static IsoZombie newBareZombie() throws Exception {
        return (IsoZombie) unsafe.allocateInstance(IsoZombie.class);
    }

    @Test
    void noOpWhenInvariantAlreadyHolds() throws Exception {
        IsoZombie zombie = newBareZombie();
        zombie.onlineId = 100;
        ServerMap.instance.zombieMap.put((short) 100, zombie);

        IsoZombieMapInvariant.ensureMapEntry(zombie);

        assertSame(zombie, ServerMap.instance.zombieMap.get((short) 100));
        assertEquals((short) 100, zombie.onlineId);
        assertEquals(1, ServerMap.instance.zombieMap.size());
    }

    @Test
    void noOpWhenOnlineIdIsInvalid() throws Exception {
        IsoZombie zombie = newBareZombie();
        zombie.onlineId = IsoZombieMapInvariant.ID_INVALID;

        IsoZombieMapInvariant.ensureMapEntry(zombie);

        // The helper must not touch the map or write to onlineId for a zombie that hasn't been
        // assigned an id yet — that's the bootstrap state and the next update() tick handles it.
        assertEquals(0, ServerMap.instance.zombieMap.size());
        assertEquals(IsoZombieMapInvariant.ID_INVALID, zombie.onlineId);
    }

    @Test
    void missingPutHealsTheMap() throws Exception {
        // The Bug 2 / chunk-load shape: zombie's onlineId was set by the re-allocate branch in
        // IsoZombie.updateInternal(), but the corresponding zombieMap.put never happened.
        IsoZombie zombie = newBareZombie();
        zombie.onlineId = 200;
        assertNull(ServerMap.instance.zombieMap.get((short) 200), "slot should start empty");

        IsoZombieMapInvariant.ensureMapEntry(zombie);

        assertSame(
                zombie,
                ServerMap.instance.zombieMap.get((short) 200),
                "missing-put case must restore the entry");
        assertEquals((short) 200, zombie.onlineId, "onlineId must not be touched on missing-put");
    }

    @Test
    void collisionClearsOnlineIdAndPreservesIncumbent() throws Exception {
        // Two zombies disagree on who owns slot 300. The map already holds the incumbent; the
        // intruder must yield and re-allocate next tick.
        IsoZombie incumbent = newBareZombie();
        incumbent.onlineId = 300;
        ServerMap.instance.zombieMap.put((short) 300, incumbent);

        IsoZombie intruder = newBareZombie();
        intruder.onlineId = 300;

        IsoZombieMapInvariant.ensureMapEntry(intruder);

        assertSame(
                incumbent,
                ServerMap.instance.zombieMap.get((short) 300),
                "incumbent must keep the slot");
        assertEquals(
                IsoZombieMapInvariant.ID_INVALID,
                intruder.onlineId,
                "intruder's onlineId must be reset so the next allocate gives it a new id");
        assertNotEquals(
                (short) 300, intruder.onlineId, "intruder must not still claim the colliding id");
    }

    @Test
    void heals_thenSubsequentCallIsNoOp() throws Exception {
        // Idempotence: a second call after a successful heal must not touch anything. This is
        // the steady-state cost — one map lookup per zombie per tick.
        IsoZombie zombie = newBareZombie();
        zombie.onlineId = 400;

        IsoZombieMapInvariant.ensureMapEntry(zombie);
        assertSame(zombie, ServerMap.instance.zombieMap.get((short) 400));

        int sizeAfterHeal = ServerMap.instance.zombieMap.size();
        IsoZombieMapInvariant.ensureMapEntry(zombie);
        assertEquals(sizeAfterHeal, ServerMap.instance.zombieMap.size(), "no extra mutation");
        assertSame(zombie, ServerMap.instance.zombieMap.get((short) 400));
        assertEquals((short) 400, zombie.onlineId);
    }

    @Test
    void manyZombiesCanHealConcurrentlyByIdSpace() throws Exception {
        // Realistic mid-game shape: hundreds of orphans appear over a few ticks (chunk-stream-in),
        // each one's update() exits and heals itself. Make sure that running through a batch
        // doesn't trip over collisions or leave any orphan behind.
        int n = 250;
        IsoZombie[] zombies = new IsoZombie[n];
        for (int i = 0; i < n; i++) {
            zombies[i] = newBareZombie();
            zombies[i].onlineId = (short) (1000 + i);
        }

        for (IsoZombie z : zombies) {
            IsoZombieMapInvariant.ensureMapEntry(z);
        }

        assertEquals(n, ServerMap.instance.zombieMap.size());
        for (int i = 0; i < n; i++) {
            assertSame(
                    zombies[i],
                    ServerMap.instance.zombieMap.get((short) (1000 + i)),
                    "zombie at index " + i + " missing from map");
            assertEquals(
                    (short) (1000 + i),
                    zombies[i].onlineId,
                    "zombie at index " + i + " onlineId disturbed");
        }
    }
}
