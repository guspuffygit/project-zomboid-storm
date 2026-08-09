package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;
import zombie.network.fields.character.PlayerID;

/**
 * Fixes a vanilla race where the client serializes its own online id as {@code -1} into a {@code
 * BodyDamageUpdatePacket}: {@code GameClient.sendPlayerConnect} (initial connect and respawn) and
 * {@code GameClient.disconnect} both reset the local player's online id to {@code -1}, and the
 * first-aid UI ({@code ISHealthPanel} / {@code ISMedicalCheckAction}) can send {@code
 * START_UPDATING}/{@code STOP_UPDATING} inside that window. Vanilla {@code processServer} then
 * registers a {@code BodyDamageSync.Updater} keyed on recipient {@code -1} that streams to nobody
 * every 500&nbsp;ms forever, and the doctor's health panel silently never subscribes.
 *
 * <p>The repair runs after {@code parse}: vanilla {@code PlayerID.parsePlayer} has already resolved
 * the actual {@link IsoPlayer} from the sending connection whenever the wire {@code playerIndex} is
 * not {@code -1} — only the numeric id field is stale. When the wire id is {@code -1} but the
 * resolved player exists, the id is rewritten to the resolved player's real online id. The
 * resolution comes from the connection itself, so the rewrite cannot be steered by a spoofed id.
 *
 * <p>Running at parse time (not {@code processServer}) matters for composition: downstream
 * ownership guards advising {@code processServer} (e.g. the anti-cheat mod) then see the repaired
 * id and pass legitimately instead of flagging the vanilla race as a spoof.
 *
 * <p><b>Reflection:</b> {@code currentPlayer} / {@code remotePlayer} are private final fields;
 * accessed via cached handles. The target class is resolved with {@code Class.forName} at first use
 * so constructing this patch never loads it early.
 */
public class BodyDamageUpdatePacketPatch extends StormClassTransformer {

    public BodyDamageUpdatePacketPatch() {
        super("zombie.network.packets.BodyDamageUpdatePacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.bodydamagesync.PacketParseAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.named("parse")));
    }

    private static volatile Field currentPlayerField;
    private static volatile Field remotePlayerField;

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (remotePlayerField != null) {
            return;
        }
        Class<?> packetClass = Class.forName("zombie.network.packets.BodyDamageUpdatePacket");

        Field current = packetClass.getDeclaredField("currentPlayer");
        current.setAccessible(true);

        Field remote = packetClass.getDeclaredField("remotePlayer");
        remote.setAccessible(true);

        currentPlayerField = current;
        remotePlayerField = remote;
    }

    /**
     * Reconciles both {@link PlayerID} fields of a just-parsed packet: a wire id of {@code -1}
     * accompanied by a connection-resolved player is rewritten to that player's real online id.
     */
    public static void repairPlayerIds(Object packetObj) throws ReflectiveOperationException {
        if (!GameServer.server) {
            return;
        }
        if (remotePlayerField == null) {
            initFieldHandles();
        }
        repairOne((PlayerID) currentPlayerField.get(packetObj), "currentPlayer");
        repairOne((PlayerID) remotePlayerField.get(packetObj), "remotePlayer");
    }

    private static void repairOne(PlayerID playerId, String label) {
        if (playerId == null || playerId.getID() != -1 || playerId.getPlayerIndex() == -1) {
            return;
        }
        IsoPlayer resolved = playerId.getPlayer();
        if (resolved == null) {
            return;
        }
        short realId = resolved.getOnlineID();
        if (realId == -1) {
            return;
        }
        playerId.setID(realId);
        LOGGER.debug(
                "BodyDamageUpdatePacket {}: repaired wire id -1 -> {} (resolved from connection, playerIndex={})",
                label,
                realId,
                playerId.getPlayerIndex());
    }
}
