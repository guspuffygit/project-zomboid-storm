package io.pzstorm.storm.entity;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.metrics.UsingPlayerRegistryMetrics;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import zombie.characters.IsoPlayer;
import zombie.entity.GameEntity;
import zombie.iso.IsoObject;

/**
 * Server-only replacement for {@code UsingPlayerUpdateSystem.update()}'s full iso-bucket scan,
 * wired in by {@code UsingPlayerSweepFastPathPatch} (sweep) and {@code
 * GameEntityUsingPlayerTrackingPatch} (registry maintenance).
 *
 * <p>Vanilla iterates <em>every</em> entity in the engine's iso-object bucket each tick (123k+ on a
 * busy server) just to find the handful whose {@code usingPlayer} is non-null (roughly the number
 * of players with a crafting/entity UI open) and clear it once the player walks away, changes Z
 * level, or dies. This registry tracks exactly that handful: {@code GameEntity.setUsingPlayer} and
 * the direct-field-write server branch of {@code GameEntity.receiveUpdateUsingPlayer} are advised
 * to add/remove entries, and the per-tick sweep iterates only the registry, replicating the vanilla
 * per-entity semantics through the real {@code setUsingPlayer(null)} so vanilla side effects (the
 * {@code sendUpdateUsingPlayer} client broadcast) still fire.
 *
 * <p>Registry membership mirrors vanilla's sweep domain: only {@code IsoObject}-derived entities
 * are registered, because the vanilla system iterates {@code Engine.getIsoObjectBucket()} whose
 * membership predicate is {@code entity instanceof IsoObject}. A non-IsoObject entity with a
 * non-null {@code usingPlayer} is never cleared by vanilla, so Storm must not clear it either.
 * ({@code MetaEntity} needs no special case — its {@code setUsingPlayer}/{@code getUsingPlayer}
 * overrides are no-op/null, so it can never surface here.)
 *
 * <p>Maintenance is <em>unconditional</em> on the server — not gated by the sandbox option or the
 * failure latch — so the registry is complete from boot and {@code Storm.UsingPlayerSweepFastPath}
 * can be flipped on live without missing entities registered while it was off.
 *
 * <p>Leak handling: entries whose entity fails {@code isValidEngineEntity()} (removed from the
 * engine, e.g. on chunk unload) are evicted during the sweep without touching {@code usingPlayer} —
 * identical world state to vanilla, which merely skips them. The {@code GameEntity} object is
 * recreated on chunk reload (and {@code usingPlayer} is not disk-persisted), so an evicted entry
 * cannot legitimately come back; letting it linger would pin the unloaded entity graph forever.
 * Entries whose {@code usingPlayer} went null through a path outside the setter (e.g. {@code
 * GameEntity.reset()}'s direct field write during pool release) are likewise evicted — vanilla
 * skips null entries, and only {@code setUsingPlayer(non-null)} can make one relevant again, which
 * re-registers it.
 *
 * <p>Concurrency: the sweep runs on the server main thread ({@code GameEntityManager} engine
 * update), and so do all known maintenance call sites, but mutation is synchronized on an internal
 * lock anyway (cheap — uncontended, per-tick) so an off-main-thread caller (debug eval, future
 * code) cannot corrupt the {@code IdentityHashMap}. The sweep iterates a snapshot array, never the
 * live set, because {@code entity.setUsingPlayer(null)} re-enters the registry through the
 * maintenance advice mid-sweep — iterating the set directly would throw {@code
 * ConcurrentModificationException}.
 */
public final class UsingPlayerRegistry {

