package io.pzstorm.storm.vehicles;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.MainLoopStepTimings;
import io.pzstorm.storm.metrics.VehicleSendFastPathMetrics;
import io.pzstorm.storm.metrics.VehicleSendMetrics;
import java.util.ArrayList;
import java.util.HashMap;
import zombie.core.math.PZMath;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoWorld;
import zombie.iso.Vector3;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleIDMap;
import zombie.vehicles.VehicleManager;

/**
 * Spatially pre-filtered replacement for {@code VehicleManager.sendVehicles(UdpConnection)}, wired
 * in by {@code VehicleManagerSendVehiclesPatch}.
 *
 * <p>Vanilla {@code serverUpdate()} runs every 100 ms and, per connection, iterates <b>every</b>
 * loaded vehicle: a relevance box test, a per-connection state-map probe and a dirty-flag check for
 * each of ~1,792 vehicles × ~144 connections — ~2.6M pair-checks/s, ~2 ms/tick on ATF (scan #5),
 * almost all of it answering "not near this player, nothing to send".
 *
 * <p>This class rebuilds a coarse position grid of all loaded vehicles once per send pass (keyed on
 * {@link StormVehicleSleep#tick}), then per connection visits only the grid cells overlapping the
 * connection's relevance areas. {@code UdpConnection.isRelevantTo} has <b>two</b> relevance
 * sources, and both must be covered: the {@code connectArea} boxes (a loading-area hint set by
 * {@code RequestLargeAreaZipPacket}/coop connect — often null for the whole session) and {@code
 * releventPos} within {@code relevantRange * 8} squares (the player's live position, updated every
 * {@code PlayerPacket} — the steady-state source for every normal connection). Every candidate
 * still goes through the exact vanilla sequence — {@code isRelevantTo}, {@code getVehicleState},
 * {@code shouldSend}, the same packets, the same state-flag bookkeeping — so the set of packets on
 * the wire is identical to vanilla's:
 *
 * <ul>
 *   <li>vanilla only ever sends for vehicles passing {@code isRelevantTo}, and every such vehicle
 *       lies inside a connectArea box or a releventPos range, hence inside a visited grid cell
 *       (queries are widened by {@link #QUERY_MARGIN} squares to cover position drift when a
 *       mid-tick authorization change triggers a second send pass in the same tick);
 *   <li>the id assignment vanilla does lazily inside the per-connection loop ({@code vehicleId ==
 *       -1} → allocate + register) is hoisted into the grid rebuild, which runs before any
 *       relevance test of the pass.
 * </ul>
 *
 * <p>Main-thread only by contract, like the vanilla caller ({@code serverUpdate} and the
 * authorization-change resends in {@code BaseVehicle}).
 *
 * <p>Fail-soft: any throwable latches the fast path off for the session and the vanilla body runs
 * from the same call onward (a partially sent pass is safe — per-state dirty flags make the send
 * idempotent). Always on otherwise — no sandbox option.
 */
public final class StormVehicleSend {

    /** Grid cell edge in squares (32) as a bit shift of the floored world coordinate. */
    private static final int CELL_SHIFT = 5;

    /**
     * Squares added around each connectArea box when collecting grid cells. Covers vehicle movement
     * between the grid build and a second send pass in the same tick (top vehicle speeds move ~2
     * squares/tick).
     */
    private static final int QUERY_MARGIN = 4;

    /** Latched on after any throwable; vanilla handles every later call. */
    private static boolean failed;

    /** Tick stamp of the current grid; rebuilt when {@link StormVehicleSleep#tick} moves on. */
    private static long gridTick = Long.MIN_VALUE;

    private static final HashMap<Long, ArrayList<BaseVehicle>> GRID = new HashMap<>();

    /** Recycled buckets, so a rebuild allocates nothing in steady state. */
    private static final ArrayList<ArrayList<BaseVehicle>> BUCKET_POOL = new ArrayList<>();

    /** Per-connection scratch for bucket dedupe across overlapping connectArea boxes. */
    private static final ArrayList<ArrayList<BaseVehicle>> SEEN_BUCKETS = new ArrayList<>();

