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
 * Verifies that {@link PlayerDataRequestBackoffPatch} injects the {@code
 * StormPlayerDataRequestBackoff.shouldSuppress} gate into the static 2-arg client {@code
 * INetworkPacket.send} and only there.
 *
 * <p>Detection signal: the inlined advice calls the gate via INVOKESTATIC. Vanilla {@code
 * INetworkPacket} contains no such call, so seeing it after the transform proves the advice landed
 * on the right overload; seeing none in the 3-arg connection-targeted {@code send} proves the
 * matcher didn't leak onto the overload every server-side send funnels through.
 */
class PlayerDataRequestBackoffPatchTest implements UnitTest {

    private static final String INETWORK_PACKET = "zombie/network/packets/INetworkPacket";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/connection/StormPlayerDataRequestBackoff";
    private static final String HELPER_METHOD = "shouldSuppress";

    private static final String TARGET_METHOD = "send";
    private static final String TARGET_DESC =
            "(Lzombie/network/PacketTypes$PacketType;[Ljava/lang/Object;)V";

    // The connection-targeted overload used to assert no scope leak.
    private static final String SIBLING_METHOD = "send";
    private static final String SIBLING_DESC =
            "(Lzombie/network/IConnection;Lzombie/network/PacketTypes$PacketType;"
                    + "[Ljava/lang/Object;)V";

    @Test
    void patchInjectsBackoffIntoClientSendOnly() throws Exception {
        byte[] rawClass = readClassBytes(INETWORK_PACKET + ".class");
        byte[] transformed = new PlayerDataRequestBackoffPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        int before = countHelperCallsInMethod(rawClass, TARGET_METHOD, TARGET_DESC);
        int after = countHelperCallsInMethod(transformed, TARGET_METHOD, TARGET_DESC);
        assertEquals(
                0,
                before,
                "Vanilla send should not call "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD
                        + " before patch");
        assertTrue(
                after >= 1,
                "Patched send must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD
                        + " (advice not injected); got "
                        + after);

        int siblingBefore = countHelperCallsInMethod(rawClass, SIBLING_METHOD, SIBLING_DESC);
        int siblingAfter = countHelperCallsInMethod(transformed, SIBLING_METHOD, SIBLING_DESC);
        assertEquals(
                siblingBefore,
                siblingAfter,
                "Advice must not leak into the 3-arg INetworkPacket.send; before="
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