    /** Default for {@code Storm.UsingPlayerSweepFastPath}: registry sweep on. */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * Kill switch, driven by the {@code Storm.UsingPlayerSweepFastPath} sandbox option through
     * {@link #setEnabled(boolean)}. Volatile because the sandbox applier may push updates from
     * outside the main thread; the per-sweep read is a single volatile load.
     */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /**
     * Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of the sweep.
     * Registry maintenance keeps running after the latch trips (harmless, and it keeps the registry
     * complete in case the latch cause is ever fixed live).
     */
    private static boolean failed;

    private static final Object LOCK = new Object();

    /**
     * Identity-based membership: {@code GameEntity} does not override {@code equals}/{@code
     * hashCode}, but an {@code IdentityHashMap} backing makes the invariant explicit and immune to
     * future overrides.
     */
    private static final Set<GameEntity> REGISTRY =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private UsingPlayerRegistry() {}

    /**
     * Applies the {@code Storm.UsingPlayerSweepFastPath} sandbox option ({@code false} = vanilla
     * full-bucket scan, {@code true} = registry sweep) and pushes the applied value to the
     * Prometheus gauge. Single mutation point — sandbox apply and tests both funnel through here.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setUsingPlayerSweepFastPath(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Current registry size; scrape-time source for {@code storm_using_player_registry_size}. */
    public static int size() {
        synchronized (LOCK) {
            return REGISTRY.size();
        }
    }

    /**
     * Maintenance hook for {@code GameEntity.setUsingPlayer(IsoPlayer)} ({@code
     * GameEntitySetUsingPlayerAdvice}). Idempotent, so firing on every call — including vanilla's
     * same-value no-op path — is safe.
     *
     * @param entityObj the entity ({@code @Advice.This}; typed {@code Object} so the advice never
     *     references the transform target)
     * @param playerObj the player being set, or {@code null} to clear
     */
    public static void onSetUsingPlayer(Object entityObj, Object playerObj) {
        GameEntity entity = (GameEntity) entityObj;
        if (playerObj == null) {
            synchronized (LOCK) {
                REGISTRY.remove(entity);
            }
        } else if (entity instanceof IsoObject) {
            synchronized (LOCK) {
                REGISTRY.add(entity);
            }
        }
    }

    /**
     * Maintenance hook for {@code GameEntity.receiveUpdateUsingPlayer} ({@code
     * GameEntityReceiveUpdateUsingPlayerAdvice}) — the server branch of that packet handler writes
     * the {@code usingPlayer} field directly, bypassing the setter, and it is the <em>main</em>
     * registration path on a dedicated server (client UIs call {@code setUsingPlayer} on the client
     * JVM and the server only ever sees the resulting packet). Re-syncs registry membership from
     * the entity's post-call state.
     */
    public static void syncFromEntity(Object entityObj) {
        GameEntity entity = (GameEntity) entityObj;
        onSetUsingPlayer(entity, entity.getUsingPlayer());
    }

    /**
     * Runs the registry-backed replacement of {@code UsingPlayerUpdateSystem.update()}. Per-entity
     * semantics replicate vanilla exactly: skip (and evict) entries failing {@code
     * isValidEngineEntity()} or holding a null {@code usingPlayer}; otherwise clear {@code
     * usingPlayer} through the real setter when the player left the ±10.0F X/Y box, is on a
     * different Z, or is dead. Vanilla's {@code !GameClient.client} gate is subsumed by the
     * advice's {@code GameServer.server} guard (this never runs on a client JVM).
     *
     * @return {@code true} if the optimized sweep ran (the advice skips the vanilla body); {@code
     *     false} to fall through to vanilla (kill switch off or failure latch tripped)
     */
    public static boolean runSweep() {
        if (failed || !enabled) {
            UsingPlayerRegistryMetrics.recordVanilla();
            return false;
        }
        try {
            GameEntity[] snapshot;
            synchronized (LOCK) {
                snapshot = REGISTRY.toArray(new GameEntity[0]);
            }
            for (GameEntity entity : snapshot) {
                if (!entity.isValidEngineEntity()) {
                    evict(entity);
                    continue;
                }
                IsoPlayer usingPlayer = entity.getUsingPlayer();
                if (usingPlayer == null) {
                    evict(entity);
                    continue;
                }
                if (usingPlayer.getX() < entity.getX() - 10.0F
                        || usingPlayer.getX() > entity.getX() + 10.0F
                        || usingPlayer.getY() < entity.getY() - 10.0F
                        || usingPlayer.getY() > entity.getY() + 10.0F
                        || usingPlayer.getZ() != entity.getZ()
                        || usingPlayer.isDead()) {
                    // Through the real setter so vanilla side effects (sendUpdateUsingPlayer)
                    // and the maintenance advice (registry removal) both fire.
                    entity.setUsingPlayer(null);
                }
            }
            UsingPlayerRegistryMetrics.recordOptimized();
            return true;
        } catch (Throwable t) {
            failed = true;
            StormLogger.LOGGER.error(
                    "UsingPlayerRegistry sweep failed — reverting to vanilla"
                            + " UsingPlayerUpdateSystem.update (registry maintenance stays live)",
                    t);
            UsingPlayerRegistryMetrics.recordVanilla();
            return false;
        }
    }

    private static void evict(GameEntity entity) {
        synchronized (LOCK) {
            REGISTRY.remove(entity);
        }
    }
}
