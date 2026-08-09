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
 * Bytecode-shape test for {@link BodyDamageUpdatePacketPatch}: after {@code transform()}, {@code
 * BodyDamageUpdatePacket.parse} must contain an {@code INVOKESTATIC} to {@link
 * BodyDamageUpdatePacketPatch#repairPlayerIds}, and the advice must not leak into {@code
 * processServer} (where it would run after downstream ownership guards instead of before them).
 *
 * <p>Bytecode shape only: exercising the repair logic end-to-end needs a resolved {@code IsoPlayer}
 * on a {@code PlayerID}, and constructing one pulls in the animation/graphics stack.
 */
class BodyDamageUpdatePacketPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/network/packets/BodyDamageUpdatePacket";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/patch/fixes/BodyDamageUpdatePacketPatch";
    private static final String HELPER_METHOD = "repairPlayerIds";

    private static final String TARGET_METHOD = "parse";
    private static final String TARGET_DESC =
            "(Lzombie/core/network/ByteBufferReader;Lzombie/network/IConnection;)V";

    private static final String SIBLING_METHOD = "processServer";

    @Test
    void patchInjectsRepairIntoParse() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new BodyDamageUpdatePacketPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countHelperCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla parse must not already call " + HELPER_OWNER);
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched parse must contain >=1 INVOKESTATIC "
                        + HELPER_OWNER
                        + "."
                        + HELPER_METHOD);
        assertEquals(
                0,
                countHelperCalls(transformed, SIBLING_METHOD, null),
                "Advice must not leak into BodyDamageUpdatePacket." + SIBLING_METHOD);
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
