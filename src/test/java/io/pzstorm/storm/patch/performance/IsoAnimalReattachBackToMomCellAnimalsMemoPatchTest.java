package io.pzstorm.storm.patch.performance;

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
 * {@code MemberSubstitution.relaxed()} silently does nothing when its matcher misses, so the weave
 * test alone cannot tell a working substitution from a no-op. This reads the transformed bytecode
 * of {@code reattachBackToMom} and checks the call site actually moved.
 */
class IsoAnimalReattachBackToMomCellAnimalsMemoPatchTest implements UnitTest {

    private static final String ISO_ANIMAL = "zombie/characters/animals/IsoAnimal";
    private static final String TARGET_METHOD = "reattachBackToMom";
    private static final String TARGET_DESC = "()V";

    private static final String CELL_OWNER = "zombie/iso/IsoCell";
    private static final String DZONE_OWNER = "zombie/iso/areas/DesignationZoneAnimal";
    private static final String GET_ANIMALS = "getAnimals";
    private static final String HELPER_OWNER =
            "io/pzstorm/storm/patch/performance/ReattachCellAnimalsMemo";
    private static final String HELPER_METHOD = "animalsThisTick";

    @Test
    void substitutesOnlyTheCellGetAnimalsCall() throws Exception {
        byte[] rawClass = readClassBytes(ISO_ANIMAL + ".class");
        byte[] transformed =
                new IsoAnimalReattachBackToMomCellAnimalsMemoPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                1,
                countCalls(rawClass, CELL_OWNER, GET_ANIMALS),
                "vanilla reattachBackToMom should call IsoCell.getAnimals exactly once");
        assertEquals(0, countCalls(rawClass, HELPER_OWNER, HELPER_METHOD));

        assertEquals(
                0,
                countCalls(transformed, CELL_OWNER, GET_ANIMALS),
                "IsoCell.getAnimals call must be substituted away");
        assertEquals(
                1,
                countCalls(transformed, HELPER_OWNER, HELPER_METHOD),
                "exactly one call to ReattachCellAnimalsMemo.animalsThisTick expected");

        int dzoneBefore = countCalls(rawClass, DZONE_OWNER, GET_ANIMALS);
        assertTrue(dzoneBefore >= 1, "vanilla should also read DesignationZoneAnimal.getAnimals");
        assertEquals(
                dzoneBefore,
                countCalls(transformed, DZONE_OWNER, GET_ANIMALS),
                "DesignationZoneAnimal.getAnimals calls must be left alone");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countCalls(byte[] classBytes, String callOwner, String callName) {
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
                                if (!TARGET_METHOD.equals(name)
                                        || !TARGET_DESC.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if (callOwner.equals(owner) && callName.equals(mname)) {
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
