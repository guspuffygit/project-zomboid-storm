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
 * Verifies that {@link LoadingQueueStateTcpDrainPatch} injects the TCP message drain into {@code
 * LoadingQueueState.update()} only — {@code enter()} sends the request and must stay vanilla.
 *
 * <p>Detection signal: the inlined advice calls the helper via INVOKESTATIC. Vanilla contains no
 * such call, so seeing it after the transform proves the advice landed.
 */
class LoadingQueueStateTcpDrainPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/gameStates/LoadingQueueState";
    private static final String HELPER_OWNER = "io/pzstorm/storm/client/StormLoginQueueOverTcp";
    private static final String HELPER_METHOD = "drainOnMainThread";

    private static final String TARGET_METHOD = "update";
    private static final String TARGET_DESC = "()Lzombie/gameStates/GameStateMachine$StateAction;";

    private static final String SIBLING_METHOD = "enter";
    private static final String SIBLING_DESC = "()V";

    @Test
    void patchInjectsHelperCallIntoTargetMethod() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new LoadingQueueStateTcpDrainPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCallsInMethod(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla "
                        + TARGET_METHOD
                        + " should not call "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD);
        assertTrue(
                countHelperCallsInMethod(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched "
                        + TARGET_METHOD
                        + " must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD
                        + " (advice not injected)");
        assertEquals(
                countHelperCallsInMethod(rawClass, SIBLING_METHOD, SIBLING_DESC),
                countHelperCallsInMethod(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Advice must not leak into " + SIBLING_METHOD);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countHelperCallsInMethod(byte[] classBytes, String method, String desc) {
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
                                if (!method.equals(name) || !desc.equals(descriptor)) {
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
