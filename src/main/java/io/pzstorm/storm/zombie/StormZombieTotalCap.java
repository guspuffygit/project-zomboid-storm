package io.pzstorm.storm.zombie;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.metrics.StormZombieCapMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.popman.NetworkZombiePacker;

/**
 * Server-wide ceiling on the number of real zombies, sourced from the {@code Storm.MaxTotalZombies}
 * sandbox option and enforced once per sweep from {@code
 * io.pzstorm.storm.advice.servertick.ServerTickAdvice}.
 *
 * <p>Vanilla has no global cap. Every ceiling PZ ships is local: {@code
 * ZombieConfig.ZombiesCountBeforeDelete} counts only the zombies streamed to a <em>single
 * connection</em> ({@code ZombieCountOptimiser.prepareZombiesForDeletion}), {@code
 * MaxZombiesPerChunk} bounds one 8×8 chunk, and the 300 in {@code
 * NetworkZombiePacker.getZombieData} is packet framing. The world total is therefore just "loaded
 * chunks around every player × population density" and grows without bound as players spread out.
 * That total, not any one player's view, drives the per-tick costs that scale linearly with it —
 * chiefly {@code NetworkZombiePacker.updateAuth()}, which walks the entire zombie list every tick
 * and re-scans every connection's players for each unowned zombie.
 *
 * <p>Eligibility mirrors vanilla's own cull predicate exactly, applied against <em>every</em>
 * connection instead of one: not a reanimated player, no target, outside (no room and no roof), and
 * beyond {@code (relevantRange - 2) * 10} tiles of every player on every connection. A zombie that
 * passes is one nobody can see, so the sweep needs no ordering heuristic on top.
 *
 * <p>The knob is a backstop, not a population control. Culled zombies re-enter the native
 * population manager's normal respawn schedule ({@code RespawnHours} / {@code RespawnMultiplier}),
 * so a cap set below what the population settings actually want produces a permanent tug-of-war
 * that shows up as a sustained {@code storm_zombies_total_cap_culled_total} rate — the signal to
 * lower {@code ZombieConfig.PopulationMultiplier} rather than the cap.
 */
public final class StormZombieTotalCap {

    public static final int MIN = 0;

    /**
     * Online IDs are allocated from a {@code short} pool ({@code zombie.network.IsoObjectID}), so a
     * cap above the address space could never be reached.
     */
    public static final int MAX = 32000;

    public static final int DEFAULT_MAX_TOTAL = 0;

    private static final long SWEEP_INTERVAL_MS =
            Integer.getInteger("storm.zombieTotalCap.sweepMs", 1000);

    private static final int MAX_CULLED_PER_SWEEP =
            Integer.getInteger("storm.zombieTotalCap.perSweep", 200);

    private static final AtomicInteger MAX_TOTAL =
            new AtomicInteger(clamp(Integer.getInteger("storm.zombieTotalCap", DEFAULT_MAX_TOTAL)));

    private static final List<IsoZombie> culled = new ArrayList<>();

    private static long lastSweepMs;

    private static boolean sweepFailureLogged;

    /** Rotates the scan origin so repeated sweeps do not always drain the same region. */
    private static int scanCursor;

    static {
        StormZombieCapMetrics.register();
    }

    private StormZombieTotalCap() {}

    /** Configured world-wide ceiling; {@code 0} when the cap is disabled. */
    public static int maxTotal() {
        return MAX_TOTAL.get();
    }

    public static boolean enabled() {
        return MAX_TOTAL.get() > MIN;
    }

    /**
     * Updates the world-wide ceiling (clamped to {@link #MIN}..{@link #MAX}), pushes it to the
     * Prometheus gauge, and returns the value actually applied. {@code 0} disables the cap.
     */
    public static int setMaxTotal(int requested) {
        int clamped = clamp(requested);
        MAX_TOTAL.set(clamped);
        StormPerformanceSandboxMetrics.setMaxTotalZombies(clamped);
        return clamped;
    }

