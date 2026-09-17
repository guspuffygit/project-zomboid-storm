package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UnknownFormatConversionException;
import org.junit.jupiter.api.Test;

/**
 * Drives the real {@code DebugLogStream.getFormattedOutputStr} — vanilla and patched, loaded
 * parent-last from the same bytes — with the exact {@code %ld} format string {@code
 * GameEntityManager.checkEntityIDChange} logs with. The vanilla control must throw; that throw is
 * what tears down {@code ServerCell.Unload} on a live server, and if it ever stops throwing the
 * patch has nothing left to fix.
 */
class DebugLogStreamFormatPatchTest implements UnitTest {

    private static final String TARGET = "zombie.debug.DebugLogStream";
    private static final String TARGET_RES = "zombie/debug/DebugLogStream.class";
    private static final String BAD_FORMAT = "idToEntityMap(%ld)=%s, expected %s";

    @Test
    void vanillaControlThrowsOnTheEntityManagerFormatString() throws Exception {
        Method format = new Harness(readClassBytes()).format;
        InvocationTargetException thrown =
                assertThrows(
                        InvocationTargetException.class,
                        () -> format.invoke(null, BAD_FORMAT, new Object[] {1L, "a", "b"}));
        assertTrue(
                thrown.getCause() instanceof UnknownFormatConversionException,
                "control: vanilla must throw on %ld, got " + thrown.getCause());
    }

    @Test
    void patchedReturnsTheRawLineInsteadOfThrowing() throws Exception {
        byte[] patched = new DebugLogStreamFormatPatch().transform(readClassBytes());
        Method format = new Harness(patched).format;

        String line = (String) format.invoke(null, BAD_FORMAT, new Object[] {1L, "a", "b"});
        assertTrue(line.startsWith(BAD_FORMAT), line);
        assertTrue(line.contains("[1, a, b]"), line);
        assertTrue(line.contains("UnknownFormatConversionException"), line);

        assertEquals(
                "x=5 y=six",
                format.invoke(null, "x=%d y=%s", new Object[] {5, "six"}),
                "a well-formed line still formats vanilla");
        assertEquals(
                "no params",
                format.invoke(null, "no params", new Object[0]),
                "the no-params path is untouched");
    }

    @Test
    void patchRefusesIfTheFormatterMethodIsGone() {
        assertThrows(
                RuntimeException.class,
                () -> new DebugLogStreamFormatPatch().transform(renamedTarget()));
    }

    private static byte[] readClassBytes() throws Exception {
        try (InputStream is =
                DebugLogStreamFormatPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(TARGET_RES)) {
            assertNotNull(is, TARGET_RES + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** {@code DebugLogStream} bytes with {@code getFormattedOutputStr} renamed away. */
    private static byte[] renamedTarget() throws Exception {
        net.bytebuddy.jar.asm.ClassReader reader =
                new net.bytebuddy.jar.asm.ClassReader(readClassBytes());
        net.bytebuddy.jar.asm.ClassWriter writer = new net.bytebuddy.jar.asm.ClassWriter(0);
        reader.accept(
                new net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9, writer) {
                    @Override
                    public net.bytebuddy.jar.asm.MethodVisitor visitMethod(
                            int access, String name, String desc, String sig, String[] exceptions) {
                        String n = "getFormattedOutputStr".equals(name) ? "somethingElse" : name;
                        return super.visitMethod(access, n, desc, sig, exceptions);
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static final class Harness extends ClassLoader {
        private final byte[] target;
        final Method format;

        Harness(byte[] target) throws Exception {
            super(DebugLogStreamFormatPatchTest.class.getClassLoader());
            this.target = target;
            Class<?> c = loadClass(TARGET);
            this.format =
                    c.getDeclaredMethod("getFormattedOutputStr", Object.class, Object[].class);
            this.format.setAccessible(true);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (TARGET.equals(name)) {
                Class<?> already = findLoadedClass(name);
                if (already != null) {
                    return already;
                }
                return defineClass(name, target, 0, target.length);
            }
            return super.loadClass(name, resolve);
        }
    }
}
