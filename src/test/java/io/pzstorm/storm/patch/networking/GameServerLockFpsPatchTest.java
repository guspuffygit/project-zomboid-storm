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
 * Verifies the patched {@code GameServer} bytecode actually rewrites the {@code
 * PerformanceSettings.setLockFPS(int)} call inside {@code main}, and only inside {@code main}.
 *
 * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
 * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class GameServerLockFpsPatchTest implements UnitTest {

    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String PERFORMANCE_SETTINGS = "zombie/core/PerformanceSettings";
    private static final String SET_LOCK_FPS_DESC = "(I)V";
    private static final String CONFIG_INTERNAL =
            ServerLockFpsConfig.class.getName().replace('.', '/');

    @Test
    void patchRewritesOnlyTheMainCallSite() throws Exception {
        String resourcePath = GAME_SERVER + ".class";
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "GameServer.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new GameServerLockFpsPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Counts counts = countInvocations(transformed);

        // main() should have its single setLockFPS(10) call rewritten to applyServerLockFps.
        assertEquals(
                1,
                counts.mainHelperCalls,
                "main() should contain exactly one INVOKESTATIC to "
                        + "ServerLockFpsConfig.applyServerLockFps; got "
                        + counts.mainHelperCalls);
        assertEquals(
                0,
                counts.mainSetLockFpsCalls,
                "main() should have no remaining INVOKESTATIC on "
                        + "PerformanceSettings.setLockFPS after rewrite; got "
                        + counts.mainSetLockFpsCalls);

        // Substitution must not leak into other methods. GameServer doesn't normally call
        // setLockFPS outside main, but assert hard zero so an accidental scope widening is caught.
        assertEquals(
                0,
                counts.otherMethodHelperCalls,
                "Helper substitution must not leak into methods outside main(); got "
                        + counts.otherMethodHelperCalls);
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
                                final boolean isMain =
                                        "main".equals(name)
                                                && "([Ljava/lang/String;)V".equals(descriptor);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        boolean isVanillaSetLockFps =
                                                opcode == Opcodes.INVOKESTATIC
                                                        && PERFORMANCE_SETTINGS.equals(owner)
                                                        && "setLockFPS".equals(mName)
                                                        && SET_LOCK_FPS_DESC.equals(mDesc);
                                        boolean isHelperCall =
                                                opcode == Opcodes.INVOKESTATIC
                                                        && CONFIG_INTERNAL.equals(owner)
                                                        && "applyServerLockFps".equals(mName)
                                                        && SET_LOCK_FPS_DESC.equals(mDesc);
                                        if (isVanillaSetLockFps) {
                                            if (isMain) counts.mainSetLockFpsCalls++;
                                            else counts.otherMethodSetLockFpsCalls++;
                                        }
                                        if (isHelperCall) {
                                            if (isMain) counts.mainHelperCalls++;
                                            else counts.otherMethodHelperCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class Counts {
        int mainSetLockFpsCalls;
        int mainHelperCalls;
        int otherMethodSetLockFpsCalls;
        int otherMethodHelperCalls;
    }
}
