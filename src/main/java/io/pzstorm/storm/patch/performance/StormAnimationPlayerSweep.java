package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.EveryOneMinuteEvent;
import io.pzstorm.storm.metrics.StormAnimationPlayerSweepMetrics;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.statistics.counters.PoolCounter;
import zombie.util.Pool;

/**
 * Reclaims {@code AnimationPlayer} instances stranded in the pool by characters that left the world
 * and were never released.
 *
 * <p>{@code IsoGameCharacter.animPlayer} is pool-allocated lazily by {@code getAnimationPlayer()}
 * and returned only by {@code releaseAnimationPlayer()}. Neither {@code
 * IsoGameCharacter.removeFromWorld()} nor any subclass override calls it, so removal alone never
 * gives the player back. Vanilla instead relies on deferred-removal lists, and only one of them
 * closes the loop:
 *
 * <ul>
 *   <li><b>Zombies</b> — {@code VirtualZombieManager.update()} moves an entry to the reuse queue 5s
 *       after removal and {@code IsoZombie.resetForReuse()} calls {@code releaseAnimationPlayer()}.
 *       Correct, and the only path that is.
 *   <li><b>Animals</b> — {@code AnimalPopulationManager.update()} expires the entry after the same
 *       5s but only stops the emitter. The {@code IsoAnimal} is then referenced by nothing, while
 *       its animation player stays in the pool's in-use set forever.
 *   <li><b>Players</b> — {@code IsoPlayer.removeFromWorld()} adds to {@code
 *       IsoPlayer.RecentlyRemoved}, but the drain ({@code IsoPlayer.UpdateRemovedEmitters}) is
 *       called under {@code if (!GameServer.server)}. On a dedicated server that list is never
 *       drained at all, so it grows one entry per removal and nothing ever releases the player.
 *   <li><b>Dead characters that never ran {@code removeFromWorld()}</b> — observed in production
 *       for both animals and zombies: dead, no square, absent from the cell, and {@code
 *       removedFromWorldMs} still 0. Nothing will bring them back and nothing will release them.
 * </ul>
 *
 * <p>The stranded players are not garbage either: {@code Pool.PoolStacks.inUse} holds a strong
 * reference, which transitively pins the character and — through {@code
 * AnimationPlayer.boneTransforms} — its {@code AnimatorsBoneTransform} objects at roughly 34:1.
 * That second pool is the one whose in-use table degenerates into the probe-length cliff {@link
 * StormPoolCompaction} repairs; this sweep removes the growth that feeds it.
 *
 * <p>Measured on a production server at 20 minutes uptime with 34 players: 2055 animation players
 * in use, 2036 of them held by characters actually in the world. Of the remaining 19, three
 * belonged to {@code IsoPlayer}s gone from the world (oldest 12.8 minutes past removal) and sixteen
 * to dead animals and zombies carrying no removal stamp at all.
 *
 * <p><b>How age is measured.</b> {@code removedFromWorldMs} is authoritative when it is set, but
 * the last bucket above never gets one, so the sweep stamps its own first-seen time the first sweep
 * it observes a character out of the world and ages from that instead. A character that returns to
 * the cell drops out of the table and starts over, so the grace period always measures an
 * uninterrupted absence.
 *
 * <p><b>Why the release goes through the character.</b> Releasing straight to the pool would leave
 * {@code character.animPlayer} pointing at a freed object, and {@code getAnimationPlayer()} only
 * re-allocates when that field is {@code null} — the next caller would get a player that the pool
 * had already handed to someone else. {@code releaseAnimationPlayer()} nulls the field as it
 * releases, so a character that comes back re-allocates cleanly. That is exactly what vanilla does
 * for a reused zombie.
 *
 * <p><b>Why a sweep and not a patch on the removal paths.</b> The decision is made from observed
 * state — the character is absent from the cell and has been for longer than the grace period —
 * rather than from an assumption at a call site about whether it will return. An animal can be
 * virtualized and re-realized as the same object, so a call-site release has to be right about the
 * future; this cannot fire for a character that is in the world, and the largest bucket has no call
 * site to patch in the first place. It also covers every holder class at once, including any added
 * by a future game update, with no bytecode to re-validate. Running on {@code EveryOneMinuteEvent}
 * puts it on the main thread, the only thread that mutates the cell membership it reads and the
 * {@code RecentlyRemoved} list it drains.
 *
 * <p>Server-only, and any throwable latches the sweep off permanently.
 */
public final class StormAnimationPlayerSweep {

    /**
     * Storm-observed absence clock for characters vanilla never stamped. Rebuilt every sweep, so a
     * character that returns to the world loses its entry and starts the grace period over.
     */
    private static Map<IsoGameCharacter, Long> firstSeenOutOfWorld = new IdentityHashMap<>();

