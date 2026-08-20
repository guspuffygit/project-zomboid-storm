package io.pzstorm.storm.connection;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.SyncIsoObjectGateMetrics;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.GameServer;
import zombie.network.PacketTypes;

/**
 * Server-only replacement for the {@code syncIsoObject} broadcast loops, wired in by the four
 * {@code *SyncGatePatch} transformers ({@code IsoObject} base method plus the {@code
 * IsoWorldInventoryObject}, {@code IsoBarricade}, and {@code IsoLightSwitch} overrides).
 *
 * <p>Vanilla's server paths send every SyncIsoObject full-state packet (doors, curtains,
 * generators, hutches, rain barrels, dropped items…) to <b>every</b> connection. A client without
 * the object's square loaded discards the packet on arrival ({@code
 * GameClient.receiveSyncIsoObject} finds no square), so sends outside a connection's relevant range
 * are pure waste — 972 KB/s at 103 players on a production server, the #2 outbound packet type.
 * Vanilla itself gates {@code IsoDoor.syncIsoObject} (and {@code IsoLightSwitch}'s server-initiated
 * branch) with {@code isFullyConnected() && isRelevantTo(x, y)}; this gate applies that same
 * precedent to the ungated loops. Everything else — error guards and their exact console output,
 * receive-side field application, source-connection exclusion, {@code flagForHotSave()} —
 * reproduces the vanilla bodies expression for expression.
 *
 * <p>{@code isRelevantTo} covers each player's connected chunk area plus relevant-range window, the
 * exact region whose squares the client keeps loaded; {@code UdpConnectionRelevancePatch}
 * additionally forces it {@code false} for connections still in the world-download handshake. State
 * on a client that was out of range while an object changed self-heals the way vanilla already
 * relies on for every object mutation without a sync packet: the full object state rides in the
 * chunk payload when the chunk streams back in.
 *
 * <p>The {@code IsoObject} base-method patch covers every subclass without its own {@code
 * syncIsoObject} override (hutches — the largest payloads, egg inventories serialize per egg —
 * generators, curtains' base path, and so on); {@code syncIsoObjectSend} dispatches virtually, so
 * subclass payloads are built exactly as vanilla. {@code IsoStove} and {@code IsoCurtain} keep
 * their vanilla override loops: their receive branches touch private state the gate cannot
 * reproduce, and both are low-volume event-driven senders.
 *
 * <p>Fail-soft is two-phase. A throw before any state was applied latches vanilla and returns
 * {@code false} — the vanilla body reruns from scratch (any packets already sent are idempotent
 * full-state snapshots, so a duplicate is harmless). A throw after the receive side consumed the
 * {@link ByteBufferReader} latches vanilla and returns {@code true} — rerunning vanilla would read
 * garbage from the drained buffer, so that one call degrades (relay skipped) and every later call
 * is vanilla.
 *
 * <p>The gate is always on; vanilla broadcast is restored permanently if the gated path ever
 * throws.
 *
 * <p>Single-threaded by design: server-side {@code syncIsoObject} runs only on the main thread
 * (engine update and inbound-packet processing), so the failure latch and the metric tallies need
 * no synchronization.
 */
public final class StormSyncIsoObjectGate {

    /**
     * Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of a gated body.
     */
    private static boolean failed;

    private StormSyncIsoObjectGate() {}

    /**
     * Gated replacement for the server paths of {@code IsoObject.syncIsoObject(boolean, byte,
     * UdpConnection, ByteBufferReader)}.
     *
     * @param objectObj the {@code IsoObject} ({@code @Advice.This}; typed {@code Object} so the
     *     advice never references the transform target)
     * @return {@code true} if the gated path handled the call (the advice skips the vanilla body);
     *     {@code false} to fall through to vanilla (failure latch tripped)
     */
    public static boolean runIsoObject(
            Object objectObj, boolean bRemote, Object sourceObj, Object bbObj) {
        if (failed) {
            SyncIsoObjectGateMetrics.recordVanilla(SyncIsoObjectGateMetrics.TARGET_ISO_OBJECT);
            return false;
        }
        boolean received = false;
        try {
            IsoObject obj = (IsoObject) objectObj;
            if (printedErrorGuard(obj)) {
                SyncIsoObjectGateMetrics.recordGated(
                        SyncIsoObjectGateMetrics.TARGET_ISO_OBJECT, 0, 0);
                return true;
            }
            long sent = 0;
            long suppressed = 0;
            if (!bRemote) {
                // Vanilla: broadcast to every connection. Gate added.
                for (UdpConnection connection : GameServer.udpEngine.connections) {
                    if (isRelevant(connection, obj)) {
                        sendFullState(connection, obj);
                        sent++;
                    } else {
                        suppressed++;
                    }
                }
            } else {
                received = true;
                obj.syncIsoObjectReceive((ByteBufferReader) bbObj);
                // Vanilla relay: only when source is non-null, excluding the source. Gate added.
                UdpConnection source = (UdpConnection) sourceObj;
                for (UdpConnection connection : GameServer.udpEngine.connections) {
                    if (source != null
                            && connection.getConnectedGUID() != source.getConnectedGUID()) {
                        if (isRelevant(connection, obj)) {
                            sendFullState(connection, obj);
                            sent++;
                        } else {
                            suppressed++;
                        }
                    }
                }
            }
            obj.flagForHotSave();
            SyncIsoObjectGateMetrics.recordGated(
                    SyncIsoObjectGateMetrics.TARGET_ISO_OBJECT, sent, suppressed);
            return true;
        } catch (Throwable t) {
            return latch("IsoObject", received, t);
        }
    }

