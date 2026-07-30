package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.advice.gameserverstalledconnections.StalledConnectionReaper;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link GameServerStalledConnectionReapPatch} inlines its two advice bodies into
 * {@code GameServer.addIncoming} and {@code GameServer.launchCommandHandler}, and nowhere else.
 *
 * <p>Detection signal: the inlined advice calls {@code StalledConnectionReaper.recordActivity} /
 * {@code .sweep} via INVOKESTATIC. Vanilla {@code GameServer} contains neither, so their presence
 * after the transform proves the advice landed; their absence from a sibling method proves the
 * matchers did not leak.
 *
 * <p>Uses ByteBuddy's bundled ASM for the same reason as {@link UdpConnectionRelevancePatchTest} —
 * the standalone {@code org.ow2.asm:asm:9.1} test dependency cannot read modern class files.
 */
class GameServerStalledConnectionReapPatchTest implements UnitTest {

    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String REAPER_OWNER =
            "io/pzstorm/storm/advice/gameserverstalledconnections/StalledConnectionReaper";

    private static final String ADD_INCOMING = "addIncoming";
    private static final String ADD_INCOMING_DESC =
            "(SLzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V";
    private static final String SWEEP_HOST = "launchCommandHandler";
    private static final String SWEEP_HOST_DESC = "()V";

    // Unrelated GameServer method used to assert no scope leak.
    private static final String SIBLING_METHOD = "disconnect";
    private static final String SIBLING_DESC =
            "(Lzombie/core/raknet/UdpConnection;Ljava/lang/String;)V";

    @Test
    void patchInjectsActivityStampAndSweepIntoTheirOwnMethodsOnly() throws Exception {
        byte[] rawClass = readClassBytes(GAME_SERVER + ".class");
        byte[] transformed = new GameServerStalledConnectionReapPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countCalls(rawClass, ADD_INCOMING, ADD_INCOMING_DESC, "recordActivity"),
                "Vanilla addIncoming must not already call recordActivity");
        assertTrue(
                countCalls(transformed, ADD_INCOMING, ADD_INCOMING_DESC, "recordActivity") >= 1,
                "Patched addIncoming must call StalledConnectionReaper.recordActivity");

        assertEquals(
                0,
                countCalls(rawClass, SWEEP_HOST, SWEEP_HOST_DESC, "sweep"),
                "Vanilla launchCommandHandler must not already call sweep");
        assertTrue(
                countCalls(transformed, SWEEP_HOST, SWEEP_HOST_DESC, "sweep") >= 1,
                "Patched launchCommandHandler must call StalledConnectionReaper.sweep");

        assertEquals(
                0,
                countCalls(transformed, SIBLING_METHOD, SIBLING_DESC, "recordActivity")
                        + countCalls(transformed, SIBLING_METHOD, SIBLING_DESC, "sweep"),
                "Advice must not leak into GameServer." + SIBLING_METHOD);
    }

    @Test
    void idleWindowDefaultsToSevenMinutes() {
        assertEquals(7L * 60L * 1000L, StalledConnectionReaper.DEFAULT_IDLE_TIMEOUT_MS);
        assertEquals(
                StalledConnectionReaper.DEFAULT_IDLE_TIMEOUT_MS,
                StalledConnectionReaper.getIdleTimeoutMs(),
                "no -Dstorm.reapStalledConnectionMs override in tests");
    }

    @Test
    void sweepIsANoOpBeforeTheServerPeerExists() {
        // GameServer.udpEngine is null outside a running server; the sweep must not throw.
        StalledConnectionReaper.sweep();
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countCalls(
            byte[] classBytes, String method, String desc, String reaperMethod) {
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
                                                && REAPER_OWNER.equals(owner)
                                                && reaperMethod.equals(mName)) {
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
