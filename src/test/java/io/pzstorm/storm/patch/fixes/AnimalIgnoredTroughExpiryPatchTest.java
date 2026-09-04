package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.util.ArrayList;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link AnimalIgnoredTroughExpiryPatch} weaves the expiry helper into the private
 * {@code IsoAnimal.checkZone()} and nowhere else, and unit-tests the blacklist reset and fail-soft
 * behaviour in {@link AnimalIgnoredTroughExpiry}.
 */
class AnimalIgnoredTroughExpiryPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/characters/animals/IsoAnimal";
    private static final String HELPER_CLASS =
            "io/pzstorm/storm/patch/fixes/AnimalIgnoredTroughExpiry";

    @Test
    void patchInjectsHelperIntoCheckZoneOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new AnimalIgnoredTroughExpiryPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(0, countHelperCalls(rawClass, "checkZone", "()V"));
        assertEquals(1, countHelperCalls(transformed, "checkZone", "()V"));
        assertEquals(0, countHelperCalls(transformed, "updateInternal", null));
        assertEquals(0, countHelperCalls(transformed, "updateStatsAway", null));
        assertEquals(0, countHelperCalls(transformed, "removeFromWorld", null));
        assertEquals(0, countHelperCalls(transformed, "pathFailed", null));
    }

    @Test
    void expireClearsAndCountsTheBlacklist() {
        long before = AnimalIgnoredTroughExpiry.getExpiredTroughs();
        ArrayList<Object> ignored = new ArrayList<>();
        ignored.add(new Object());
        ignored.add(new Object());
        assertEquals(2, AnimalIgnoredTroughExpiry.expire(ignored));
        assertTrue(ignored.isEmpty());
        assertEquals(before + 2, AnimalIgnoredTroughExpiry.getExpiredTroughs());

        assertEquals(0, AnimalIgnoredTroughExpiry.expire(ignored));
        assertEquals(0, AnimalIgnoredTroughExpiry.expire(null));
        assertEquals(before + 2, AnimalIgnoredTroughExpiry.getExpiredTroughs());
    }

    @Test
    void nonAnimalLatchesOffInsteadOfThrowing() {
        try {
            AnimalIgnoredTroughExpiry.resetBroken();
            AnimalIgnoredTroughExpiry.onZoneCheck(new Object());
            assertTrue(AnimalIgnoredTroughExpiry.isBroken());
            // Latched: a second call must be a silent no-op, never a throw into checkZone.
            AnimalIgnoredTroughExpiry.onZoneCheck(new Object());
        } finally {
            AnimalIgnoredTroughExpiry.resetBroken();
        }
        assertFalse(AnimalIgnoredTroughExpiry.isBroken());
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
