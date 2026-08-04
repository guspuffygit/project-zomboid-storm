package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Verifies that {@link GameServerStalledConnectionReapPatch} inlines its sweep advice into {@code
 * GameServer.launchCommandHandler}, and nowhere else.
 *
 * <p>Detection signal: the inlined advice calls {@code StalledConnectionReaper.sweep} via
 * INVOKESTATIC. Vanilla {@code GameServer} contains no such call, so its presence after the
 * transform proves the advice landed; its absence from other methods proves the matcher did not
 * leak. {@code addIncoming} is checked explicitly because an earlier revision hooked it for
 * per-packet activity stamping — the wall-clock reaper must not touch it.
 *
 * <p>Uses ByteBuddy's bundled ASM for the same reason as {@link UdpConnectionRelevancePatchTest} —
 * the standalone {@code org.ow2.asm:asm:9.1} test dependency cannot read modern class files.
 */
class GameServerStalledConnectionReapPatchTest implements UnitTest {

    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String REAPER_OWNER =
            "io/pzstorm/storm/advice/gameserverstalledconnections/StalledConnectionReaper";

    private static final String SWEEP_HOST = "launchCommandHandler";
    private static final String SWEEP_HOST_DESC = "()V";

    private static final String ADD_INCOMING = "addIncoming";
    private static final String ADD_INCOMING_DESC =
            "(SLzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V";

    // Unrelated GameServer method used to assert no scope leak.
    private static final String SIBLING_METHOD = "disconnect";
    private static final String SIBLING_DESC =
            "(Lzombie/core/raknet/UdpConnection;Ljava/lang/String;)V";

    @Test
    void patchInjectsSweepIntoLaunchCommandHandlerOnly() throws Exception {
        byte[] rawClass = readClassBytes(GAME_SERVER + ".class");
        byte[] transformed = new GameServerStalledConnectionReapPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countReaperCalls(rawClass, SWEEP_HOST, SWEEP_HOST_DESC),
                "Vanilla launchCommandHandler must not already call the reaper");
        assertTrue(
                countReaperCalls(transformed, SWEEP_HOST, SWEEP_HOST_DESC) >= 1,
                "Patched launchCommandHandler must call StalledConnectionReaper.sweep");

        assertEquals(
                0,
                countReaperCalls(transformed, ADD_INCOMING, ADD_INCOMING_DESC),
                "The wall-clock reaper must not hook GameServer.addIncoming");
        assertEquals(
                0,
                countReaperCalls(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Advice must not leak into GameServer." + SIBLING_METHOD);
    }

    @Test
    void connectBudgetDefaultsToTenMinutes() {
        assertEquals(10L * 60L * 1000L, StalledConnectionReaper.DEFAULT_CONNECT_TIMEOUT_MS);
        assertEquals(
                StalledConnectionReaper.DEFAULT_CONNECT_TIMEOUT_MS,
                StalledConnectionReaper.getConnectTimeoutMs(),
                "no -Dstorm.reapStalledConnectionMs override in tests");
    }

    @Test
    void sweepIsANoOpBeforeTheServerPeerExists() {
        // GameServer.udpEngine is null outside a running server; the sweep must not throw.
        StalledConnectionReaper.sweep();
    }

    @Test
    void sandboxSetterClampsToDeclaredBoundsAndApplies() {
        long original = StalledConnectionReaper.getConnectTimeoutMs();
        try {
            assertEquals(
                    StalledConnectionReaper.MIN_SANDBOX_CONNECT_TIMEOUT_SECONDS * 1000L,
                    StalledConnectionReaper.setConnectTimeoutSecondsFromSandbox(1, false),
                    "Below-min sandbox value must clamp to the declared minimum");
            assertEquals(
                    StalledConnectionReaper.MAX_SANDBOX_CONNECT_TIMEOUT_SECONDS * 1000L,
                    StalledConnectionReaper.setConnectTimeoutSecondsFromSandbox(999999, false),
                    "Above-max sandbox value must clamp to the declared maximum");
            assertEquals(
                    900_000L,
                    StalledConnectionReaper.setConnectTimeoutSecondsFromSandbox(900, false),
                    "In-range sandbox value must apply verbatim");
            assertEquals(900_000L, StalledConnectionReaper.getConnectTimeoutMs());
        } finally {
            StalledConnectionReaper.setConnectTimeoutMs(original);
        }
    }

    @Test
    void launchFlagAlwaysWinsOverSandboxValue() {
        long original = StalledConnectionReaper.getConnectTimeoutMs();
        try {
            StalledConnectionReaper.setConnectTimeoutMs(123_000L);
            assertEquals(
                    123_000L,
                    StalledConnectionReaper.setConnectTimeoutSecondsFromSandbox(900, true),
                    "With the -D property forcing the budget, the sandbox value must be ignored");
            assertEquals(123_000L, StalledConnectionReaper.getConnectTimeoutMs());
        } finally {
            StalledConnectionReaper.setConnectTimeoutMs(original);
        }
    }

    @Test
    void connectTimeoutIsNotForcedWithoutTheProperty() {
        // The test JVM sets no -Dstorm.reapStalledConnectionMs, so sandbox values must apply.
        assertFalse(StalledConnectionReaper.isConnectTimeoutForcedByProperty());
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countReaperCalls(byte[] classBytes, String method, String desc) {
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
                                                && REAPER_OWNER.equals(owner)) {
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
