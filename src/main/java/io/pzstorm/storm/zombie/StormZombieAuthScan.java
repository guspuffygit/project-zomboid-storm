package io.pzstorm.storm.zombie;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.metrics.ZombieAuthScanMetrics;
import java.util.Arrays;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.component.NetworkZombieComponent;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;
import zombie.network.ServerOptions;
import zombie.popman.NetworkZombieManager;

/**
 * Snapshot-backed replacement for the ownership scan in {@code
 * NetworkZombieManager.updateAuth(IsoZombie)}, active only inside a {@code
 * NetworkZombiePacker.updateAuth()} pass (the per-tick loop over the whole zombie list).
 *
 * <p>Vanilla, per zombie per scan, walks {@code GameServer.udpEngine.connections} and each
 * connection's {@code players} array through virtual calls, probes the delayed-disconnect {@code
 * HashMap} once per connection, and reaches the zombie's owner through {@code
 * IsoZombie.getOwner()}/{@code getOwnerPlayer()} — each a {@code tryGetECSComponent} probe into the
 * (treeified, at production entity counts) ECS component {@code HashMap} — up to six times per
 * zombie counting {@code moveZombie}. At 112 connections and 7,000 zombies this scan was 6.7% of
 * the server main thread (live profile, ATF 2026-08-24), and it is the only tick cost that grows
 * with the <em>product</em> of player count and zombie count.
 *
 * <p>This fast path applies three transformations, each argued outcome-identical below:
 *
 * <ol>
 *   <li><b>Per-pass snapshot of the connection/player rows.</b> {@link #beginPass()} flattens
 *       (connection, player) pairs into primitive-indexed arrays — position, {@code (relevantRange
 *       - 2) * 8} bound, alive/dead flags — filtering delayed-disconnect connections and null
 *       player slots once per pass instead of once per zombie. Everything the snapshot caches is
 *       stable within one packer pass: it runs on the server main thread, and nothing reachable
 *       from {@code updateAuth}/{@code moveZombie} mutates connection membership, player positions,
 *       alive/dead state, relevant ranges, or the delayed-disconnect map. The snapshot preserves
 *       vanilla's nested iteration order (connections outer, players inner), so
 *       first-match/overwrite tie-breaks are identical.
 *   <li><b>One ECS component probe per zombie.</b> The {@code NetworkZombieComponent} is fetched
 *       once and its {@code authOwner}/{@code ownerPlayer} read directly — exactly what {@code
 *       getOwner()}/{@code getOwnerPlayer()} return, including the component-missing case (both
 *       yield {@code null}, and {@code moveZombie}'s setters no-op just as vanilla's would).
 *   <li><b>Skip of provably no-op {@code moveZombie} calls.</b> Vanilla calls {@code moveZombie}
 *       unconditionally at scan end. For a live zombie whose winning connection equals its current
 *       owner, {@code moveZombie} mutates nothing — the vehicle-driver reassignment is the only
 *       code before its {@code getOwner() != to} guard, so the call is skipped only when {@code
 *       player == null || player.getVehicle() == null} also holds (note vanilla does <em>not</em>
 *       update {@code ownerPlayer} when the connection is unchanged). Dead zombies always go
 *       through {@code moveZombie} — its dead-path side effects ({@code die()}, owner clear) must
 *       run.
 * </ol>
 *
 * <p>The rare branches are kept verbatim on live game state, not the snapshot: the {@code
 * switchZombiesOwnershipEachUpdate} server option routes the whole pass to vanilla; the
 * grapple/target early-outs call {@code GameServer.getConnectionFromPlayer} and {@code moveZombie}
 * exactly as vanilla; the reanimated-corpse sweep uses the snapshot's dead rows (at most one row in
 * the world can match a given zombie's corpse, so overwrite order cannot diverge).
 *
 * <p>The 2-second gate compares against the pass-start {@code System.currentTimeMillis()} instead
 * of a per-zombie read; a zombie on the boundary re-scans at most one tick later than vanilla.
 *
 * <p>Outside a packer pass (e.g. {@code clearTargetAuth} on player disconnect) the advice falls
 * through to the untouched vanilla body. Kill switch: the {@code Storm.ZombieAuthFastPath} sandbox
 * option (live-appliable); permanent revert to vanilla if the fast path ever throws. Composes with
 * {@code Storm.ZombieAuthTickInterval}: the stride advice is woven outermost and still skips
 * off-phase unowned zombies before this code runs.
 *
 * <p>Single-threaded by design: every entry point runs on the server main thread inside {@code
 * NetworkZombiePacker.postupdate()}, so the snapshot arrays and tallies need no synchronization.
 */
public final class StormZombieAuthScan {

    /** Default for {@code Storm.ZombieAuthFastPath}: fast path on. */
    public static final boolean DEFAULT_ENABLED = true;

    /** Golden-ratio hysteresis from vanilla — a new owner must be this factor closer. */
    private static final float OWNER_SWITCH_HYSTERESIS = 1.618034F;