    /**
     * Gated replacement for the server paths of {@code IsoWorldInventoryObject.syncIsoObject}. Same
     * shape as {@link #runIsoObject}; the receive side applies the override's inline field reads
     * ({@code xoff}/{@code yoff}/{@code zoff} + fluid container) and post-relay square refresh.
     */
    public static boolean runWorldInventoryObject(
            Object objectObj, boolean bRemote, Object sourceObj, Object bbObj) {
        if (failed) {
            SyncIsoObjectGateMetrics.recordVanilla(SyncIsoObjectGateMetrics.TARGET_WORLD_INVENTORY);
            return false;
        }
        boolean received = false;
        try {
            IsoWorldInventoryObject obj = (IsoWorldInventoryObject) objectObj;
            if (printedErrorGuard(obj)) {
                SyncIsoObjectGateMetrics.recordGated(
                        SyncIsoObjectGateMetrics.TARGET_WORLD_INVENTORY, 0, 0);
                return true;
            }
            long sent = 0;
            long suppressed = 0;
            if (!bRemote) {
                for (UdpConnection connection : GameServer.udpEngine.connections) {
                    if (isRelevant(connection, obj)) {
                        sendFullState(connection, obj);
                        sent++;
                    } else {
                        suppressed++;
                    }
                }
            } else {
                received = true;
                ByteBufferReader bb = (ByteBufferReader) bbObj;
                obj.xoff = bb.getFloat();
                obj.yoff = bb.getFloat();
                obj.zoff = bb.getFloat();
                obj.syncFluidContainerReceive(bb);
                UdpConnection source = (UdpConnection) sourceObj;
                for (UdpConnection connection : GameServer.udpEngine.connections) {
                    if (source != null
                            && connection.getConnectedGUID() != source.getConnectedGUID()) {
                        if (isRelevant(connection, obj)) {
                            sendFullState(connection, obj);
                            sent++;
                        } else {
                            suppressed++;
                        }
                    }
                }
                obj.invalidateRenderChunkLevel(256L);
                obj.getSquare().RecalcProperties();
            }
            obj.flagForHotSave();
            SyncIsoObjectGateMetrics.recordGated(
                    SyncIsoObjectGateMetrics.TARGET_WORLD_INVENTORY, sent, suppressed);
            return true;
        } catch (Throwable t) {
            return latch("IsoWorldInventoryObject", received, t);
        }
    }

    /**
     * Gated replacement for the server path of {@code IsoBarricade.syncIsoObject}. The override has
     * no error guards, no receive on the server (its receive branch is client-only), and no {@code
     * flagForHotSave()}; the single loop sends to everyone when {@code source} is null, everyone
     * but the source otherwise. Gate added on top.
     */
    public static boolean runBarricade(Object objectObj, Object sourceObj) {
        if (failed) {
            SyncIsoObjectGateMetrics.recordVanilla(SyncIsoObjectGateMetrics.TARGET_BARRICADE);
            return false;
        }
        try {
            IsoBarricade obj = (IsoBarricade) objectObj;
            UdpConnection source = (UdpConnection) sourceObj;
            long sent = 0;
            long suppressed = 0;
            for (UdpConnection connection : GameServer.udpEngine.connections) {
                if (source == null || connection.getConnectedGUID() != source.getConnectedGUID()) {
                    if (isRelevant(connection, obj)) {
                        sendFullState(connection, obj);
                        sent++;
                    } else {
                        suppressed++;
                    }
                }
            }
            SyncIsoObjectGateMetrics.recordGated(
                    SyncIsoObjectGateMetrics.TARGET_BARRICADE, sent, suppressed);
            return true;
        } catch (Throwable t) {
            return latch("IsoBarricade", false, t);
        }
    }

