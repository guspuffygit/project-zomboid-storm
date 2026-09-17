package io.pzstorm.storm.patch.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ModelManagerReloadWaitPatch} hooks {@code
 * ModelManager.initAnimationMeshes(boolean)} and nothing else.
 *
 * <p>Detection signal: the inlined advice calls {@code ModelMeshReloadWait.waitForMeshes} via
 * INVOKESTATIC; vanilla contains no such call.
 */
class ModelManagerReloadWaitPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/core/skinnedmodel/ModelManager";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/advice/client/modelmeshwait/ModelMeshReloadWait";
    private static final String HELPER_METHOD = "waitForMeshes";

    private static final String TARGET_METHOD = "initAnimationMeshes";
    private static final String TARGET_DESC = "(Z)V";

    @Test
    void patchInjectsWaitIntoInitAnimationMeshesOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new ModelManagerReloadWaitPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla initAnimationMeshes must not already call " + HELPER_OWNER);
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched initAnimationMeshes(boolean) must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD);
        assertEquals(
                0, countHelperCalls(transformed, null, null), "No other method may carry the hook");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** Counts hook calls in the named method, or in every method except the target when null. */
    private static int countHelperCalls(byte[] classBytes, String method, String desc) {
        int[] hits = new int[1];
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
                                boolean isTarget =
                                        TARGET_METHOD.equals(name)
                                                && TARGET_DESC.equals(descriptor);
                                if (method == null ? isTarget : !isTarget) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && HELPER_OWNER.equals(owner)
                                                && HELPER_METHOD.equals(mName)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return hits[0];
    }
}