    /**
     * Runs one sweep if the world is over the cap and the sweep interval has elapsed.
     *
     * <p>Called from the server tick hook, which sits at {@code
     * StatisticManager.getInstance().update(dif)} in {@code GameServer.main} — after {@code
     * statex.update()} has finished the whole world update (including {@code
     * MovingObjectUpdateScheduler.postupdate()}, where vanilla performs its own zombie deletions)
     * and after {@code ServerMap.instance.postupdate()}. Nothing is iterating the zombie list at
     * that point. The client-side delete notifications queued here drain at the next frame's {@code
     * ServerMap.postupdate()}, one tick later.
     */
    public static void onServerTick() {
        int cap = MAX_TOTAL.get();
        if (cap <= MIN || !GameServer.server) {
            return;
        }
        IsoWorld world = IsoWorld.instance;
        IsoCell cell = world == null ? null : world.getCell();
        if (cell == null) {
            return;
        }
        List<IsoZombie> zombies = cell.getZombieList();
        int total = zombies.size();
        if (total <= cap) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepMs = now;
        try {
            sweep(zombies, total, cap);
        } catch (Throwable t) {
            culled.clear();
            if (!sweepFailureLogged) {
                sweepFailureLogged = true;
                LOGGER.error(
                        "Storm: total-zombie cap sweep failed; the cap is inert until this is"
                                + " resolved (logged once)",
                        t);
            }
        }
    }

    private static void sweep(List<IsoZombie> zombies, int total, int cap) {
        collectEligible(zombies, total, Math.min(total - cap, MAX_CULLED_PER_SWEEP));
        if (culled.isEmpty()) {
            return;
        }
        int removed = culled.size();
        for (int i = 0; i < removed; i++) {
            IsoZombie zombie = culled.get(i);
            NetworkZombiePacker.getInstance().deleteZombie(zombie);
            zombie.removeFromWorld();
            zombie.removeFromSquare();
        }
        culled.clear();
        StormZombieCapMetrics.recordCulled(removed);
        LOGGER.debug(
                "Storm: total-zombie cap culled {} zombies ({} live, cap {})", removed, total, cap);
    }

    private static void collectEligible(List<IsoZombie> zombies, int total, int budget) {
        culled.clear();
        int start = Math.floorMod(scanCursor, total);
        int scanned = 0;
        while (scanned < total && culled.size() < budget) {
            IsoZombie zombie = zombies.get((start + scanned) % total);
            scanned++;
            if (zombie != null && isEligible(zombie)) {
                culled.add(zombie);
            }
        }
        scanCursor = start + scanned;
    }

    private static boolean isEligible(IsoZombie zombie) {
        return !zombie.isReanimatedPlayer()
                && zombie.getTarget() == null
                && isOutside(zombie)
                && isUnnoticedByEveryone(zombie);
    }

    private static boolean isOutside(IsoZombie zombie) {
        IsoGridSquare square = zombie.getCurrentSquare();
        return square == null || !square.isInARoom() && !square.haveRoof;
    }

    /**
     * Vanilla's {@code canBeDeletedUnnoticed} widened from one connection to all of them. Returns
     * {@code false} when the engine is unavailable — without connections there is no way to prove
     * nobody is looking, and a backstop must never delete on an unproven assumption.
     */
    private static boolean isUnnoticedByEveryone(IsoZombie zombie) {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return false;
        }
        List<UdpConnection> connections = engine.connections;
        for (int i = 0; i < connections.size(); i++) {
            UdpConnection connection = connections.get(i);
            if (connection == null) {
                continue;
            }
            float relevantDistance = (connection.getRelevantRange() - 2) * 10.0F;
            float relevantDistanceSquared = relevantDistance * relevantDistance;
            for (IsoPlayer player : connection.players) {
                if (player == null) {
                    continue;
                }
                float distance =
                        IsoUtils.DistanceToSquared(
                                zombie.getX(), zombie.getY(), player.getX(), player.getY());
                if (distance <= relevantDistanceSquared) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int clamp(int requested) {
        if (requested < MIN) {
            return MIN;
        }
        if (requested > MAX) {
            return MAX;
        }
        return requested;
    }
}
