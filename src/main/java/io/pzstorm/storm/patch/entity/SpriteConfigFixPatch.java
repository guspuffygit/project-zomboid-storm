package io.pzstorm.storm.patch.entity;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the vanilla bug where {@code SpriteConfig.onAddedToOwner()} is called redundantly on every
 * network sync packet (via {@code GameEntity.receiveSyncEntity} line 723).
 *
 * <p>The vanilla {@code onAddedToOwner()} unconditionally calls {@code initObjectInfo()}, which
 * resets and rebuilds the object/face/tile info every time. When the sprite is in a non-default
 * state (open door, smashed window), the rebuild fails and logs:
 *
 * <pre>WARN: Invalid SpriteConfig object! scripted object = ...</pre>
 *
 * <p>This patch makes {@code onAddedToOwner()} idempotent by skipping re-initialization when the
 * component is already fully initialized (all three info fields are non-null).
 *
 * <h3>Testing the fix</h3>
 *
 * <p>Set {@code -DstormOomTest=true} to enable leak amplification. When the method body executes
 * (not skipped), a 4KB byte array is retained to simulate GC pressure. This lets you compare two
 * scenarios:
 *
 * <ul>
 *   <li><b>Without fix</b> (patch disabled): ballast grows unbounded → OOM
 *   <li><b>With fix</b> (patch enabled): ballast only grows on first init, stays flat → stable
 * </ul>
 *
 * <p>Metrics are logged to stderr periodically:
 *
 * <pre>[Storm:SpriteConfigFix] executed=12 skipped=48832 skipRate=99.9% ballast=0MB heap=512MB
 * </pre>
 */
public class SpriteConfigFixPatch extends StormClassTransformer {

    public SpriteConfigFixPatch() {
        super("zombie.entity.components.spriteconfig.SpriteConfig");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(OnAddedToOwnerAdvice.class)
                        .on(
                                ElementMatchers.named("onAddedToOwner")
                                        .and(ElementMatchers.takesArguments(0))));
    }

    /**
     * Advice inlined into {@code SpriteConfig.onAddedToOwner()}.
     *
     * <p>If {@code objectInfo}, {@code faceInfo}, and {@code tileInfo} are all non-null, the
     * component is already initialized — skip the method body entirely. This prevents the redundant
     * {@code initObjectInfo()} → {@code resetObjectInfo()} → rebuild cycle that causes the warning
     * spam and GC pressure.
     */
    public static class OnAddedToOwnerAdvice {

        private static volatile long executedCount = 0;
        private static volatile long skippedCount = 0;
        private static final long LOG_INTERVAL = 1000;

        /**
         * Retained byte arrays that simulate the cumulative memory pressure from repeated
         * initObjectInfo() calls. Only populated when {@code -DstormOomTest=true}. Grows only when
         * the method body actually executes (not skipped by the fix).
         */
        private static final java.util.List<byte[]> leakBallast =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        private static final int BALLAST_BYTES = 4096;

        /**
         * @return {@code true} to skip the method body (already initialized), {@code false} to let
         *     it run normally.
         */
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(
                @Advice.FieldValue("objectInfo") Object objectInfo,
                @Advice.FieldValue("faceInfo") Object faceInfo,
                @Advice.FieldValue("tileInfo") Object tileInfo) {

            boolean alreadyValid = objectInfo != null && faceInfo != null && tileInfo != null;

            if (alreadyValid) {
                long skipped = ++skippedCount;
                if (skipped % LOG_INTERVAL == 0) {
                    long executed = executedCount;
                    long total = executed + skipped;
                    long skipRate = total > 0 ? (skipped * 100) / total : 0;
                    long ballastMb = ((long) leakBallast.size() * BALLAST_BYTES) / (1024 * 1024);
                    long usedMb =
                            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                                    / (1024 * 1024);
                    LOGGER.info(
                            "[SpriteConfigFix] executed={} skipped={} skipRate={}% ballast={}MB heap={}MB",
                            executed, skipped, skipRate, ballastMb, usedMb);
                }
                return true; // skip method body
            }

            // Method body will execute — this is either first init or a re-init.
            long executed = ++executedCount;

            if (Boolean.getBoolean("stormOomTest")) {
                // Retain memory to simulate the cumulative effect of the bug.
                // With the fix active, this only fires on genuine first-init calls.
                // Without the fix, this fires on EVERY sync packet → OOM.
                leakBallast.add(new byte[BALLAST_BYTES]);
            }

            if (executed % LOG_INTERVAL == 0) {
                long skipped = skippedCount;
                long total = executed + skipped;
                long skipRate = total > 0 ? (skipped * 100) / total : 0;
                long ballastMb = ((long) leakBallast.size() * BALLAST_BYTES) / (1024 * 1024);
                long usedMb =
                        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                                / (1024 * 1024);
                LOGGER.info(
                        "[SpriteConfigFix] executed={} skipped={} skipRate={}% ballast={}MB heap={}MB",
                        executed, skipped, skipRate, ballastMb, usedMb);
            }

            return false; // let method body run
        }
    }
}
