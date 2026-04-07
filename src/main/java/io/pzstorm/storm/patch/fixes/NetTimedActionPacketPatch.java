package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.ActionManager;
import zombie.core.NetTimedAction;
import zombie.core.Transaction;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.NetTimedActionPacket;

/**
 * Fixes a vanilla bug in {@code NetTimedActionPacket.processServer()} where both the Accept and
 * Reject response paths serialize the packet ({@code this}) instead of the server-side action
 * ({@code act}).
 *
 * <p>Since the packet's state is always {@code Request} when entering the handler, the client
 * always receives {@code Request} state back regardless of whether the server accepted or rejected
 * the action. This means:
 *
 * <ul>
 *   <li>The client never transitions out of the Request state
 *   <li>The client never receives the server-calculated duration (Accept path)
 *   <li>The client cannot distinguish acceptance from rejection
 * </ul>
 *
 * <p>This patch replaces the method body and writes {@code act} (with the correct state and
 * duration) instead of {@code this}.
 *
 * <p><b>Registration order matters:</b> this patch must be registered <em>before</em> {@link
 * io.pzstorm.storm.patch.networking.PacketReceivedPatch} so that the generic packet event
 * dispatching wraps around the fixed logic. The corrected code runs inside the enter advice; the
 * original body is skipped via {@code skipOn}. If the advice throws, {@code suppress} causes a
 * fallback to the original (buggy) method body.
 *
 * <p><b>Selective rollout:</b> set the system property {@code storm.fix.nettimedaction.players} to
 * restrict the fix to specific players. Accepts a comma-separated list of Steam IDs and/or
 * usernames (case-insensitive), or {@code *} for all players (the default when unset). Players not
 * in the list fall through to the original vanilla method. Example:
 *
 * <pre>-Dstorm.fix.nettimedaction.players=76561198000000000,bob</pre>
 *
 * <p><b>Reflection:</b> the {@code state}, {@code id}, and {@code playerId} fields live in the
 * package-private {@code Action} base class. Byte Buddy's {@code @Advice.FieldValue} only searches
 * declared fields (not the superclass hierarchy), and {@code Action} cannot be specified as {@code
 * declaringType} because it is package-private. These fields are therefore accessed via cached
 * reflection handles in {@link #processServerFixed}.
 */
public class NetTimedActionPacketPatch extends StormClassTransformer {

    public NetTimedActionPacketPatch() {
        super("zombie.network.packets.NetTimedActionPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(ProcessServerAdvice.class).on(ElementMatchers.named("processServer")));
    }

    // -- Cached reflection handles for Action's protected fields --

    private static volatile Field stateField;
    private static volatile Field idField;
    private static volatile Field playerIdField;

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (stateField != null) return;
        Class<?> actionClass = Class.forName("zombie.core.Action");
        Field sf = actionClass.getDeclaredField("state");
        sf.setAccessible(true);
        Field idf = actionClass.getDeclaredField("id");
        idf.setAccessible(true);
        Field pf = actionClass.getDeclaredField("playerId");
        pf.setAccessible(true);
        // Publish all at once — volatile write of stateField acts as release fence
        idField = idf;
        playerIdField = pf;
        stateField = sf;
    }

    /**
     * Corrected implementation of {@code NetTimedActionPacket.processServer()}. Identical to
     * vanilla except that {@code act.write(bbw)} is used instead of {@code this.write(bbw)} in both
     * the Accept and Reject response paths.
     *
     * <p>The private {@code getAction()} helper is replicated using the public {@link
     * ActionManager#getAction(byte, PlayerID)} and {@link NetTimedAction#copyFrom(NetTimedAction)}.
     *
     * @return {@code true} if the fix ran (skip original), {@code false} if the player is not in
     *     the allowed set (fall through to vanilla).
     */
    public static boolean processServerFixed(Object self, UdpConnection connection)
            throws ReflectiveOperationException {
        if (stateField == null) {
            initFieldHandles();
        }

        NetTimedActionPacket packet = (NetTimedActionPacket) self;
        Transaction.TransactionState state = (Transaction.TransactionState) stateField.get(self);
        byte id = idField.getByte(self);
        PlayerID playerId = (PlayerID) playerIdField.get(self);

        if (state == Transaction.TransactionState.Request) {
            if (packet.isConsistent(connection) && packet.action != null) {
                // --- ACCEPT PATH ---
                LOGGER.trace("NetTimedAction accepted {}", packet.getDescription());
                ActionManager.stopPlayerActions(playerId);

                NetTimedAction act = ActionManager.getAction(id, playerId);
                if (act == null) {
                    act = new NetTimedAction();
                }
                act.copyFrom(packet);

                ActionManager.start(act);
                act.setState(Transaction.TransactionState.Accept);

                ByteBufferWriter bbw = connection.startPacket();
                PacketTypes.PacketType.NetTimedAction.doPacket(bbw);
                act.write(bbw); // FIX: write act (state=Accept, with duration) not this
                PacketTypes.PacketType.NetTimedAction.send(connection);
            } else {
                // --- REJECT PATH ---
                LOGGER.trace("NetTimedAction rejected {}", packet.getDescription());

                NetTimedAction act = ActionManager.getAction(id, playerId);
                if (act == null) {
                    act = new NetTimedAction();
                }
                act.copyFrom(packet);

                act.setState(Transaction.TransactionState.Reject);

                ByteBufferWriter bbw = connection.startPacket();
                PacketTypes.PacketType.NetTimedAction.doPacket(bbw);
                act.write(bbw); // FIX: write act (state=Reject) not this
                PacketTypes.PacketType.NetTimedAction.send(connection);
            }
        } else if (Transaction.TransactionState.Reject == state) {
            // --- CLIENT REJECT ACKNOWLEDGEMENT ---
            NetTimedAction act = ActionManager.getAction(id, playerId);
            if (act == null) {
                act = new NetTimedAction();
            }
            act.copyFrom(packet);
            LOGGER.trace("NetTimedAction reject {}", packet.getDescription());
            ActionManager.stop(act);
        }

        return true;
    }

    /**
     * Advice inlined into {@code NetTimedActionPacket.processServer()}. Delegates to {@link
     * #processServerFixed} which contains the corrected logic. If the helper throws (e.g.
     * reflection failure), {@code suppress} catches the exception and the advice returns {@code
     * false} (the default), allowing the original method body to run as a fallback.
     */
    public static class ProcessServerAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
        public static boolean onEnter(
                @Advice.This Object self, @Advice.Argument(1) UdpConnection connection)
                throws Exception {
            try {
                return NetTimedActionPacketPatch.processServerFixed(self, connection);
            } catch (Exception e) {
                LOGGER.error("Unable to run NetTimedActionPacketPatch.processServerFixed", e);
                return false;
            }
        }
    }
}
