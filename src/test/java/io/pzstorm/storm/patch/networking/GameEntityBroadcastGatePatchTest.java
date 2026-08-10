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
 * Verifies that {@link GameEntityBroadcastGatePatch} injects the {@code
 * StormGameEntityBroadcastGate.run} gate into the 5-arg {@code sendPacketData} and only there.
 *
 * <p>Detection signal: the inlined advice calls {@code StormGameEntityBroadcastGate.run} via
 * INVOKESTATIC. Vanilla {@code GameEntityNetwork} contains no such call, so seeing it after the
 * transform proves the advice landed on the right method; seeing none in {@code sendPacketDataTo}
 * proves the matcher didn't leak onto the targeted-send overload.
 */
class GameEntityBroadcastGatePatchTest implements UnitTest {

    private static final String GAME_ENTITY_NETWORK = "zombie/entity/GameEntityNetwork";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/connection/StormGameEntityBroadcastGate";
    private static final String HELPER_METHOD = "run";

    private static final String TARGET_METHOD = "sendPacketData";
    private static final String TARGET_DESC =
            "(Lzombie/entity/network/EntityPacketData;Lzombie/entity/GameEntity;"
                    + "Lzombie/entity/Component;Lzombie/network/IConnection;Z)V";

    // The targeted-send sibling used to assert no scope leak.
    private static final String SIBLING_METHOD = "sendPacketDataTo";
    private static final String SIBLING_DESC =
            "(Lzombie/characters/IsoPlayer;Lzombie/entity/network/EntityPacketData;"
                    + "Lzombie/entity/GameEntity;Lzombie/entity/Component;)V";

    @Test
    void patchInjectsGateIntoBroadcastSendOnly() throws Exception {
        byte[] rawClass = readClassBytes(GAME_ENTITY_NETWORK + ".class");
        byte[] transformed = new GameEntityBroadcastGatePatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        int before = countHelperCallsInMethod(rawClass, TARGET_METHOD, TARGET_DESC);
        int after = countHelperCallsInMethod(transformed, TARGET_METHOD, TARGET_DESC);
        assertEquals(
                0,
                before,
                "Vanilla sendPacketData should not call "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD
                        + " before patch");
        assertTrue(
                after >= 1,
                "Patched sendPacketData must contain >=1 INVOKESTATIC "
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
                "Advice must not leak into GameEntityNetwork."
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
