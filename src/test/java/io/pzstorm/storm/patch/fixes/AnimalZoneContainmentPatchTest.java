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
 * Verifies that {@link AnimalZoneContainmentPatch} weaves the containment helper into exactly the
 * three {@code IsoAnimal} methods that let livestock leave a player's animal zone, and unit-tests
 * the pure geometry and option clamping in {@link AnimalZoneContainment}.
 */
class AnimalZoneContainmentPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/characters/animals/IsoAnimal";
    private static final String HELPER_CLASS = "io/pzstorm/storm/patch/fixes/AnimalZoneContainment";

    @Test
    void patchInjectsHelperIntoContainmentMethodsOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new AnimalZoneContainmentPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(
                0,
                countHelperCalls(rawClass, "shouldBreakObstaclesDuringPathfinding", "()Z"),
                "Vanilla IsoAnimal must not reference the Storm helper");

        assertTrue(
                countHelperCalls(transformed, "shouldBreakObstaclesDuringPathfinding", "()Z") >= 1,
                "Pathfinder obstacle permission must run through " + HELPER_CLASS);
        assertTrue(
                countHelperCalls(transformed, "animalShouldThump", "()Z") >= 1,
                "Thump permission must run through " + HELPER_CLASS);
        assertTrue(
                countHelperCalls(transformed, "pathToLocation", "(III)V") >= 1,
                "Path targets must run through " + HELPER_CLASS);

        // Nearby methods that must keep vanilla behavior: the zone bookkeeping pass, and the
        // trough/character path helpers that feed pathToLocation rather than duplicating it.
        assertEquals(0, countHelperCalls(transformed, "checkZone", null));
        assertEquals(0, countHelperCalls(transformed, "pathToTrough", null));
        assertEquals(0, countHelperCalls(transformed, "updateInternal", null));
    }

    @Test
    void rectDistanceIsZeroInsideAndChebyshevOutside() {
        // Zone rectangles cover [x, x + w) by [y, y + h) — a 10x10 zone at (100, 100) owns
        // 100..109 on both axes.
        assertEquals(0, AnimalZoneContainment.rectDistance(100, 100, 10, 10, 100, 100));
        assertEquals(0, AnimalZoneContainment.rectDistance(100, 100, 10, 10, 109, 109));
        assertEquals(1, AnimalZoneContainment.rectDistance(100, 100, 10, 10, 110, 105));
        assertEquals(1, AnimalZoneContainment.rectDistance(100, 100, 10, 10, 99, 99));
        assertEquals(20, AnimalZoneContainment.rectDistance(100, 100, 10, 10, 129, 105));
        assertEquals(5, AnimalZoneContainment.rectDistance(100, 100, 10, 10, 95, 103));
    }

    @Test
    void packRoundTripsNegativeCoordinates() {
        long packed = AnimalZoneContainment.pack(-1234, 5678);
        assertEquals(-1234, AnimalZoneContainment.unpackX(packed));
        assertEquals(5678, AnimalZoneContainment.unpackY(packed));

        packed = AnimalZoneContainment.pack(7000, -9000);
        assertEquals(7000, AnimalZoneContainment.unpackX(packed));
        assertEquals(-9000, AnimalZoneContainment.unpackY(packed));
    }

    @Test
    void disabledContainmentLeavesVanillaDecisionsAlone() {
        try {
            AnimalZoneContainment.setEnabled(false);
            // No cast against the game class happens while disabled, so a bare Object is enough
            // to prove the helper short-circuits before touching the animal.
            assertTrue(AnimalZoneContainment.allowObstacleBreaking(new Object(), true));
            assertEquals(
                    AnimalZoneContainment.NO_CLAMP,
                    AnimalZoneContainment.clampTarget(new Object(), 10, 10, 0));
        } finally {
            AnimalZoneContainment.setEnabled(AnimalZoneContainment.DEFAULT_ENABLED);
        }
    }

    @Test
    void vanillaFalseIsNeverUpgradedToTrue() {
        // The advice may only take permission away, never grant it — an animal whose definition
        // has canThump off must stay unable to break obstacles.
        assertFalse(AnimalZoneContainment.allowObstacleBreaking(new Object(), false));
    }

    @Test
    void setLeashDistanceClampsAndReturnsApplied() {
        try {
            assertEquals(20, AnimalZoneContainment.setLeashDistance(20));
            assertEquals(0, AnimalZoneContainment.setLeashDistance(-5));
            assertEquals(200, AnimalZoneContainment.setLeashDistance(5000));
            assertEquals(64, AnimalZoneContainment.setLeashDistance(64));
            assertEquals(64, AnimalZoneContainment.getLeashDistance());
        } finally {
            AnimalZoneContainment.setLeashDistance(AnimalZoneContainment.DEFAULT_LEASH_DISTANCE);
        }
    }

    @Test
    void setEnabledRoundTrips() {
        try {
            assertFalse(AnimalZoneContainment.setEnabled(false));
            assertFalse(AnimalZoneContainment.isEnabled());
            assertTrue(AnimalZoneContainment.setEnabled(true));
            assertTrue(AnimalZoneContainment.isEnabled());
        } finally {
            AnimalZoneContainment.setEnabled(AnimalZoneContainment.DEFAULT_ENABLED);
        }
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