    /**
     * Gated replacement for the server path of {@code IsoLightSwitch.syncIsoObject(boolean, byte,
     * UdpConnection)} (the operative 3-arg method — the 4-arg override just delegates). The
     * server-initiated branch ({@code source == null}) is already {@code isRelevantTo}-gated in
     * vanilla and carries a custom inline payload (reproduced verbatim, including the missing-index
     * fallback byte); the gate only adds {@code isFullyConnected()} there, and the full precedent
     * gate on the previously ungated relay branch.
     */
    public static boolean runLightSwitch(Object objectObj, Object sourceObj) {
        if (failed) {
            SyncIsoObjectGateMetrics.recordVanilla(SyncIsoObjectGateMetrics.TARGET_LIGHT_SWITCH);
            return false;
        }
        try {
            IsoLightSwitch obj = (IsoLightSwitch) objectObj;
            if (printedErrorGuard(obj)) {
                SyncIsoObjectGateMetrics.recordGated(
                        SyncIsoObjectGateMetrics.TARGET_LIGHT_SWITCH, 0, 0);
                return true;
            }
            UdpConnection source = (UdpConnection) sourceObj;
            IsoGridSquare square = obj.getSquare();
            long sent = 0;
            long suppressed = 0;
            for (UdpConnection connection : GameServer.udpEngine.connections) {
                if (source != null) {
                    if (connection.getConnectedGUID() != source.getConnectedGUID()) {
                        if (isRelevant(connection, obj)) {
                            sendFullState(connection, obj);
                            sent++;
                        } else {
                            suppressed++;
                        }
                    }
                } else if (connection.isFullyConnected()
                        && connection.isRelevantTo(square.x, square.y)) {
                    ByteBufferWriter b = connection.startPacket();
                    PacketTypes.PacketType.SyncIsoObject.doPacket(b);
                    b.putInt(square.getX());
                    b.putInt(square.getY());
                    b.putInt(square.getZ());
                    int i = square.getObjects().indexOf(obj);
                    if (i != -1) {
                        b.putByte(i);
                    } else {
                        b.putByte(square.getObjects().size());
                    }
                    b.putBoolean(true);
                    b.putBoolean(obj.activated);
                    PacketTypes.PacketType.SyncIsoObject.send(connection);
                    sent++;
                }
                // No suppressed++ in the source == null branch beyond isFullyConnected: vanilla's
                // own isRelevantTo gate is doing the dropping there, not this patch.
            }
            obj.flagForHotSave();
            SyncIsoObjectGateMetrics.recordGated(
                    SyncIsoObjectGateMetrics.TARGET_LIGHT_SWITCH, sent, suppressed);
            return true;
        } catch (Throwable t) {
            return latch("IsoLightSwitch", false, t);
        }
    }

    /**
     * Vanilla's leading error guards, output-identical: prints and returns {@code true} when the
     * object has no square or is not on its square's object list (in which case the whole vanilla
     * body — sends, receive, {@code flagForHotSave} — is skipped too).
     */
    private static boolean printedErrorGuard(IsoObject obj) {
        IsoGridSquare square = obj.getSquare();
        if (square == null) {
            System.out.println("ERROR: " + obj.getClass().getSimpleName() + " square is null");
            return true;
        }
        if (obj.getObjectIndex() == -1) {
            System.out.println(
                    "ERROR: "
                            + obj.getClass().getSimpleName()
                            + " not found on square "
                            + square.getX()
                            + ","
                            + square.getY()
                            + ","
                            + square.getZ());
            return true;
        }
        return false;
    }

    /**
     * The gate itself — vanilla's own {@code IsoDoor.syncIsoObject} precedent: fully connected and
     * the object's square inside the connection's relevant range. {@code getX()}/{@code getY()}
     * read the square, non-null here by the callers' guards (and vanilla's own send path
     * dereferences it identically).
     */
    private static boolean isRelevant(UdpConnection connection, IsoObject obj) {
        return connection.isFullyConnected() && connection.isRelevantTo(obj.getX(), obj.getY());
    }

    /** Vanilla's 4-line full-state send; {@code syncIsoObjectSend} dispatches to the subclass. */
    private static void sendFullState(UdpConnection connection, IsoObject obj) {
        ByteBufferWriter b = connection.startPacket();
        PacketTypes.PacketType.SyncIsoObject.doPacket(b);
        obj.syncIsoObjectSend(b);
        PacketTypes.PacketType.SyncIsoObject.send(connection);
    }

    /**
     * Trips the permanent vanilla latch. Returns the advice verdict for the failing call: {@code
     * false} (rerun vanilla — safe, no state was applied) unless {@code received}, in which case
     * the {@link ByteBufferReader} is already drained and rerunning vanilla would read garbage, so
     * the call reports handled and degrades once.
     */
    private static boolean latch(String target, boolean received, Throwable t) {
        failed = true;
        StormLogger.LOGGER.error(
                "StormSyncIsoObjectGate failed in "
                        + target
                        + ".syncIsoObject — reverting to vanilla broadcast"
                        + (received ? " (receive already applied; relay skipped this once)" : ""),
                t);
        return received;
    }
}
