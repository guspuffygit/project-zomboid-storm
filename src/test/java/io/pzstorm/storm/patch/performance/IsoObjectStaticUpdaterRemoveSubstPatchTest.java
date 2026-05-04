package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.iso.StormCellMembership;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Asserts that {@link IsoObjectStaticUpdaterRemoveSubstPatch} rewrites only the {@code
 * ArrayList.remove(Object)} call inside the no-arg {@code IsoObject.removeFromWorld()} method,
 * substituting it with an {@code INVOKESTATIC} on {@link
 * StormCellMembership#removeStaticUpdaterFromList(java.util.ArrayList, Object)}.
 *
 * <p>Other methods on {@code IsoObject} that also call {@code ArrayList.remove(Object)} (e.g.
 * {@code removeFromSquare}) must remain unchanged — the substitution scope is the no-arg {@code
 * removeFromWorld()} only.
 */
class IsoObjectStaticUpdaterRemoveSubstPatchTest implements UnitTest {

    private static final String ISO_OBJECT = "zombie/iso/IsoObject";
    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String REMOVE_DESC = "(Ljava/lang/Object;)Z";
    private static final String HELPER_INTERNAL =
            StormCellMembership.class.getName().replace('.', '/');
    private static final String HELPER_DESC = "(Ljava/util/ArrayList;Ljava/lang/Object;)Z";

    @Test
    void patchRewritesOnlyTheNoArgRemoveFromWorld() throws Exception {
        String resourcePath = ISO_OBJECT + ".class";
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "IsoObject.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new IsoObjectStaticUpdaterRemoveSubstPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Counts counts = countInvocations(transformed);

        assertEquals(
                1,
                counts.removeFromWorldHelperCalls,
                "removeFromWorld() should contain exactly one INVOKESTATIC to "
                        + "StormCellMembership.removeStaticUpdaterFromList; got "
                        + counts.removeFromWorldHelperCalls);
        assertEquals(
                0,
                counts.removeFromWorldArrayListRemoveCalls,
                "removeFromWorld() should have no remaining INVOKEVIRTUAL on "
                        + "ArrayList.remove(Object) after substitution");

        // removeFromSquare() also calls ArrayList.remove(Object) (on getSpecialObjects()) — that
        // call must be left untouched because our scope is named("removeFromWorld").takesArgs(0).
        assertTrue(
                counts.otherMethodArrayListRemoveCalls >= 1,
                "Other methods on IsoObject that call ArrayList.remove(Object) must not be"
                        + " rewritten (got "
                        + counts.otherMethodArrayListRemoveCalls
                        + ")");
        assertEquals(
                0,
                counts.otherMethodHelperCalls,
                "Helper substitution must not leak into methods outside removeFromWorld()");
    }

    private static Counts countInvocations(byte[] classBytes) {
        Counts counts = new Counts();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                final boolean isNoArgRemoveFromWorld =
                                        "removeFromWorld".equals(name) && "()V".equals(descriptor);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        boolean isHelperCall =
                                                opcode == Opcodes.INVOKESTATIC
                                                        && HELPER_INTERNAL.equals(owner)
                                                        && "removeStaticUpdaterFromList"
                                                                .equals(mName)
                                                        && HELPER_DESC.equals(mDesc);
                                        boolean isArrayListRemove =
                                                opcode == Opcodes.INVOKEVIRTUAL
                                                        && ARRAY_LIST.equals(owner)
                                                        && "remove".equals(mName)
                                                        && REMOVE_DESC.equals(mDesc);
                                        if (isHelperCall) {
                                            if (isNoArgRemoveFromWorld) {
                                                counts.removeFromWorldHelperCalls++;
                                            } else {
                                                counts.otherMethodHelperCalls++;
                                            }
                                        }
                                        if (isArrayListRemove) {
                                            if (isNoArgRemoveFromWorld) {
                                                counts.removeFromWorldArrayListRemoveCalls++;
                                            } else {
                                                counts.otherMethodArrayListRemoveCalls++;
                                            }
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class Counts {
        int removeFromWorldHelperCalls;
        int removeFromWorldArrayListRemoveCalls;
        int otherMethodHelperCalls;
        int otherMethodArrayListRemoveCalls;
    }
}