    private static Field poolField;

    private static Field animPlayerField;

    private static Field counterStacksField;

    private static Field inUseField;

    private static Field lockField;

    private static Field recentlyRemovedField;

    private static boolean resolved;

    private static boolean failed;

    private static long lastSweepNanos;

    private static boolean everSwept;

    private StormAnimationPlayerSweep() {}

    /**
     * How long a character has been continuously out of the world. Prefers vanilla's removal stamp
     * and falls back to the first sweep that saw it absent. Negative ages — a stamp in the future,
     * or a clock that moved backwards — are treated as zero rather than as long expired.
     */
    public static long resolveAgeMs(
            long removedFromWorldMs, long firstSeenOutOfWorldMs, long nowMs) {
        long since = removedFromWorldMs > 0 ? removedFromWorldMs : firstSeenOutOfWorldMs;
        long age = nowMs - since;
        return age > 0 ? age : 0;
    }

    public static boolean isReclaimable(boolean inWorld, long ageMs, long graceMs) {
        return !inWorld && ageMs >= graceMs;
    }

    @SubscribeEvent
    public static void onEveryOneMinute(EveryOneMinuteEvent event) {
        sweep();
    }

    public static void sweep() {
        if (failed || !StormAnimationPlayerSweepConfig.isEnabled() || !GameServer.server) {
            return;
        }
        if (!intervalElapsed()) {
            return;
        }
        try {
            lastSweepNanos = System.nanoTime();
            everSwept = true;

            IsoWorld world = IsoWorld.instance;
            IsoCell cell = world == null ? null : world.getCell();
            if (cell == null) {
                return;
            }
            resolve();

            long now = System.currentTimeMillis();
            long graceMs = StormAnimationPlayerSweepConfig.graceMs();
            reclaimOrphaned(cell, now, graceMs);
            drainRecentlyRemoved(cell, now, graceMs);
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("StormAnimationPlayerSweep failed; disabling for this session", t);
        }
    }

    private static void reclaimOrphaned(IsoCell cell, long nowMs, long graceMs) throws Exception {
        Object[] snapshot = snapshotInUse();
        if (snapshot == null) {
            return;
        }
        StormAnimationPlayerSweepMetrics.setInUse(snapshot.length);

        Map<IsoGameCharacter, Long> nextFirstSeen = new IdentityHashMap<>();
        Map<String, Integer> reclaimed = new HashMap<>();
        int stranded = 0;
        for (Object o : snapshot) {
            if (!(o instanceof AnimationPlayer)) {
                continue;
            }
            AnimationPlayer animPlayer = (AnimationPlayer) o;
            IsoGameCharacter chr = animPlayer.getIsoGameCharacter();
            if (chr == null) {
                continue;
            }
            if (animPlayerField.get(chr) != animPlayer) {
                stranded++;
                continue;
            }
            if (inWorld(cell, chr)) {
                continue;
            }
            Long firstSeen = firstSeenOutOfWorld.get(chr);
            if (firstSeen == null) {
                firstSeen = nowMs;
            }
            nextFirstSeen.put(chr, firstSeen);

            long ageMs = resolveAgeMs(chr.removedFromWorldMs, firstSeen, nowMs);
            if (!isReclaimable(false, ageMs, graceMs)) {
                continue;
            }
            String holder = chr.getClass().getSimpleName();
            // Vanilla pairs every release with an animator reset (IsoZombie.resetForReuse,
            // VirtualZombieManager.Reset, IsoPlayer.Reset): the layers' LiveAnimNodes hold the
            // tracks that releasing the player returns to the AnimationTrack pool.
            chr.getAdvancedAnimator().reset();
            chr.releaseAnimationPlayer();
            nextFirstSeen.remove(chr);
            reclaimed.merge(holder, 1, Integer::sum);
        }
        firstSeenOutOfWorld = nextFirstSeen;
        StormAnimationPlayerSweepMetrics.setStranded(stranded);
        StormAnimationPlayerSweepMetrics.setOutOfWorld(nextFirstSeen.size());

        if (!reclaimed.isEmpty()) {
            int total = 0;
            for (Map.Entry<String, Integer> e : reclaimed.entrySet()) {
                StormAnimationPlayerSweepMetrics.recordReclaimed(e.getKey(), e.getValue());
                total += e.getValue();
            }
            LOGGER.info(
                    "StormAnimationPlayerSweep: reclaimed {} orphaned animation players of {} in"
                            + " use — {}",
                    total,
                    snapshot.length,
                    reclaimed);
        }
    }

