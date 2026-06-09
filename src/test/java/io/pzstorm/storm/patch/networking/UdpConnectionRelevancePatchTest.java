package io.pzstorm.storm.patch.networking;

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
 * Verifies that {@link UdpConnectionRelevancePatch} injects the {@code isFullyConnected()} gate
 * into all four relevance methods on {@code UdpConnection} and only into those.
 *
 * <p>Detection signal: the inlined advice calls {@code
 * UdpConnectionRelevance.isConnectionReady(Object)} via INVOKESTATIC. Vanilla {@code UdpConnection}
 * contains no such call, so seeing it after the transform proves the advice landed on the right
 * methods; seeing none in an unrelated method proves the matcher didn't leak.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class UdpConnectionRelevancePatchTest implements UnitTest {

    private static final String UDP_CONNECTION = "zombie/core/raknet/UdpConnection";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/patch/networking/UdpConnectionRelevance";
    private static final String HELPER_METHOD = "isConnectionReady";

    private static final String[][] TARGETS = {
        {"isRelevantTo", "(FF)Z"},
        {"RelevantToPlayerIndex", "(IFF)Z"},
        {"RelevantTo", "(FFF)Z"},
        {"getRelevantAndDistance", "(FFF)F"},
    };

    // A simple unrelated method on UdpConnection used to assert no scope leak.
    private static final String SIBLING_METHOD = "isFullyConnected";
    private static final String SIBLING_DESC = "()Z";

    @Test
    void patchInjectsGateIntoAllFourRelevanceMethods() throws Exception {
        byte[] rawClass = readClassBytes(UDP_CONNECTION + ".class");
        byte[] transformed = new UdpConnectionRelevancePatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        for (String[] target : TARGETS) {
            String name = target[0];
            String desc = target[1];

            int before = countHelperCallsInMethod(rawClass, name, desc);
            int after = countHelperCallsInMethod(transformed, name, desc);

            assertEquals(
                    0,
                    before,
                    "Vanilla "
                            + name
                            + " should not call "
                            + HELPER_OWNER
                            + "."
                            + HELPER_METHOD
                            + " before patch");
            assertTrue(
                    after >= 1,
                    "Patched "
                            + name
                            + " must contain >=1 INVOKESTATIC "
                            + HELPER_OWNER
                            + "."
                            + HELPER_METHOD
                            + " (advice not injected); got "
                            + after);
        }

        int siblingBefore = countHelperCallsInMethod(rawClass, SIBLING_METHOD, SIBLING_DESC);
        int siblingAfter = countHelperCallsInMethod(transformed, SIBLING_METHOD, SIBLING_DESC);
        assertEquals(
                siblingBefore,
                siblingAfter,
                "Advice must not leak into UdpConnection."
                        + SIBLING_METHOD
                        + "; before="
                        + siblingBefore
                        + " after="
                        + siblingAfter);
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
