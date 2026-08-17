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

    private static final int SLOT_TABLE_42_20_3 = 255;

    @Test
    void vanillaCapIsAFloorSoSmallServersAreNotRegressed() {
        // MaxPlayers=32 default + 64 headroom = 96, below the vanilla cap.
        assertEquals(
                RakNetConnectionCapConfig.VANILLA_CAP,
                RakNetConnectionCapConfig.resolveCap(
                        RakNetConnectionCapConfig.VANILLA_CAP, 32, SLOT_TABLE_42_20_3));
    }

    @Test
    void maxPlayersGetsHeadroomAboveTheVanillaCap() {
        // Exercised with the pre-42.20.3 literal 101, where the headroom sum lands between the
        // floor and the ceiling.
        assertEquals(
                100 + RakNetConnectionCapConfig.DEFAULT_HEADROOM,
                RakNetConnectionCapConfig.resolveCap(101, 100, SLOT_TABLE_42_20_3));
    }

    @Test
    void capNeverExceedsTheSlotTable() {
        // GameServer.disconnect scans SlotToConnection[i] for i < getMaxConnections(); a cap of
        // 256 against the 42.20.3 length-255 table threw AIOOBE on every disconnect.
        assertEquals(
                SLOT_TABLE_42_20_3,
                RakNetConnectionCapConfig.resolveCap(
                        RakNetConnectionCapConfig.VANILLA_CAP, 10_000, SLOT_TABLE_42_20_3));
    }

    @Test
    void unreadableSlotTableFallsBackToTheConservativeBound() {
        assertEquals(
                RakNetConnectionCapConfig.FALLBACK_SLOT_TABLE_LENGTH,
                RakNetConnectionCapConfig.resolveCap(
                        RakNetConnectionCapConfig.VANILLA_CAP, 10_000, 0));
    }

    @Test
    void byteWideWireIndexStillBindsWhenTheSlotTableIsRoomier() {
        // Pre-42.20.3 the table was UdpConnection[512]; the wire index kept the cap at 256.
        assertEquals(
                RakNetConnectionCapConfig.MAX_CAP,
                RakNetConnectionCapConfig.resolveCap(
                        RakNetConnectionCapConfig.VANILLA_CAP, 10_000, 512));
    }

    @Test
    void fallbackSlotTableLengthMatchesTheShippedGameBytes() throws Exception {
        // Read from <clinit> bytecode rather than GameServer.SlotToConnection.length —
        // initializing GameServer in a bare test JVM is not safe. Fails on the next vanilla
        // resize so FALLBACK_SLOT_TABLE_LENGTH gets re-audited instead of silently drifting.
        assertEquals(
                RakNetConnectionCapConfig.FALLBACK_SLOT_TABLE_LENGTH,
                readSlotToConnectionAllocationSize(readClassBytes(GAME_SERVER + ".class")));
    }

    /**
     * Finds {@code SlotToConnection = new UdpConnection[N]} in {@code <clinit>} and returns {@code
     * N}: the int constant on the stack when {@code ANEWARRAY} feeding the {@code PUTSTATIC
     * SlotToConnection} executes.
     */
    private static int readSlotToConnectionAllocationSize(byte[] classBytes) {
        int[] size = {-1};
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
                                if (!"<clinit>".equals(name)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private int lastIntConstant = -1;
                                    private boolean lastWasArrayAlloc;

                                    @Override
                                    public void visitIntInsn(int opcode, int operand) {
                                        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                                            lastIntConstant = operand;
                                            lastWasArrayAlloc = false;
                                        }
                                    }

                                    @Override
                                    public void visitLdcInsn(Object value) {
                                        if (value instanceof Integer) {
                                            lastIntConstant = (Integer) value;
                                        }
                                        lastWasArrayAlloc = false;
                                    }

                                    @Override
                                    public void visitTypeInsn(int opcode, String type) {
                                        lastWasArrayAlloc = opcode == Opcodes.ANEWARRAY;
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String name, String desc) {
                                        if (opcode == Opcodes.PUTSTATIC
                                                && GAME_SERVER.equals(owner)
                                                && "SlotToConnection".equals(name)
                                                && lastWasArrayAlloc) {
                                            size[0] = lastIntConstant;
                                        }
                                        lastWasArrayAlloc = false;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        assertTrue(size[0] > 0, "SlotToConnection allocation not found in GameServer.<clinit>");
        return size[0];
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