    /** Vehicles examined across all connections. Main-thread writer; read at scrape time. */
    public static long candidates;

    /** Vehicle update/full-update packets sent by the fast path. Main-thread writer. */
    public static long sends;

    static {
        VehicleSendFastPathMetrics.init();
    }

    private StormVehicleSend() {}

    /**
     * Advice entry for {@code VehicleManager.sendVehicles(UdpConnection)}. Returns {@code 0} when
     * the fast path handled the connection (the vanilla body is skipped), {@code -1} to run vanilla
     * untimed, or a {@code nanoTime} timestamp to run vanilla timed.
     */
    public static long enterSendVehicles(Object managerObj, Object connectionObj) {
        if (!GameServer.server) {
            return -1L;
        }
        if (failed) {
            return System.nanoTime();
        }
        long start = System.nanoTime();
        try {
            sendFast((VehicleManager) managerObj, (UdpConnection) connectionObj);
            long elapsed = System.nanoTime() - start;
            VehicleSendMetrics.recordNanos(elapsed);
            MainLoopStepTimings.record("VehicleManager.sendVehicles", elapsed);
            return 0L;
        } catch (Throwable t) {
            failed = true;
            gridTick = Long.MIN_VALUE;
            VehicleSendFastPathMetrics.recordFailure();
            StormLogger.LOGGER.error(
                    "StormVehicleSend failed — reverting to the vanilla per-connection vehicle"
                            + " scan",
                    t);
            return System.nanoTime();
        }
    }

    private static void sendFast(VehicleManager manager, UdpConnection connection) {
        if (!connection.isFullyConnected()) {
            return;
        }
        ensureGrid(manager);
        SEEN_BUCKETS.clear();
        Vector3[] areas = connection.connectArea;
        for (int n = 0; n < areas.length; n++) {
            Vector3 area = areas[n];
            if (area == null) {
                continue;
            }
            int chunkMapWidth = (int) area.z;
            visitCells(
                    manager,
                    connection,
                    boxMinSquare(area.x, chunkMapWidth),
                    boxMinSquare(area.y, chunkMapWidth),
                    boxMaxSquare(area.x, chunkMapWidth),
                    boxMaxSquare(area.y, chunkMapWidth));
        }
        Vector3[] positions = connection.releventPos;
        int relevantRange = connection.getRelevantRange();
        for (int n = 0; n < positions.length; n++) {
            Vector3 pos = positions[n];
            if (pos == null) {
                continue;
            }
            visitCells(
                    manager,
                    connection,
                    posMinSquare(pos.x, relevantRange),
                    posMinSquare(pos.y, relevantRange),
                    posMaxSquare(pos.x, relevantRange),
                    posMaxSquare(pos.y, relevantRange));
        }
    }

    /**
     * First square of a {@code connectArea} box on one axis (same box math as {@code
     * UdpConnection.isRelevantTo}), widened by {@link #QUERY_MARGIN}. Package-private for the
     * coverage parity test.
     */
    static int boxMinSquare(float areaCoord, int chunkMapWidth) {
        return PZMath.fastfloor(areaCoord - chunkMapWidth / 2) * 8 - QUERY_MARGIN;
    }

    /** Last square of a {@code connectArea} box on one axis, widened by {@link #QUERY_MARGIN}. */
    static int boxMaxSquare(float areaCoord, int chunkMapWidth) {
        return PZMath.fastfloor(areaCoord - chunkMapWidth / 2) * 8
                + chunkMapWidth * 8
                + QUERY_MARGIN;
    }

    /**
     * First square of a {@code releventPos} range on one axis ({@code |pos - x| <= relevantRange *
     * 8}, inclusive), widened by {@link #QUERY_MARGIN}.
     */
    static int posMinSquare(float posCoord, int relevantRange) {
        return PZMath.fastfloor(posCoord) - relevantRange * 8 - QUERY_MARGIN;
    }

    /** Last square of a {@code releventPos} range on one axis, widened by {@link #QUERY_MARGIN}. */
    static int posMaxSquare(float posCoord, int relevantRange) {
        return PZMath.fastfloor(posCoord) + relevantRange * 8 + QUERY_MARGIN;
    }

