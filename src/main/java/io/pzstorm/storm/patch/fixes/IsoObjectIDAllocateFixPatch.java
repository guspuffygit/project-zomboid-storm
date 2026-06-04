package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.IsoObjectIdPoolMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Rewrites {@code zombie.network.IsoObjectID.allocateID()} so it probes the underlying {@code
 * ConcurrentHashMap<Short, T>} for a free slot instead of returning the next sequential short.
 * Patches both server-side instances of the class — {@code ServerMap.zombieMap} (zombies) and
 * {@code AnimalInstanceManager.AnimalMap} (animals) — via the same class.
 *
 * <h2>Why this matters</h2>
 *
 * <p>Vanilla {@code allocateID()} is a free-running 16-bit counter with no uniqueness check, and
 * {@code put(short, T)} blindly overwrites whatever's already in the slot. With the population
 * uncapped (e.g. {@code -Dstorm.disableZombieCull=true}) the simultaneously-alive count can grow
 * into the same order of magnitude as the address space — at ~10k zombies in a 65 535-slot space
 * the expected number of probes to hit a collision drops by ~10× compared to the historical ~1k
 * baseline.
 *
 * <p>Each collision silently overwrites a live zombie's {@code zombieMap} entry. The displaced
 * holder remains in {@code cell.zombieList} ("phantom") but is unreachable by ID. The next time it
 * reaches a state that re-allocates its {@code onlineId} (e.g. {@code IsoZombie.update()} line 3329
 * after a transient {@code onlineId = -1}), the server starts broadcasting that holder under a
 * fresh ID. The client's {@code IDToZombieMap.get(packet.id)} miss path then spawns a brand-new
 * client zombie via {@code VirtualZombieManager.createRealZombieAlways}, while the original client
 * sprite lingers for ~800 ms until stale-zombie cleanup — visible as the "mitosis" players were
 * reporting.
 *
 * <p>The same {@code IsoObjectID#remove(short)} cascade also evicts an innocent holder when a
 * phantom is eventually cleaned up via {@code removeFromWorld}, so a single collision can leak
 * across multiple holders before draining.
 *
 * <h2>Prior art in PZ</h2>
 *
 * <p>{@code ObjectIDManager.addObject} (zombie.network.id) already uses a probe-until-free loop
 * over the same kind of map. Vehicles use a free-id stack ({@code VehicleIDMap}). Zombies and
 * animals are the two cases that never got either pattern; one class transform addresses both.
 *
 * <h2>Failure semantics</h2>
 *
 * <p>When the map is fully occupied the patch returns {@link IsoObjectIDProbe#ID_INVALID} ({@code
 * -1}). Existing callsites already handle this — {@code
 * VirtualZombieManager.createRealZombieAlways} rolls back the partial spawn, {@code
 * ReanimatedPlayers.addReanimatedPlayersToChunk} defers, and {@code IsoDeadBody.reanimate} returns
 * {@code null}. {@code IsoZombie.update}'s re-allocation branch assigns {@code -1} back to {@code
 * onlineId}, which is the same value the field already held, so the branch is a benign no-op.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers}. {@code IsoObjectID} is instantiated only
 * on the dedicated server JVM ({@code ServerMap.instance.zombieMap}, {@code
 * AnimalInstanceManager.AnimalMap}); no client load path exists.
 */
public class IsoObjectIDAllocateFixPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isoobjectidalloc.";

    static {
        IsoObjectIdPoolMetrics.ensureStarted();
    }

    public IsoObjectIDAllocateFixPatch() {
        super("zombie.network.IsoObjectID");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoObjectIDAllocateAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("allocateID")
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.returns(short.class))));
    }
}
