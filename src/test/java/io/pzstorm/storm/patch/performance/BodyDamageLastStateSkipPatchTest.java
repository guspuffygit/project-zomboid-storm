package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/** The woven {@code setBodyPartsLastState()} must consult {@code GameServer.server} on entry. */
class BodyDamageLastStateSkipPatchTest implements UnitTest {

    private static final String TARGET = "zombie/characters/BodyDamage/BodyDamage";

    @Test
    void setBodyPartsLastStateReadsServerFlag() throws Exception {
        byte[] raw = readClass(TARGET);
        byte[] transformed = new BodyDamageLastStateSkipPatch().transform(raw);
        assertNotNull(transformed);
        assertEquals(0, serverFlagReads(raw, "setBodyPartsLastState()V"));
        assertEquals(1, serverFlagReads(transformed, "setBodyPartsLastState()V"));
        assertEquals(
                0,
                serverFlagReads(
                        transformed,
                        "getBodyPartsLastState(Lzombie/characters/BodyDamage/BodyPartType;)Lzombie/characters/BodyDamage/BodyPartLast;"));
    }

    private static int serverFlagReads(byte[] classBytes, String method) {
        int[] reads = new int[1];
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
                                if (!method.equals(name + descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fname, String desc) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && "zombie/network/GameServer".equals(owner)
                                                && "server".equals(fname)) {
                                            reads[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return reads[0];
    }

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream is =
                BodyDamageLastStateSkipPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