    private static final long OWNER_RESCAN_GATE_MILLIS = 2000L;

    /**
     * Kill switch, driven by the {@code Storm.ZombieAuthFastPath} sandbox option through {@link
     * #setEnabled(boolean)}. Volatile because the sandbox applier may push updates from outside the
     * main thread; the per-call read is a single volatile load.
     */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /** Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of the body. */
    private static boolean failed;

    /** True only between {@link #beginPass()} and {@link #endPass()} with a valid snapshot. */
    private static boolean passActive;

    private static long passNow;

    // Flattened (connection, player) rows in vanilla's nested iteration order. Delayed-disconnect
    // connections and null player slots are excluded at build time — both filters are stable
    // within a pass (see class doc item 1).
    private static int rowCount;
    private static UdpConnection[] rowConn = new UdpConnection[64];
    private static IsoPlayer[] rowPlayer = new IsoPlayer[64];
    private static float[] rowX = new float[64];
    private static float[] rowY = new float[64];
    private static float[] rowRange8 = new float[64];
    private static boolean[] rowAlive = new boolean[64];
    private static boolean[] rowDead = new boolean[64];

    private StormZombieAuthScan() {}

    /**
     * Applies the {@code Storm.ZombieAuthFastPath} sandbox option ({@code false} = vanilla scan,
     * {@code true} = snapshot-backed scan) and pushes the applied value to the Prometheus gauge.
     * Single mutation point — sandbox apply and tests both funnel through here.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setZombieAuthFastPath(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Builds the per-pass snapshot. Called at the entry of {@code NetworkZombiePacker.updateAuth()}
     * on the server main thread. Leaves the pass inactive (vanilla per-zombie bodies run) when the
     * kill switch is off, the failure latch is set, the engine is not up yet, or the
     * rotate-ownership server option is on (that mode is routed wholesale to vanilla).
     */
    public static void beginPass() {
        if (failed || !enabled || !GameServer.server || GameServer.udpEngine == null) {
            ZombieAuthScanMetrics.vanillaPasses++;
            return;
        }
        try {
            if (ServerOptions.getInstance().switchZombiesOwnershipEachUpdate.getValue()
                    && GameServer.getPlayerCount() > 1) {
                ZombieAuthScanMetrics.vanillaPasses++;
                return;
            }
            List<UdpConnection> connections = GameServer.udpEngine.connections;
            int rows = 0;
            for (int ci = 0; ci < connections.size(); ci++) {
                UdpConnection c = connections.get(ci);
                if (c == null || GameServer.isDelayedDisconnect(c)) {
                    continue;
                }
                // Exactly vanilla's bound: (byte range - 2) as int, widened to float, times 8.
                float range8 = (c.getRelevantRange() - 2) * 8.0F;
                IsoPlayer[] players = c.players;
                for (int pi = 0; pi < players.length; pi++) {
                    IsoPlayer p = players[pi];
                    if (p == null) {
                        continue;
                    }
                    if (rows == rowConn.length) {
                        grow();
                    }
                    rowConn[rows] = c;
                    rowPlayer[rows] = p;
                    rowX[rows] = p.getX();
                    rowY[rows] = p.getY();
                    rowRange8[rows] = range8;
                    rowAlive[rows] = p.isAlive();
                    rowDead[rows] = p.isDead();
                    rows++;
                }
            }
            rowCount = rows;
            passNow = System.currentTimeMillis();
            passActive = true;
            ZombieAuthScanMetrics.optimizedPasses++;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Deactivates the pass and drops snapshot object references. */
    public static void endPass() {
        passActive = false;
        Arrays.fill(rowConn, 0, rowCount, null);
        Arrays.fill(rowPlayer, 0, rowCount, null);
        rowCount = 0;
    }

    /**
     * Runs the snapshot-backed equivalent of {@code NetworkZombieManager.updateAuth(zombie)}.
     *
     * @param managerObj the {@code NetworkZombieManager} ({@code @Advice.This}; typed {@code
     *     Object} so the advice never references the transform target)
     * @return {@code true} if the fast path handled the zombie (the advice skips the vanilla body);
     *     {@code false} to fall through to vanilla (no active pass, kill switch off, or failure
     *     latch tripped)
     */
    public static boolean updateAuthFast(Object managerObj, IsoZombie zombie) {
        if (!passActive) {
            return false;
        }
        try {
            runFor((NetworkZombieManager) managerObj, zombie);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /**
     * The snapshot-backed equivalent of the vanilla {@code updateAuth} body (rotate-ownership mode
     * excluded — {@link #beginPass()} routes it to vanilla). Every kept expression mirrors its
     * vanilla counterpart in order; comments name what vanilla does at each seam.
     */
    private static void runFor(NetworkZombieManager manager, IsoZombie zombie) {
        // Single ECS probe replacing every getOwner()/getOwnerPlayer() on this path (class doc
        // item 2). A missing component reads as (null, null), exactly like the vanilla accessors.
        NetworkZombieComponent comp = zombie.tryGetECSComponent(NetworkZombieComponent.class);
        UdpConnection owner = comp != null ? comp.getAuthOwner() : null;

        // Vanilla gate: rescan every 2s, or continuously while unowned. passNow is hoisted to
        // pass start — boundary zombies rescan at most one tick later than vanilla.
        if (passNow - zombie.lastChangeOwner < OWNER_RESCAN_GATE_MILLIS && owner != null) {
            ZombieAuthScanMetrics.gateClosedZombies++;
            return;
        }

        // Grapple early-out — verbatim vanilla, on live state.
        if (zombie.getWrappedGrappleable().getGrappledBy() instanceof IsoPlayer grapplePlayer) {
            UdpConnection c = GameServer.getConnectionFromPlayer(grapplePlayer);
            if (c != null && c.isFullyConnected() && !GameServer.isDelayedDisconnect(c)) {
                manager.moveZombie(zombie, c, grapplePlayer);
                ZombieAuthScanMetrics.branchMovedZombies++;
                return;
            }
        }

        float zx = zombie.getX();
        float zy = zombie.getY();

        // Target early-out — verbatim vanilla, on live state.
        if (zombie.target instanceof IsoPlayer targetPlayer) {
            UdpConnection c = GameServer.getConnectionFromPlayer(targetPlayer);
            if (c != null && c.isFullyConnected() && !GameServer.isDelayedDisconnect(c)) {
                float d = targetPlayer.getRelevantAndDistance(zx, zy, c.getRelevantRange() - 2);
                if (!Float.isInfinite(d)) {
                    manager.moveZombie(zombie, c, targetPlayer);
                    ZombieAuthScanMetrics.branchMovedZombies++;
                    return;
                }
            }
        }

        UdpConnection connection = owner;
        IsoPlayer player = comp != null ? comp.getOwnerPlayer() : null;
        float distance = Float.POSITIVE_INFINITY;
        if (connection != null) {
            distance = connection.getRelevantAndDistance(zx, zy, zombie.getZ());
        }

        // The hot scan. Row skip mirrors vanilla's connection-level `c != connection` against the
        // RUNNING best (vanilla reassigns `connection` mid-loop; each list entry is visited once,
        // so the running best can only match a row via the original-owner case or never).
        for (int i = 0; i < rowCount; i++) {
            if (rowConn[i] == connection || !rowAlive[i]) {
                continue;
            }
            // Inlined IsoPlayer.getRelevantAndDistance against snapshot coordinates.
            float bound = rowRange8[i];
            if (Math.abs(rowX[i] - zx) > bound || Math.abs(rowY[i] - zy) > bound) {
                continue;
            }
            float d = IsoUtils.DistanceTo(rowX[i], rowY[i], zx, zy);
            if (connection == null || distance > d * OWNER_SWITCH_HYSTERESIS) {
                connection = rowConn[i];
                distance = d;
                player = rowPlayer[i];
            }
        }

        // Reanimated-corpse sweep — vanilla scans dead players when no owner was found. At most
        // one player in the world can hold this zombie as reanimatedCorpse, so plain overwrite
        // matches vanilla's last-match-wins regardless of connection-level skip subtleties.
        if (connection == null && zombie.isReanimatedPlayer()) {
            for (int i = 0; i < rowCount; i++) {
                if (rowDead[i] && rowPlayer[i].reanimatedCorpse == zombie) {
                    connection = rowConn[i];
                    player = rowPlayer[i];
                }
            }
        }

        // Vanilla's final relevance check on the winner.
        if (connection != null
                && !connection.RelevantTo(zx, zy, (connection.getRelevantRange() - 2) * 10)) {
            connection = null;
        }

        // Provably no-op moveZombie skip (class doc item 3): live zombie, unchanged connection,
        // and no vehicle-driver reassignment possible. Vanilla does not update ownerPlayer when
        // the connection is unchanged, so a differing `player` local does not matter here.
        if (!zombie.isDead()
                && connection == owner
                && (player == null || player.getVehicle() == null)) {
            ZombieAuthScanMetrics.scanUnchangedZombies++;
            return;
        }
        manager.moveZombie(zombie, connection, player);
        ZombieAuthScanMetrics.scanMovedZombies++;
    }

    private static void grow() {
        int newLength = rowConn.length * 2;
        rowConn = Arrays.copyOf(rowConn, newLength);
        rowPlayer = Arrays.copyOf(rowPlayer, newLength);
        rowX = Arrays.copyOf(rowX, newLength);
        rowY = Arrays.copyOf(rowY, newLength);
        rowRange8 = Arrays.copyOf(rowRange8, newLength);
        rowAlive = Arrays.copyOf(rowAlive, newLength);
        rowDead = Arrays.copyOf(rowDead, newLength);
    }

    private static void fail(Throwable t) {
        failed = true;
        passActive = false;
        StormLogger.LOGGER.error(
                "StormZombieAuthScan failed — reverting to vanilla"
                        + " NetworkZombieManager.updateAuth",
                t);
    }
}
