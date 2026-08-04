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
 * Verifies that {@link ServerQueryPatch} injects the launcher-query hook into {@code
 * GameServer.addIncoming} and nowhere else.
 *
 * <p>Detection signal: the inlined advice calls {@code StormQueryResponder.handle} via
 * INVOKESTATIC. Vanilla {@code GameServer} contains no such call, so seeing it after the transform
 * proves the advice landed; seeing none in a sibling method proves the matcher didn't leak.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class ServerQueryPatchTest implements UnitTest {

    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String HELPER_OWNER = "io/pzstorm/storm/query/StormQueryResponder";
    private static final String HELPER_METHOD = "handle";

    private static final String TARGET_METHOD = "addIncoming";
    private static final String TARGET_DESC =
            "(SLzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V";

    private static final String SIBLING_METHOD = "mainLoopDealWithNetData";

    @Test
    void patchInjectsResponderIntoAddIncoming() throws Exception {
        byte[] rawClass = readClassBytes(GAME_SERVER + ".class");
        byte[] transformed = new ServerQueryPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla addIncoming must not already call " + HELPER_OWNER);
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched addIncoming must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD);
        assertEquals(
                0,
                countHelperCalls(transformed, SIBLING_METHOD, null),
                "Advice must not leak into GameServer." + SIBLING_METHOD);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** A null {@code desc} matches every overload of {@code method}. */
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
                                if (!method.equals(name)
                                        || (desc != null && !desc.equals(descriptor))) {
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
