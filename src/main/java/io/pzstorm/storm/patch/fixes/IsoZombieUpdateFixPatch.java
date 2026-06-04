package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.IsoObjectIdPoolMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Attaches an exit advice to {@code zombie.characters.IsoZombie.update()} that re-establishes the
 * invariant {@code onlineId != -1 ⇒ ServerMap.instance.zombieMap.get(onlineId) == this} after every
 * tick. The actual fix logic lives in {@link IsoZombieMapInvariant}.
 *
 * <h2>The bug this patches</h2>
 *
 * <p>{@code IsoZombie.updateInternal()} (around lines 3328-3329) contains:
 *
 * <pre>{@code
 * if (GameServer.server && this.onlineId == -1) {
 *     this.onlineId = ServerMap.instance.getUniqueZombieId();
 *     // ❌ never calls zombieMap.put(this.onlineId, this)
 * }
 * }</pre>
 *
 * <p>Peer call sites — {@code VirtualZombieManager.createRealZombieAlways}, {@code
 * ReanimatedPlayers.addReanimatedPlayersToChunk}, {@code IsoDeadBody.reanimate} — all do allocate +
 * put. Only this path is broken.
 *
 * <h2>Why it matters operationally</h2>
 *
 * <p>The dominant trigger is not reanimation, it's chunk load. {@code IsoZombie.load} adds the
 * deserialized zombie to {@code cell.zombieList} (line 1231) with {@code onlineId == -1} — the
 * field is not persisted by {@code save()}. The next tick, the re-allocate branch above runs and
 * leaves the zombie orphaned in the cell list but absent from {@code zombieMap}.
 *
 * <p>Server-side broadcasts (e.g. {@code NetworkZombiePacker}) iterate {@code cell.zombieList}, not
 * {@code zombieMap}, so the orphan is still broadcast under its newly-allocated id. The receiving
 * client's {@code IDToZombieMap.get(packet.id)} miss path falls through to {@code
 * VirtualZombieManager.createRealZombieAlways}, spawning a brand-new client zombie alongside the
 * one already on screen — the visible "mitosis". The duplicate persists until stale-zombie cleanup
 * (~800 ms).
 *
 * <h2>Fix shape</h2>
 *
 * <p>The advice is an {@code @Advice.OnMethodExit} on the public {@code update()} wrapper (not
 * {@code updateInternal()}), so it runs once per tick regardless of which internal branch took. The
 * check is idempotent — a correctly-mapped zombie is a no-op, costing one map lookup per zombie per
 * tick.
 *
 * <p>Three outcomes (see {@link IsoZombieMapInvariant.Action}):
 *
 * <ul>
 *   <li>Already mapped → {@code NONE}.
 *   <li>Slot empty → {@code MISSING_PUT}: put {@code (id, self)} into the map. This is the path
 *       that heals chunk-loaded zombies and any earlier orphan from a session that ran without the
 *       fix.
 *   <li>Slot held by another zombie → {@code COLLISION}: reset {@code onlineId} to {@code -1}. The
 *       next tick re-allocates via the probe-for-free {@code allocateID} (already patched by {@link
 *       IsoObjectIDAllocateFixPatch}), which by construction returns a slot that nobody else holds.
 *       The {@code MISSING_PUT} path then fills the map.
 * </ul>
 *
 * <p>Both healing paths are exported as Prometheus counters (see {@link IsoObjectIdPoolMetrics}).
 * {@code MISSING_PUT} runs roughly once per allocation in steady state, {@code COLLISION} should
 * stay near zero.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers}. {@code IsoZombie} also exists on the
 * client, but the buggy branch is guarded by {@code GameServer.server}, so the advice is a no-op
 * there in any case — gating keeps the class transform itself off the client per the project's
 * absolute "no new client patches" rule.
 */
public class IsoZombieUpdateFixPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isozombieupdate.";

    static {
        IsoObjectIdPoolMetrics.ensureStarted();
    }

    public IsoZombieUpdateFixPatch() {
        super("zombie.characters.IsoZombie");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoZombieUpdateAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.returns(void.class))));
    }
}
