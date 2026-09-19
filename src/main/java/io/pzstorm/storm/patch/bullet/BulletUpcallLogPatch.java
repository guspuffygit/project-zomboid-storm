package io.pzstorm.storm.patch.bullet;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * {@code -Dstorm.bullet.record}: records the upcalls the Bullet library resolves by class name
 * ({@code DebugLog.nativeLog}, {@code SkeletonBone.getBoneName/getBoneOrdinal}). The advice only
 * writes while a recorded native call is the innermost frame of the thread, so the game's own Java
 * calls to these methods are not traced. Fail-soft like {@link BulletRecordPatch}.
 */
public class BulletUpcallLogPatch extends StormClassTransformer {

    private final boolean debugLog;
    private volatile Throwable lastError;

    private BulletUpcallLogPatch(String className, boolean debugLog) {
        super(className);
        this.debugLog = debugLog;
    }

    public static BulletUpcallLogPatch debugLog() {
        return new BulletUpcallLogPatch("zombie.debug.DebugLog", true);
    }

    public static BulletUpcallLogPatch skeletonBone() {
        return new BulletUpcallLogPatch("zombie.core.skinnedmodel.model.SkeletonBone", false);
    }

    public Throwable lastError() {
        return lastError;
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        try {
            byte[] out = super.transform(rawClass);
            lastError = null;
            return out;
        } catch (Throwable t) {
            lastError = t;
            LOGGER.error(
                    "[bullet-record] could not patch {}; its upcalls go unrecorded", className, t);
            return rawClass;
        }
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        if (debugLog) {
            return builder.visit(
                    Advice.to(NativeLogAdvice.class)
                            .on(
                                    ElementMatchers.named("nativeLog")
                                            .and(ElementMatchers.isStatic())
                                            .and(
                                                    ElementMatchers.takesArguments(
                                                            String.class,
                                                            String.class,
                                                            String.class))));
        }
        return builder.visit(
                        Advice.to(BoneNameAdvice.class)
                                .on(
                                        ElementMatchers.named("getBoneName")
                                                .and(ElementMatchers.isStatic())
                                                .and(ElementMatchers.takesArguments(int.class))))
                .visit(
                        Advice.to(BoneOrdinalAdvice.class)
                                .on(
                                        ElementMatchers.named("getBoneOrdinal")
                                                .and(ElementMatchers.isStatic())
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                String.class))));
    }

    public static class NativeLogAdvice {
        @Advice.OnMethodEnter
        public static boolean enter(@Advice.AllArguments Object[] args) {
            return BulletRecorder.adviceEnter(BulletRecorder.NATIVE_LOG, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter boolean entered, @Advice.Thrown Throwable thrown) {
            if (entered) {
                BulletRecorder.adviceExit(BulletRecorder.NATIVE_LOG, null, thrown);
            }
        }
    }

    public static class BoneNameAdvice {
        @Advice.OnMethodEnter
        public static boolean enter(@Advice.AllArguments Object[] args) {
            return BulletRecorder.adviceEnter(BulletRecorder.GET_BONE_NAME, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.Enter boolean entered,
                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object ret,
                @Advice.Thrown Throwable thrown) {
            if (entered) {
                BulletRecorder.adviceExit(BulletRecorder.GET_BONE_NAME, ret, thrown);
            }
        }
    }

    public static class BoneOrdinalAdvice {
        @Advice.OnMethodEnter
        public static boolean enter(@Advice.AllArguments Object[] args) {
            return BulletRecorder.adviceEnter(BulletRecorder.GET_BONE_ORDINAL, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.Enter boolean entered,
                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object ret,
                @Advice.Thrown Throwable thrown) {
            if (entered) {
                BulletRecorder.adviceExit(BulletRecorder.GET_BONE_ORDINAL, ret, thrown);
            }
        }
    }
}