    /** Runs {@link #sendBucket} on every not-yet-seen grid cell overlapping a square range. */
    private static void visitCells(
            VehicleManager manager,
            UdpConnection connection,
            int minSquareX,
            int minSquareY,
            int maxSquareX,
            int maxSquareY) {
        int cx0 = minSquareX >> CELL_SHIFT;
        int cy0 = minSquareY >> CELL_SHIFT;
        int cx1 = maxSquareX >> CELL_SHIFT;
        int cy1 = maxSquareY >> CELL_SHIFT;
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                ArrayList<BaseVehicle> bucket = GRID.get(key(cx, cy));
                if (bucket == null || seen(bucket)) {
                    continue;
                }
                SEEN_BUCKETS.add(bucket);
                sendBucket(manager, connection, bucket);
            }
        }
    }

    /** The vanilla per-vehicle send sequence, applied to one grid bucket. */
    private static void sendBucket(
            VehicleManager manager, UdpConnection connection, ArrayList<BaseVehicle> bucket) {
        for (int i = 0; i < bucket.size(); i++) {
            BaseVehicle vehicle = bucket.get(i);
            candidates++;
            if (!connection.isRelevantTo(vehicle.getX(), vehicle.getY())) {
                continue;
            }
            BaseVehicle.ServerVehicleState state = manager.getVehicleState(connection, vehicle);
            if (!state.shouldSend(vehicle)) {
                continue;
            }
            if ((state.flags & 1) != 0) {
                INetworkPacket.send(connection, PacketTypes.PacketType.VehicleFullUpdate, vehicle);
                state.flags = (short) (state.flags | 24578);
            } else {
                INetworkPacket.send(
                        connection, PacketTypes.PacketType.VehicleUpdate, vehicle, state.flags);
            }
            sends++;
            if ((state.flags & 8192) != 0) {
                state.setAuthorization(vehicle);
            }
            if ((state.flags & 2) != 0) {
                state.x = vehicle.getX();
                state.y = vehicle.getY();
                state.z = vehicle.jniTransform.origin.y;
                state.orient.set(vehicle.savedRot);
            }
            state.flags = 0;
        }
    }

    /**
     * Rebuilds the vehicle grid for the current server tick (no-op when already built this tick, so
     * the ~144 per-connection calls of one pass share a single build). Also hoists vanilla's lazy
     * id assignment for freshly spawned vehicles.
     */
    private static void ensureGrid(VehicleManager manager) {
        long tick = StormVehicleSleep.tick;
        if (gridTick == tick) {
            return;
        }
        for (ArrayList<BaseVehicle> bucket : GRID.values()) {
            bucket.clear();
            BUCKET_POOL.add(bucket);
        }
        GRID.clear();
        for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
            if (vehicle.vehicleId == -1) {
                vehicle.vehicleId = VehicleIDMap.instance.allocateID();
                manager.registerVehicle(vehicle);
            }
            long key =
                    key(
                            PZMath.fastfloor(vehicle.getX()) >> CELL_SHIFT,
                            PZMath.fastfloor(vehicle.getY()) >> CELL_SHIFT);
            ArrayList<BaseVehicle> bucket = GRID.get(key);
            if (bucket == null) {
                int last = BUCKET_POOL.size() - 1;
                bucket = last >= 0 ? BUCKET_POOL.remove(last) : new ArrayList<>();
                GRID.put(key, bucket);
            }
            bucket.add(vehicle);
        }
        gridTick = tick;
    }

    private static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xFFFFFFFFL);
    }

    private static boolean seen(ArrayList<BaseVehicle> bucket) {
        for (int i = 0; i < SEEN_BUCKETS.size(); i++) {
            if (SEEN_BUCKETS.get(i) == bucket) {
                return true;
            }
        }
        return false;
    }

    /** Test-only: clears the failure latch and grid. */
    static void resetForTest() {
        failed = false;
        gridTick = Long.MIN_VALUE;
        GRID.clear();
        BUCKET_POOL.clear();
        SEEN_BUCKETS.clear();
    }
}
