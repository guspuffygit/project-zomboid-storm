package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Verifies that {@link CoopHatchPositionFixPatch} weaves the position-repair helper call into
 * {@code IsoHutch.addAnimalInside(IsoAnimal, boolean)} and only there, and unit-tests the pure
 * decision logic in {@link CoopHatchPositionFix#needsFix}.
 */
class CoopHatchPositionFixPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/iso/objects/IsoHutch";
    private static final String HELPER_CLASS = "io/pzstorm/storm/patch/fixes/CoopHatchPositionFix";

    private static final String TARGET_METHOD = "addAnimalInside";
    private static final String TARGET_DESC = "(Lzombie/characters/animals/IsoAnimal;Z)Z";

    // The 1-arg overload delegates to the 2-arg one; advising both would run the fix twice.
    private static final String OVERLOAD_DESC = "(Lzombie/characters/animals/IsoAnimal;)Z";

    @Test
    void patchInjectsHelperIntoTwoArgAddAnimalInsideOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new CoopHatchPositionFixPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(
                0,
                countHelperCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla addAnimalInside must not reference the Storm helper");
        assertTrue(
                countHelperCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched addAnimalInside(IsoAnimal, boolean) must call " + HELPER_CLASS);
        assertEquals(
                0,
                countHelperCalls(transformed, TARGET_METHOD, OVERLOAD_DESC),
                "Advice must not leak into the delegating 1-arg overload");
        assertEquals(
                0,
                countHelperCalls(transformed, "removeAnimal", null),
                "Advice must not leak into removeAnimal");
    }

    @Test
    void originChickEnteringRealHutchNeedsFix() {
        // A chick from `new IsoAnimal(cell, 0, 0, 0, ...)` sits at the square centre (0.5, 0.5).
        assertTrue(CoopHatchPositionFix.needsFix(0.5f, 0.5f, 7398, 8258));
    }

    @Test
    void positionedAnimalIsLeftAlone() {
        assertFalse(CoopHatchPositionFix.needsFix(7398.9f, 8260.9f, 7398, 8258));
        // One real axis is enough — never second-guess a position that is not at the origin band.
        assertFalse(CoopHatchPositionFix.needsFix(0.5f, 8260.9f, 7398, 8258));
        assertFalse(CoopHatchPositionFix.needsFix(7398.9f, 0.5f, 7398, 8258));
    }

    @Test
    void hutchWithoutOwnPositionOffersNoFix() {
        assertFalse(CoopHatchPositionFix.needsFix(0.5f, 0.5f, 0, 0));
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
