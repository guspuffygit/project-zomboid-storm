package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.transfer.StormTransferHandler;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.Transaction;
import zombie.network.GameServer;
import zombie.network.fields.ContainerID;

/**
 * Fixes a server-side bug in {@code TransactionManager.isConsistent()} where a stale transaction
 * (one whose source or destination container has been unloaded from memory) causes <em>all</em> new
 * inventory-bound transfers from <em>every</em> player to be rejected.
 *
 * <p>The vanilla consistency-check loop iterates every in-flight transaction and includes the
 * condition {@code transaction.sourceId.getContainer() == null} as a standalone {@code ||} branch.
 * When any transaction's container is unloaded (player disconnects, chunk unloads), this condition
 * evaluates to {@code true} for every subsequent consistency check, producing cascade rejections
 * that prevent all item transfers server-wide.
 *
 * <p>This patch intercepts {@code TransactionManager.update()} with a {@code @OnMethodEnter} advice
 * that removes stale transactions from the queue <em>before</em> the vanilla processing loop runs.
 * A transaction is considered stale when it is in {@code Accept} state but its source or
 * destination container returns {@code null}. These transactions would fail in {@code
 * Transaction.update()} anyway &mdash; this patch simply catches them one tick earlier, before they
 * can poison the consistency check.
 *
 * <p>The {@code suppress = Throwable.class} annotation ensures that if the cleanup throws, the
 * vanilla {@code update()} method still runs normally as a safety net.
 */
public class TransactionManagerPatch extends StormClassTransformer {

    public TransactionManagerPatch() {
        super("zombie.core.TransactionManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(UpdateAdvice.class)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }

    // -- Cached reflection handles (protected/private fields across packages) --

    private static volatile Field txStateField;
    private static volatile Field txSourceIdField;
    private static volatile Field txDestinationIdField;
    private static volatile Field tmTransactionsField;

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (txStateField != null) return;

        Class<?> txClass = Class.forName("zombie.core.Transaction");
        Field sf = txClass.getDeclaredField("state");
        sf.setAccessible(true);
        Field srcF = txClass.getDeclaredField("sourceId");
        srcF.setAccessible(true);
        Field destF = txClass.getDeclaredField("destinationId");
        destF.setAccessible(true);

        Class<?> tmClass = Class.forName("zombie.core.TransactionManager");
        Field tf = tmClass.getDeclaredField("transactions");
        tf.setAccessible(true);

        // Publish all at once — volatile write of txStateField acts as release fence
        txSourceIdField = srcF;
        txDestinationIdField = destF;
        tmTransactionsField = tf;
        txStateField = sf;
    }

    /**
     * Removes stale transactions from the server's transaction queue. A transaction is stale when
     * it is in {@code Accept} state but its source or destination container has been unloaded
     * (returns {@code null} from {@link ContainerID#getContainer()}).
     */
    @SuppressWarnings("unchecked")
    public static void cleanStaleTransactions() {
        if (!GameServer.server) return;

        try {
            if (txStateField == null) {
                initFieldHandles();
            }

            ConcurrentLinkedQueue<Transaction> transactions =
                    (ConcurrentLinkedQueue<Transaction>) tmTransactionsField.get(null);

            transactions.removeIf(
                    t -> {
                        try {
                            Transaction.TransactionState state =
                                    (Transaction.TransactionState) txStateField.get(t);
                            if (state != Transaction.TransactionState.Accept) {
                                return false;
                            }

                            ContainerID sourceId = (ContainerID) txSourceIdField.get(t);
                            ContainerID destinationId = (ContainerID) txDestinationIdField.get(t);

                            boolean sourceStale = sourceId.getContainer() == null;
                            boolean destStale =
                                    destinationId.getContainerType()
                                                    != ContainerID.ContainerType.Undefined
                                            && destinationId.getContainer() == null;

                            if (sourceStale || destStale) {
                                LOGGER.debug(
                                        "Removing stale transaction: container unloaded"
                                                + " (source={}, dest={})",
                                        sourceStale ? "null" : "ok",
                                        destStale ? "null" : "ok");
                                return true;
                            }
                            return false;
                        } catch (ReflectiveOperationException e) {
                            LOGGER.error("Reflection error during stale transaction cleanup", e);
                            return false;
                        }
                    });
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to access transaction queue for stale cleanup", e);
        }
    }

    /**
     * Advice inlined into {@code TransactionManager.update()}. Runs before the vanilla method body
     * to clean up stale transactions, preventing them from poisoning the consistency check.
     */
    public static class UpdateAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter() {
            TransactionManagerPatch.cleanStaleTransactions();
            StormTransferHandler.processPending();
        }
    }
}
