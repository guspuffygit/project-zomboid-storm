package io.pzstorm.storm.patch.client;

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
 * Verifies that {@link VehicleSoundsClientCreatePatch} weaves the sounds-create helper call into
 * {@code BaseVehicle.checkVehicleSoundsExists()} and only there.
 */
class VehicleSoundsClientCreatePatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/vehicles/BaseVehicle";
    private static final String HELPER_CLASS =
            "io/pzstorm/storm/advice/client/vehiclesounds/VehicleSoundsClientCreate";
    private static final String HELPER_METHOD = "ensureExists";

    private static final String TARGET_METHOD = "checkVehicleSoundsExists";
    private static final String TARGET_DESC = "()V";

    // Calls checkVehicleSoundsExists() itself, an easy over-match for a sloppy matcher.
    private static final String SIBLING_METHOD = "updateSounds";

    @Test
    void patchInjectsHelperIntoCheckVehicleSoundsExistsOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new VehicleSoundsClientCreatePatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(
                0,
                countHelperCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla checkVehicleSoundsExists() must not reference the Storm helper");
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched checkVehicleSoundsExists() must call "
                        + HELPER_CLASS
                        + "."
                        + HELPER_METHOD);
        assertEquals(
                0,
                countHelperCalls(transformed, SIBLING_METHOD, null),
                "Advice must not leak into " + SIBLING_METHOD);
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
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && HELPER_CLASS.equals(owner)
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
