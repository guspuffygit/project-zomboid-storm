package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.Transaction;
import zombie.network.fields.character.PlayerID;

/**
 * Fixes a server-side bug in {@code ItemTransactionPacket.processServer()} where client-initiated
 * cancel (Reject) removes transactions by byte {@code id} alone, without checking which player owns
 * them.
 *
 * <p>{@code Transaction.lastId} is a single {@code byte} that wraps every 256 transactions. On a
 * high-player-count server, different players will frequently share the same byte ID for unrelated
 * transactions. When Player A cancels transaction {@code id=42}, the vanilla code calls {@code
 * TransactionManager.removeItemTransaction(42, true)}, which executes {@code
 * transactions.removeIf(r -> r.id == 42)} &mdash; removing <em>every</em> transaction with that ID,
 * including Player B's active transfer.
 *
 * <p>This patch intercepts the Reject branch of {@code processServer()} and replaces the removal
 * with a compound match on both {@code id} and {@code playerId}, so only the cancelling player's
 * own transaction is removed.
 *
 * <p>The Request branch (new transaction acceptance) is left untouched and handled by the original
 * method.
 *
 * <p><b>Registration order:</b> register before {@link
 * io.pzstorm.storm.patch.networking.PacketReceivedPatch} so that the fix runs inside any generic
 * packet event wrapper.
 *
 * <p><b>Reflection:</b> the {@code id}, {@code state}, and {@code playerId} fields are declared in
 * the {@code Transaction} superclass. Byte Buddy's {@code @Advice.FieldValue} does not traverse the
 * superclass hierarchy, so these are accessed via cached reflection handles.
 */
public class ItemTransactionPacketPatch extends StormClassTransformer {

    public ItemTransactionPacketPatch() {
        super("zombie.network.packets.ItemTransactionPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(ProcessServerAdvice.class).on(ElementMatchers.named("processServer")));
    }

    // -- Cached reflection handles --

    private static volatile Field txStateField;
    private static volatile Field txIdField;
    private static volatile Field txPlayerIdField;
    private static volatile Field tmTransactionsField;

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (txStateField != null) return;

        Class<?> txClass = Class.forName("zombie.core.Transaction");
        Field idf = txClass.getDeclaredField("id");
        idf.setAccessible(true);
        Field sf = txClass.getDeclaredField("state");
        sf.setAccessible(true);
        Field pf = txClass.getDeclaredField("playerId");
        pf.setAccessible(true);

        Class<?> tmClass = Class.forName("zombie.core.TransactionManager");
        Field tf = tmClass.getDeclaredField("transactions");
        tf.setAccessible(true);

        // Publish all at once — volatile write of txStateField acts as release fence
        txIdField = idf;
        txPlayerIdField = pf;
        tmTransactionsField = tf;
        txStateField = sf;
    }

    /**
     * Handles the Reject branch of {@code processServer()} with player-aware transaction removal.
     *
     * @return {@code true} if the Reject was handled (skip original), {@code false} for any other
     *     state (fall through to original method).
     */
    @SuppressWarnings("unchecked")
    public static boolean handleReject(Object self) throws ReflectiveOperationException {
        if (txStateField == null) {
            initFieldHandles();
        }

        Transaction.TransactionState state = (Transaction.TransactionState) txStateField.get(self);
        if (state != Transaction.TransactionState.Reject) {
            return false;
        }

        byte id = txIdField.getByte(self);
        PlayerID playerId = (PlayerID) txPlayerIdField.get(self);
        short playerOnlineId = playerId.getID();

        ConcurrentLinkedQueue<Transaction> transactions =
                (ConcurrentLinkedQueue<Transaction>) tmTransactionsField.get(null);

        // Player-aware removal: match BOTH byte id AND player online ID
        transactions.removeIf(
                t -> {
                    try {
                        byte tId = txIdField.getByte(t);
                        PlayerID tPlayerId = (PlayerID) txPlayerIdField.get(t);
                        return tId == id && tPlayerId.getID() == playerOnlineId;
                    } catch (ReflectiveOperationException e) {
                        LOGGER.error("Reflection error during player-aware transaction removal", e);
                        return false;
                    }
                });

        LOGGER.trace("Removed transaction id={} for player={} (player-aware)", id, playerOnlineId);
        return true;
    }

    /**
     * Advice inlined into {@code ItemTransactionPacket.processServer()}. For Reject state,
     * delegates to {@link #handleReject} which performs player-aware removal. For all other states,
     * returns {@code false} to let the original method handle it. If the advice throws, {@code
     * suppress} catches the exception and falls through to the original (buggy) method as a safety
     * net.
     */
    public static class ProcessServerAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
        public static boolean onEnter(@Advice.This Object self) throws Exception {
            try {
                return ItemTransactionPacketPatch.handleReject(self);
            } catch (Exception e) {
                LOGGER.error("Error in ItemTransactionPacketPatch.handleReject", e);
                return false;
            }
        }
    }
}
