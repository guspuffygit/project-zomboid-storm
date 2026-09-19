package io.pzstorm.storm.patch.bullet;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.lang.reflect.Method;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * {@code -Dstorm.bullet.record=<path>}: turns every native of the game's {@code
 * zombie.core.physics.Bullet} into a recording wrapper and redirects its {@code loadLibrary} to an
 * isolated copy of the class (see {@link BulletRecorder}). The isolated copy's bytes, with its
 * upcalls redirected to {@link BulletRecordUpcalls}, are built here from the same raw class bytes.
 *
 * <p>Runs on both client and server JVMs, but only when the property is set. Fail-soft: if either
 * class version cannot be built, the game class is returned unchanged and physics runs unrecorded.
 */
public class BulletRecordPatch extends StormClassTransformer {

    private volatile Throwable lastError;

    public BulletRecordPatch() {
        super(BulletRecorder.BULLET);
    }

    /** The failure that made the last {@link #transform} fall back to the raw class, or null. */
    public Throwable lastError() {
        return lastError;
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        try {
            byte[] isolated = isolatedCopy(rawClass);
            byte[] game = super.transform(rawClass);
            BulletRecorder.prepare(isolated);
            lastError = null;
            return game;
        } catch (Throwable t) {
            lastError = t;
            LOGGER.error(
                    "[bullet-record] could not build the recording classes; Bullet runs"
                            + " unrecorded",
                    t);
            return rawClass;
        }
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.method(ElementMatchers.isNative())
                .intercept(
                        MethodDelegation.withDefaultConfiguration()
                                .filter(ElementMatchers.named("call"))
                                .to(NativeInterceptor.class))
                .method(
                        ElementMatchers.named("loadLibrary")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(String.class))
                                .and(ElementMatchers.returns(void.class)))
                .intercept(
                        MethodDelegation.withDefaultConfiguration()
                                .filter(ElementMatchers.named("loadLibrary"))
                                .to(NativeInterceptor.class));
    }

    /** The isolated copy: natives kept, the two upcalls redirected to the recorder. */
    byte[] isolatedCopy(byte[] rawClass) {
        ClassFileLocator locator = defaultClassFileLocator(rawClass);
        TypePool pool = TypePool.Default.of(locator);
        DynamicType.Builder<Object> b =
                new ByteBuddy().redefine(pool.describe(className).resolve(), locator);
        b =
                b.method(
                                ElementMatchers.named("updatePhysicsForLevelIfNeeded")
                                        .and(ElementMatchers.isStatic())
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        int.class, int.class, int.class)))
                        .intercept(
                                MethodDelegation.withDefaultConfiguration()
                                        .filter(
                                                ElementMatchers.named(
                                                        "updatePhysicsForLevelIfNeeded"))
                                        .to(BulletRecordUpcalls.class))
                        .method(
                                ElementMatchers.named("onVehicleConstraintImpulse")
                                        .and(ElementMatchers.isStatic())
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        int.class,
                                                        int.class,
                                                        int.class,
                                                        float.class)))
                        .intercept(
                                MethodDelegation.withDefaultConfiguration()
                                        .filter(ElementMatchers.named("onVehicleConstraintImpulse"))
                                        .to(BulletRecordUpcalls.class));
        return b.make().getBytes();
    }

    public static final class NativeInterceptor {
        private NativeInterceptor() {}

        @RuntimeType
        public static Object call(@Origin Method method, @AllArguments Object[] args)
                throws Throwable {
            return BulletRecorder.call(method, args);
        }

        public static void loadLibrary(@Origin Class<?> owner, @Argument(0) String libName)
                throws Throwable {
            BulletRecorder.loadLibrary(owner, libName);
        }
    }
}
