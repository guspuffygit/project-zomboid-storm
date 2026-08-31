package io.pzstorm.storm.patch.popman;

import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;

/**
 * Establishes whether the popman rewrite can ship as an ordinary Storm transformer. A native method
 * has no body and carries ACC_NATIVE, so redefining one is a different problem from redefining a
 * normal method, and every later popman patch assumes it works.
 */
class NativeMethodReplacementFeasibilityTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/popman/ZombiePopulationManager";

    static class NativeHolder {
        static native int n_addZombie(float x, float y, int flags);
    }

    public static class Substitute {
        public static int intercept(@Argument(2) int flags) {
            return flags + 1000;
        }
    }

    @Test
    void replacesNativeMethodBodyAndClearsNativeFlag() throws Exception {
        String className = NativeHolder.class.getName();
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(getClass().getClassLoader());
        TypePool typePool = TypePool.Default.of(locator);

        DynamicType.Unloaded<Object> made =
                new ByteBuddy()
                        .redefine(typePool.describe(className).resolve(), locator)
                        .method(named("n_addZombie"))
                        .intercept(MethodDelegation.to(Substitute.class))
                        .make();

        Class<?> patched =
                made.load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                        .getLoaded();

        Method m = patched.getDeclaredMethod("n_addZombie", float.class, float.class, int.class);
        m.setAccessible(true);

        assertFalse(Modifier.isNative(m.getModifiers()), "ACC_NATIVE must be cleared");
        assertEquals(1042, m.invoke(null, 1.0f, 2.0f, 42), "patched body must run");
    }

    /**
     * The toy case above proves the mechanism; this proves it survives the real class, whose 28
     * native methods span far more signature shapes (ByteBuffer, arrays, String, mixed primitives).
     */
    @Test
    void clearsNativeFlagOnEveryPopulationManagerNative() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");

        int vanillaNatives = countNatives(rawClass);
        assertTrue(
                vanillaNatives >= 25,
                "expected the vanilla class to declare the popman JNI surface, found "
                        + vanillaNatives);

        ClassFileLocator locator =
                new ClassFileLocator.Compound(
                        ClassFileLocator.Simple.of(TARGET_CLASS.replace('/', '.'), rawClass),
                        ClassFileLocator.ForClassLoader.of(getClass().getClassLoader()));
        TypePool typePool = TypePool.Default.of(locator);

        byte[] transformed =
                new ByteBuddy()
                        .redefine(
                                typePool.describe(TARGET_CLASS.replace('/', '.')).resolve(),
                                locator)
                        .method(isNative().and(nameStartsWith("n_")))
                        .intercept(StubMethod.INSTANCE)
                        .make()
                        .getBytes();

        assertNotNull(transformed);
        assertEquals(
                0,
                countNatives(transformed),
                "every n_* native must lose ACC_NATIVE and gain a body");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countNatives(byte[] classBytes) {
        int[] hits = new int[1];
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                if ((access & Opcodes.ACC_NATIVE) != 0 && name.startsWith("n_")) {
                                    hits[0]++;
                                }
                                return super.visitMethod(access, name, desc, sig, ex);
                            }
                        },
                        0);
        return hits[0];
    }
}