    /**
     * Collects every checked-out player across every thread's stacks.
     *
     * <p>{@code Pool.getPoolStacks()} is a {@code ThreadLocal}, so reading it from the sweeping
     * thread would only ever see that thread's own set — and would silently mint an empty one if
     * the players were allocated elsewhere. {@code PoolCounter} keeps the set of every {@code
     * PoolStacks} the pool has handed out, and {@code Pool.alloc} registers with it
     * unconditionally, so the counter exists as soon as anything has been allocated and a null
     * counter means there is nothing to sweep. Releasing across threads is safe: {@code
     * Pool.release} takes the lock of the stacks the item was allocated from, not the caller's.
     */
    private static Object[] snapshotInUse() throws Exception {
        Pool<?> pool = (Pool<?>) poolField.get(null);
        PoolCounter counter = pool.getMonitoringPoolCounter();
        if (counter == null) {
            return null;
        }
        Set<?> allStacks = (Set<?>) counterStacksField.get(counter);
        List<?> stacksList;
        synchronized (allStacks) {
            stacksList = new ArrayList<>(allStacks);
        }

        List<Object> players = new ArrayList<>();
        for (Object stacks : stacksList) {
            synchronized (lockField.get(stacks)) {
                players.addAll((Set<?>) inUseField.get(stacks));
            }
        }
        return players.toArray();
    }

    /**
     * Vanilla's drain for this list runs only on the client, so on a dedicated server every removed
     * player stays in it for the life of the process — pinning the character and making the {@code
     * contains} check in {@code removeFromWorld} linear in the number of removals so far. The
     * emitter work vanilla does here is deliberately skipped: the client-side gate means a
     * dedicated server has never run it.
     */
    private static void drainRecentlyRemoved(IsoCell cell, long nowMs, long graceMs)
            throws Exception {
        List<?> recentlyRemoved = (List<?>) recentlyRemovedField.get(null);
        int drained = 0;
        for (int i = recentlyRemoved.size() - 1; i >= 0; i--) {
            Object o = recentlyRemoved.get(i);
            if (!(o instanceof IsoGameCharacter)) {
                recentlyRemoved.remove(i);
                drained++;
                continue;
            }
            IsoGameCharacter chr = (IsoGameCharacter) o;
            long ageMs = resolveAgeMs(chr.removedFromWorldMs, nowMs, nowMs);
            if (inWorld(cell, chr) || isReclaimable(false, ageMs, graceMs)) {
                recentlyRemoved.remove(i);
                drained++;
            }
        }
        StormAnimationPlayerSweepMetrics.setRecentlyRemoved(recentlyRemoved.size());
        if (drained > 0) {
            StormAnimationPlayerSweepMetrics.recordRecentlyRemovedDrained(drained);
            LOGGER.debug(
                    "StormAnimationPlayerSweep: drained {} entries from IsoPlayer.RecentlyRemoved,"
                            + " {} left",
                    drained,
                    recentlyRemoved.size());
        }
    }

    /**
     * Warmed animals are parked out of their square but are deliberately kept in {@code
     * objectList}; the extra {@link StormCellWarmer#isWarmedAnimal} check makes the sweep
     * independent of that invariant so restore never has to re-allocate a player.
     */
    private static boolean inWorld(IsoCell cell, IsoGameCharacter chr) {
        return cell.getObjectList().contains(chr)
                || cell.getAddList().contains(chr)
                || StormCellWarmer.isWarmedAnimal(chr);
    }

    /**
     * Resolved on the first sweep rather than in a static initializer so that registering this
     * handler at launch does not pull {@code AnimationPlayer} and {@code IsoPlayer} — and their
     * static initializers — into the JVM before the game has started.
     */
    private static void resolve() throws NoSuchFieldException {
        if (resolved) {
            return;
        }
        poolField = AnimationPlayer.class.getDeclaredField("s_pool");
        poolField.setAccessible(true);
        animPlayerField = IsoGameCharacter.class.getDeclaredField("animPlayer");
        animPlayerField.setAccessible(true);
        counterStacksField = PoolCounter.class.getDeclaredField("poolStacks");
        counterStacksField.setAccessible(true);
        inUseField = Pool.PoolStacks.class.getDeclaredField("inUse");
        inUseField.setAccessible(true);
        lockField = Pool.PoolStacks.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        recentlyRemovedField =
                zombie.characters.IsoPlayer.class.getDeclaredField("RecentlyRemoved");
        recentlyRemovedField.setAccessible(true);
        resolved = true;
    }

    private static boolean intervalElapsed() {
        if (!everSwept) {
            return true;
        }
        long elapsedMs = (System.nanoTime() - lastSweepNanos) / 1_000_000L;
        return elapsedMs >= StormAnimationPlayerSweepConfig.intervalMs();
    }
}
