package io.pzstorm.storm.patch.fixes;

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
 * Verifies that {@link GameServerStartPMChatPatch} injects {@code GameServerStartPMChatAdvice} into
 * {@code GameServer.receivePlayerStartPMChat} and only into that method.
 *
 * <p>Detection signal: the inlined advice calls {@code GameServerStartPMChatAdvice.run} (an {@code
 * INVOKESTATIC} whose owner is the advice class). Vanilla contains no reference to any Storm class,
 * so seeing the call after the transform proves the advice landed; seeing none in a sibling packet
 * handler proves the matcher didn't leak.
 */
class GameServerStartPMChatPatchTest implements UnitTest {

    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String ADVICE_OWNER =
            "io/pzstorm/storm/advice/whisperchatfix/GameServerStartPMChatAdvice";

    private static final String TARGET_METHOD = "receivePlayerStartPMChat";
    private static final String SIBLING_METHOD = "receiveSandboxOptions";

    @Test
    void patchInjectsAdviceIntoReceivePlayerStartPMChatOnly() throws Exception {
        byte[] rawClass = readClassBytes(GAME_SERVER + ".class");
        byte[] transformed = new GameServerStartPMChatPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countAdviceCallsInMethod(rawClass, TARGET_METHOD),
                "Vanilla receivePlayerStartPMChat must not reference the advice class");
        assertTrue(
                countAdviceCallsInMethod(transformed, TARGET_METHOD) >= 1,
                "Patched receivePlayerStartPMChat must call GameServerStartPMChatAdvice.run"
                        + " (advice not injected)");
        assertEquals(
                0,
                countAdviceCallsInMethod(transformed, SIBLING_METHOD),
                "Advice must not leak into GameServer." + SIBLING_METHOD);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countAdviceCallsInMethod(byte[] classBytes, String methodName) {
        int[] count = {0};
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
                                if (!name.equals(methodName)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String insnName,
                                            String insnDescriptor,
                                            boolean isInterface) {
                                        if (ADVICE_OWNER.equals(owner)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }
}
