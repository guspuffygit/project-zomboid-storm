package io.pzstorm.storm.connection;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.GameEntityBroadcastGateMetrics;
import zombie.core.raknet.UdpConnection;
import zombie.entity.Component;
import zombie.entity.GameEntity;
import zombie.entity.GameEntityType;
import zombie.entity.network.EntityPacketData;
import zombie.entity.network.EntityPacketType;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;

/**
 * Server-only relevancy gate for the broadcast branch of {@code
 * GameEntityNetwork.sendPacketData(EntityPacketData, GameEntity, Component, IConnection, boolean)},
 * wired in by {@code GameEntityBroadcastGatePatch}.
 *
 * <p>Vanilla's server broadcast branch ({@code isIgnoreConnection == true}) hands every GameEntity
 * packet to {@code INetworkPacket.sendToAll}, whose only filter is {@code isFullyConnected()} —
 * every craft-progress tick ({@code CraftLogicSync}, re-sent every 1000 ms per running station),
 * every component dump ({@code SyncGameEntity}), and every using-player change goes to every
 * connection. A client without the entity's chunk loaded has no registered entity to apply it to;
 * at 103 players on ATF this stream measured ~1.06 MB/s at ~1080 pkts/s, the #1 outbound packet
 * count. The gate applies vanilla's own object-sync precedent ({@code
 * INetworkPacket.sendToRelative}, used for {@code UpdateItemSprite}/{@code SendCustomColor}/{@code
 * AddItemToMap}): skip connections failing {@code isFullyConnected() && isRelevantTo(x, y)}.
 *
 * <p>Only entities of {@link GameEntityType#IsoObject} are gated — they are placed world objects
 * whose square is the relevancy anchor. Inventory-item, vehicle-part, moving-object, and
 * meta-entity packets keep the vanilla broadcast (their position is not the entity's own square, or
 * they have none). An IsoObject entity with no square (not placed) also stays vanilla.
 *
 * <p>State on a client that was out of range while an entity changed self-heals the way vanilla
 * already relies on: current entity state rides in the chunk payload when the chunk streams back
 * in, and the next 1 s {@code CraftLogicSync} arrives as soon as the client becomes relevant.
 *
 * <p>Fail-soft mirrors {@link StormSyncIsoObjectGate}: a throw before {@code
 * EntityPacketData.release} latches vanilla and returns {@code false} — the vanilla body reruns
 * from scratch (GameEntity payloads are full-state snapshots on a reliable-ordered channel, so a
 * duplicate is harmless). A throw after release latches vanilla and returns {@code true} —
 * rerunning vanilla would release the pooled packet data twice.
 *
 * <p>Single-threaded by design: server-side {@code sendPacketData} runs only on the main thread
 * (engine update and inbound-packet processing), so the failure latch and the metric tallies need
 * no synchronization.
 */
public final class StormGameEntityBroadcastGate {

    /**
     * Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of the gated body.
     */
    private static boolean failed;

    private StormGameEntityBroadcastGate() {}

    /**
     * Gated replacement for the server broadcast branch of {@code
     * GameEntityNetwork.sendPacketData}. Arguments are typed {@code Object} so the advice class
     * never references the transform target's dependencies.
     *
     * @return {@code true} if the gated path handled the call (the advice skips the vanilla body);
     *     {@code false} to fall through to vanilla (targeted send, ungateable entity, vanilla
     *     validation-warn case, or failure latch)
     */
    public static boolean run(
            Object dataObj,
            Object entityObj,
            Object componentObj,
            Object connectionObj,
            boolean isIgnoreConnection) {
        if (!isIgnoreConnection) {
            // Targeted send — never a broadcast; vanilla handles it. Not counted.
            return false;
        }
        if (failed) {
            GameEntityBroadcastGateMetrics.recordVanilla();
            return false;
        }
        boolean released = false;
        try {
            GameEntity entity = (GameEntity) entityObj;
            EntityPacketData data = (EntityPacketData) dataObj;
            Component component = (Component) componentObj;
            if (entity == null
                    || data == null
                    || entity.getGameEntityType() != GameEntityType.IsoObject) {
                GameEntityBroadcastGateMetrics.recordBypassed();
                return false;
            }
            EntityPacketType packetType = data.getEntityPacketType();
            if (packetType.isEntityPacket() && component != null
                    || packetType.isComponentPacket() && component == null) {
                // Vanilla warns and skips the send for these; let it print the exact output.
                GameEntityBroadcastGateMetrics.recordBypassed();
                return false;
            }
            IsoGridSquare square = entity.getSquare();
            if (square == null) {
                GameEntityBroadcastGateMetrics.recordBypassed();
                return false;
            }
            float x = entity.getX();
            float y = entity.getY();
            IConnection excluded = (IConnection) connectionObj;
            long sent = 0;
            long suppressed = 0;
            // Vanilla: INetworkPacket.sendToAll(GameEntity, excluded, ...). Relevancy gate added,
            // per-connection send and exclusion semantics reproduced from sendToAll/sendToRelative.
            for (UdpConnection connection : GameServer.udpEngine.connections) {
                if (excluded == null
                        || connection.getConnectedGUID() != excluded.getConnectedGUID()) {
                    if (connection.isFullyConnected() && connection.isRelevantTo(x, y)) {
                        INetworkPacket.send(
                                connection,
                                PacketTypes.PacketType.GameEntity,
                                data,
                                entity,
                                component);
                        sent++;
                    } else {
                        suppressed++;
                    }
                }
            }
            EntityPacketData.release(data);
            released = true;
            GameEntityBroadcastGateMetrics.recordGated(sent, suppressed);
            return true;
        } catch (Throwable t) {
            return latch(released, t);
        }
    }

    /**
     * Trips the permanent vanilla latch. Returns the advice verdict for the failing call: {@code
     * false} (rerun vanilla — safe, duplicate sends are idempotent full-state snapshots) unless
     * {@code released}, in which case the pooled {@link EntityPacketData} is already back in the
     * pool and rerunning vanilla would double-release it, so the call reports handled and every
     * later call is vanilla.
     */
    private static boolean latch(boolean released, Throwable t) {
        failed = true;
        StormLogger.LOGGER.error(
                "StormGameEntityBroadcastGate failed in GameEntityNetwork.sendPacketData —"
                        + " reverting to vanilla broadcast"
                        + (released
                                ? " (packet data already released; send skipped this once)"
                                : ""),
                t);
        return released;
    }
}
