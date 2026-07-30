package io.pzstorm.storm.patch.networking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.connection.RakNetConnectionCapConfig;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link GameServerConnectionCapPatch} redirects the {@code new UdpEngine(...)} call
 * in {@code GameServer.startServer} through {@code UdpEngineFactory.create}, and nowhere else.
 *
 * <p>Detection signal: an INVOKESTATIC to the factory. Vanilla {@code GameServer} contains none, so
 * its presence after the transform proves the substitution landed; its absence from a sibling
 * method proves the {@code named("startServer")} scope did not leak.
 *
 * <p>Uses ByteBuddy's bundled ASM for the same reason as {@link UdpConnectionRelevancePatchTest} —
 * the standalone {@code org.ow2.asm:asm:9.1} test dependency cannot read modern class files.
 */
class GameServerConnectionCapPatchTest implements UnitTest {

    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String FACTORY_OWNER =
            "io/pzstorm/storm/patch/networking/GameServerConnectionCapPatch$UdpEngineFactory";

    private static final String START_SERVER = "startServer";
    private static final String START_SERVER_DESC = "()V";

    // Unrelated GameServer method used to assert no scope leak.
    private static final String SIBLING_METHOD = "disconnect";
    private static final String SIBLING_DESC =
            "(Lzombie/core/raknet/UdpConnection;Ljava/lang/String;)V";

    @Test
    void patchRoutesTheServerUdpEngineThroughTheFactoryOnly() throws Exception {
        byte[] rawClass = readClassBytes(GAME_SERVER + ".class");
        byte[] transformed = new GameServerConnectionCapPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countFactoryCalls(rawClass, START_SERVER, START_SERVER_DESC),
                "Vanilla startServer must not already call the factory");
        assertEquals(
                1,
                countFactoryCalls(transformed, START_SERVER, START_SERVER_DESC),
                "Patched startServer must build its UdpEngine through UdpEngineFactory.create");
        assertEquals(
                0,
                countFactoryCalls(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Substitution must not leak into GameServer." + SIBLING_METHOD);
    }

    @Test
    void vanillaCapIsAFloorSoSmallServersAreNotRegressed() {
        // MaxPlayers=32 default + 64 headroom = 96, below the vanilla 101.
        assertEquals(
                RakNetConnectionCapConfig.VANILLA_CAP,
                RakNetConnectionCapConfig.resolveCap(RakNetConnectionCapConfig.VANILLA_CAP, 32));
    }

    @Test
    void maxPlayersGetsHeadroomAboveTheVanillaCap() {
        assertEquals(
                100 + RakNetConnectionCapConfig.DEFAULT_HEADROOM,
                RakNetConnectionCapConfig.resolveCap(RakNetConnectionCapConfig.VANILLA_CAP, 100));
    }

    @Test
    void capNeverExceedsTheByteWideWireIndex() {
        // UdpEngine.decode() reads the connection index as buf.getByte() & 255 into a
        // UdpConnection[256]; a 257th connection would collide with an existing one.
        assertEquals(
                RakNetConnectionCapConfig.MAX_CAP,
                RakNetConnectionCapConfig.resolveCap(
                        RakNetConnectionCapConfig.VANILLA_CAP, 10_000));
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countFactoryCalls(byte[] classBytes, String method, String desc) {
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
                                                && FACTORY_OWNER.equals(owner)
                                                && "create".equals(mName)) {
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
