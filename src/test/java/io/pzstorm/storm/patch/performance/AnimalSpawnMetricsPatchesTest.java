package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.core.StormClassTransformer;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every animal-spawn observability patch injects its {@code AnimalSpawnMetrics} call
 * into the method it targets — and into no sibling. {@code AllPatchesIntegrationTest} only proves
 * the transformed bytecode verifies; a matcher that silently matches nothing passes it too, and the
 * metric would then read a permanent zero.
 */
class AnimalSpawnMetricsPatchesTest implements UnitTest {

    private static final String METRICS = "io/pzstorm/storm/metrics/AnimalSpawnMetrics";

    @Test
    void zoneSpawnPatchWrapsSpawnAnimalsOnZone() throws Exception {
        byte[] patched =
                transform(
                        "zombie/characters/animals/AnimalZones", new AnimalZoneSpawnMetricsPatch());

        assertEquals(1, calls(patched, "spawnAnimalsOnZone", null, "beginZoneSpawn"));
        assertEquals(1, calls(patched, "spawnAnimalsOnZone", null, "endZoneSpawn"));
        assertEquals(
                0,
                calls(patched, "spawnAnimalsInCell", null, "beginZoneSpawn"),
                "advice must not leak into the per-cell caller");
    }

    @Test
    void virtualRegisterPatchWrapsAddAnimal() throws Exception {
        byte[] patched =
                transform(
                        "zombie/characters/animals/AnimalManagerWorker",
                        new AnimalVirtualRegisterMetricsPatch());

        assertEquals(1, calls(patched, "addAnimal", null, "recordVirtualRegistered"));
        assertEquals(
                0,
                calls(patched, "moveAnimal", null, "recordVirtualRegistered"),
                "advice must not leak into moveAnimal, which calls addAnimal itself");
    }

    @Test
    void cellLoadPatchWrapsNoArgLoadOnly() throws Exception {
        byte[] patched =
                transform("zombie/characters/animals/AnimalCell", new AnimalCellLoadMetricsPatch());

        assertEquals(1, calls(patched, "load", "()V", "recordCellLoad"));
        assertEquals(
                0,
                calls(patched, "load", "(Ljava/lang/String;)Z", "recordCellLoad"),
                "the load(String) overload must stay untouched");
    }

    @Test
    void realizePatchWrapsFromWorker() throws Exception {
        byte[] patched =
                transform(
                        "zombie/characters/animals/AnimalManagerMain",
                        new AnimalRealizeMetricsPatch());

        assertEquals(1, calls(patched, "fromWorker", null, "recordRealizeBatch"));
        assertEquals(
                0,
                calls(patched, "addAnimal", null, "recordRealizeBatch"),
                "advice must not leak into the sibling one-argument addAnimal");
    }

    @Test
    void ranchPatchWrapsCheckAndRandomize() throws Exception {
        byte[] patched =
                transform(
                        "zombie/randomizedWorld/randomizedRanch/RandomizedRanchBase",
                        new RanchAnimalSpawnMetricsPatch());

        assertEquals(1, calls(patched, "checkRanchStory", null, "recordRanchCheck"));
        assertEquals(1, calls(patched, "randomizeRanch", null, "recordRanchSpawn"));
        assertEquals(
                0,
                calls(patched, "doRandomRanch", null, "recordRanchSpawn"),
                "the two-argument matcher must not catch the three-argument roll helper");
    }

    private byte[] transform(String internalName, StormClassTransformer patch) throws Exception {
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " must be on the test classpath");
            byte[] raw = is.readAllBytes();
            assertEquals(
                    0,
                    countAll(raw),
                    "vanilla " + internalName + " must not reference AnimalSpawnMetrics");
            byte[] patched = patch.transform(raw);
            assertNotNull(patched);
            return patched;
        }
    }

    private static int countAll(byte[] classBytes) {
        return calls(classBytes, null, null, null);
    }

    /**
     * Counts {@code AnimalSpawnMetrics.<helper>} call sites, filtered by method name/descriptor.
     */
    private static int calls(byte[] classBytes, String method, String desc, String helper) {
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
                                if ((method != null && !method.equals(name))
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
                                                && METRICS.equals(owner)
                                                && (helper == null || helper.equals(mName))) {
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
