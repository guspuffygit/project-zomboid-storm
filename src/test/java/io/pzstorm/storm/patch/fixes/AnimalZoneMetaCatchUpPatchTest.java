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
 * Verifies that {@link AnimalZoneMetaCatchUpPatch} routes only {@code doMeta(int)} through the
 * helper.
 */
class AnimalZoneMetaCatchUpPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/iso/areas/DesignationZoneAnimal";
    private static final String HELPER_CLASS = "io/pzstorm/storm/patch/fixes/AnimalZoneMetaCatchUp";

    @Test
    void patchInjectsHelperIntoDoMetaOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new AnimalZoneMetaCatchUpPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(0, countHelperCalls(rawClass, "doMeta", "(I)V"));
        assertTrue(countHelperCalls(transformed, "doMeta", "(I)V") >= 1);

        assertEquals(0, countHelperCalls(transformed, "check", "()V"));
        assertEquals(0, countHelperCalls(transformed, "reAttachAnimal", "()V"));
        assertEquals(0, countHelperCalls(transformed, "getAnimalsInZone", null));
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

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
                                        if (HELPER_CLASS.equals(owner)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return hits[0];
    }
}
