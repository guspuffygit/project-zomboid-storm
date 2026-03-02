package io.pzstorm.storm.patch.entity;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.logging.ZomboidLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Patches {@code zombie.entity.GameEntity#receiveSyncEntity} to track and amplify the duplicate
 * {@code onAddedToOwner()} call bug.
 *
 * <p>The vanilla code at line 723 calls {@code component.onAddedToOwner()} on every sync packet
 * even for components that already exist on the entity. For SpriteConfig components, this triggers
 * {@code initObjectInfo()} repeatedly, which:
 *
 * <ul>
 *   <li>Logs "Invalid SpriteConfig object!" warnings via string concatenation
 *   <li>Creates garbage from repeated HashMap lookups and string ops
 *   <li>On servers with many objects, compounds into GC pressure and potential OOM
 * </ul>
 *
 * <p>This patch intercepts {@code receiveSyncEntity} to:
 *
 * <ol>
 *   <li>Count every invocation (tracking sync frequency)
 *   <li>Optionally amplify memory pressure by retaining references that simulate the cumulative
 *       effect of the bug at scale
 * </ol>
 *
 * <p>Enable amplification mode by setting {@code -DstormOomTest=true}. Without that flag, this
 * patch only logs metrics.
 */
public class GameEntityPatch extends StormClassTransformer {

    public GameEntityPatch() {
        super("zombie.entity.GameEntity");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(ReceiveSyncAdvice.class).on(ElementMatchers.named("receiveSyncEntity")));
    }

    /**
     * Advice inlined into {@code receiveSyncEntity}. Tracks call frequency and optionally amplifies
     * memory pressure to reproduce OOM conditions faster.
     *
     * <p>All state is kept in static fields because advice classes are inlined by ByteBuddy — they
     * cannot reference Storm classes loaded by a different classloader.
     */
    public static class ReceiveSyncAdvice {

        /** Total number of receiveSyncEntity calls observed. */
        private static volatile long syncCallCount = 0;

        /** Interval at which to print metrics. */
        private static final long LOG_INTERVAL = 1000;

        /** Interval in seconds between background log messages. */
        private static final long THREAD_LOG_INTERVAL_SECONDS = 10;

        /**
         * Retained byte arrays that simulate the cumulative memory pressure from the repeated
         * onAddedToOwner() bug. Only populated when {@code -DstormOomTest=true}.
         */
        private static final java.util.List<byte[]> leakBallast =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        /** Bytes to allocate per sync call in amplification mode. */
        private static final int BALLAST_BYTES = 4096;

        static {
            ZomboidLogger.LOGGER.info(
                    "[Storm:GameEntityPatch] ReceiveSyncAdvice class loaded — starting monitor thread");
            Thread monitor =
                    new Thread(
                            () -> {
                                while (true) {
                                    try {
                                        Thread.sleep(THREAD_LOG_INTERVAL_SECONDS * 1000);
                                    } catch (InterruptedException e) {
                                        break;
                                    }
                                    long count = syncCallCount;
                                    long usedMb =
                                            (Runtime.getRuntime().totalMemory()
                                                            - Runtime.getRuntime().freeMemory())
                                                    / (1024 * 1024);
                                    long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
                                    long ballastMb =
                                            ((long) leakBallast.size() * BALLAST_BYTES)
                                                    / (1024 * 1024);
                                    boolean amplify = Boolean.getBoolean("stormOomTest");
                                    ZomboidLogger.LOGGER.info(
                                            "[Storm:GameEntityPatch][monitor] calls={} heap={}/{}MB ballast={}MB amplify={}",
                                            count,
                                            usedMb,
                                            maxMb,
                                            ballastMb,
                                            amplify);
                                }
                            },
                            "Storm-GameEntityPatch-Monitor");
            monitor.setDaemon(true);
            monitor.start();
        }

        @Advice.OnMethodEnter
        public static void onEnter() {
            long count = ++syncCallCount;

            boolean amplify = Boolean.getBoolean("stormOomTest");

            if (amplify) {
                LOGGER.debug("AMPLIFYING");
                // Retain a chunk of memory on every sync call to accelerate OOM.
                // This simulates the cumulative effect of thousands of objects
                // each re-running initObjectInfo() with string concat + logging.
                leakBallast.add(new byte[BALLAST_BYTES]);
            }

            if (count % LOG_INTERVAL == 0) {
                long usedMb =
                        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                                / (1024 * 1024);
                long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
                long ballastMb = ((long) leakBallast.size() * BALLAST_BYTES) / (1024 * 1024);
                ZomboidLogger.LOGGER.info(
                        "[Storm:GameEntityPatch] receiveSyncEntity calls={} heap={}/{}MB ballast={}MB amplify={}",
                        count,
                        usedMb,
                        maxMb,
                        ballastMb,
                        amplify);
            }
        }
    }
}
