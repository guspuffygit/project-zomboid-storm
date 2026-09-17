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
 * Verifies that {@link LoginQueueOverTcpPatch} injects {@code StormLoginQueueOverTcp.tryRequest}
 * into {@code GameClient.sendLoginQueueRequest()} and {@code tryDone} into {@code
 * sendLoginQueueDone(long)}, each into its own method only, and leaves the neighbouring loading
 * sends ({@code GameLoadingRequestData}) alone.
 *
 * <p>Detection signal: the inlined advice calls the helper via INVOKESTATIC. Vanilla contains no
 * such call, so seeing it after the transform proves the advice landed.
 */
class LoginQueueOverTcpPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/network/GameClient";
    private static final String HELPER_OWNER = "io/pzstorm/storm/client/StormLoginQueueOverTcp";

    private static final String REQUEST_METHOD = "sendLoginQueueRequest";
    private static final String REQUEST_DESC = "()V";
    private static final String REQUEST_HELPER = "tryRequest";

    private static final String DONE_METHOD = "sendLoginQueueDone";
    private static final String DONE_DESC = "(J)V";
    private static final String DONE_HELPER = "tryDone";

    private static final String SIBLING_METHOD = "GameLoadingRequestData";
    private static final String SIBLING_DESC = "()V";

    @Test
    void patchInjectsEachHelperIntoItsOwnMethod() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new LoginQueueOverTcpPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(0, countCalls(rawClass, REQUEST_METHOD, REQUEST_DESC, REQUEST_HELPER));
        assertEquals(0, countCalls(rawClass, DONE_METHOD, DONE_DESC, DONE_HELPER));

        assertTrue(
                countCalls(transformed, REQUEST_METHOD, REQUEST_DESC, REQUEST_HELPER) >= 1,
                "sendLoginQueueRequest must call " + REQUEST_HELPER);
        assertTrue(
                countCalls(transformed, DONE_METHOD, DONE_DESC, DONE_HELPER) >= 1,
                "sendLoginQueueDone must call " + DONE_HELPER);

        assertEquals(
                0,
                countCalls(transformed, REQUEST_METHOD, REQUEST_DESC, DONE_HELPER),
                "done advice must not land on the request method");
        assertEquals(
                0,
                countCalls(transformed, DONE_METHOD, DONE_DESC, REQUEST_HELPER),
                "request advice must not land on the done method");
        assertEquals(
                0,
                countCalls(transformed, SIBLING_METHOD, SIBLING_DESC, REQUEST_HELPER)
                        + countCalls(transformed, SIBLING_METHOD, SIBLING_DESC, DONE_HELPER),
                "Advice must not leak into " + SIBLING_METHOD);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countCalls(byte[] classBytes, String method, String desc, String helper) {
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
                                                && helper.equals(mName)) {
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
