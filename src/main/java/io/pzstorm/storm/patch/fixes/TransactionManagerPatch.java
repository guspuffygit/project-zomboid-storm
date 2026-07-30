package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.transfer.StormTransferHandler;
import java.lang.reflect.Field;
import java.util.List;
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
 * <p>Since PZ 42.20.0 a {@code Transaction} holds a list of {@code Transaction$TransactionEntry}
 * batch entries (each with its own {@code itemId}/{@code sourceId}/{@code destinationId}) instead
 * of one top-level container pair. The vanilla consistency-check loop iterates every entry of every
 * in-flight transaction and includes the condition {@code entry.sourceId.getContainer() == null} as
 * a standalone {@code ||} branch. When any entry's container is unloaded (player disconnects, chunk
 * unloads), this condition evaluates to {@code true} for every subsequent consistency check,
 * producing cascade rejections that prevent all item transfers server-wide until the stale
 * transaction's {@code endTime} lapses.
 *
 * <p>This patch intercepts {@code TransactionManager.update()} with a {@code @OnMethodEnter} advice
 * that removes stale transactions from the queue <em>before</em> the vanilla processing loop runs.
 * A transaction is considered stale when it is in {@code Accept} state but any entry's source or
 * destination container returns {@code null} <em>and that leg is supposed to have a container at
 * all</em> (see {@link #isLegStale}). These transactions would fail in {@code Transaction.update()}
 * anyway &mdash; this patch simply catches them one tick earlier, before they can poison the
 * consistency check. Every entry of a batch shares the same source and destination containers (the
 * client only merges actions with identical containers), so sweeping the whole transaction on the
 * first stale entry matches vanilla batch semantics.
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

    // -- Cached reflection handles (protected/private members across packages) --

    private static volatile Field txStateField;
    private static volatile Field txEntriesField;
    private static volatile Field entrySourceIdField;
    private static volatile Field entryDestinationIdField;
    private static volatile Field tmTransactionsField;

    private static synchronized void initFieldHandles() throws ReflectiveOperationException {
        if (txStateField != null) return;

        Class<?> txClass = Class.forName("zombie.core.Transaction");
        Field sf = txClass.getDeclaredField("state");
        sf.setAccessible(true);
        Field ef = txClass.getDeclaredField("entries");
        ef.setAccessible(true);

        Class<?> entryClass = Class.forName("zombie.core.Transaction$TransactionEntry");
        Field esf = entryClass.getDeclaredField("sourceId");
        esf.setAccessible(true);
        Field edf = entryClass.getDeclaredField("destinationId");
        edf.setAccessible(true);

        Class<?> tmClass = Class.forName("zombie.core.TransactionManager");
        Field tf = tmClass.getDeclaredField("transactions");
        tf.setAccessible(true);

        // Publish all at once — volatile write of txStateField acts as release fence
        txEntriesField = ef;
        entrySourceIdField = esf;
        entryDestinationIdField = edf;
        tmTransactionsField = tf;
        txStateField = sf;
    }

    /**
     * Removes stale transactions from the server's transaction queue. A transaction is stale when
     * it is in {@code Accept} state but any batch entry's source or destination container has been
     * unloaded (returns {@code null} from {@link ContainerID#getContainer()}).
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

            transactions.removeIf(TransactionManagerPatch::isStale);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to access transaction queue for stale cleanup", e);
        }
    }

    /**
     * A leg counts as stale only when it is supposed to resolve to a container and does not.
     *
     * <p>{@code Undefined} legs have no container by definition. {@code IsoObject} legs never
     * resolve one either: {@code ContainerID.parse()} hardcodes {@code containerIndex = -1} for
     * that type and {@code IsoObject.getContainerByIndex(-1)} always returns {@code null}. Those
     * legs identify a moveable object, and {@code TransactionProcessor.lua} reads {@code
     * getObject()} from them rather than {@code getContainer()} &mdash; so sweeping them killed
     * every {@code pickUpMoveable} / {@code placeMoveable} / {@code rotateMoveable} / {@code
     * scrapMoveable} transaction one tick after acceptance, server-wide.
     */
    private static boolean isLegStale(ContainerID id) {
        ContainerID.ContainerType type = id.getContainerType();
        if (type == ContainerID.ContainerType.Undefined
                || type == ContainerID.ContainerType.IsoObject) {
            return false;
        }
        return id.getContainer() == null;
    }

    private static boolean isStale(Transaction t) {
        try {
            Transaction.TransactionState state = (Transaction.TransactionState) txStateField.get(t);
            if (state != Transaction.TransactionState.Accept) {
                return false;
            }

            List<?> entries = (List<?>) txEntriesField.get(t);
            for (Object entry : entries) {
                ContainerID sourceId = (ContainerID) entrySourceIdField.get(entry);
                ContainerID destinationId = (ContainerID) entryDestinationIdField.get(entry);

                boolean sourceStale = isLegStale(sourceId);
                boolean destStale = isLegStale(destinationId);

                if (sourceStale || destStale) {
                    LOGGER.debug(
                            "Removing stale transaction: container unloaded"
                                    + " (source={} {}, dest={} {})",
                            sourceId.getContainerType(),
                            sourceStale ? "null" : "ok",
                            destinationId.getContainerType(),
                            destStale ? "null" : "ok");
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Reflection error during stale transaction cleanup", e);
            return false;
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
