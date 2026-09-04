package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.popman.PopManCell;
import io.pzstorm.storm.popman.PopManCore;
import io.pzstorm.storm.popman.PopManRepopulateTask;
import io.pzstorm.storm.popman.PopManWorld;
import io.pzstorm.storm.popman.PopManZombie;
import io.pzstorm.storm.popman.ZpopCell;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link PopManSaveAdoptFixPatch} weaves the helper into {@code PopManCore.save()}
 * and {@code saveCell(int, int)} only, and that {@link PopManSaveAdoptFix} around a real save
 * leaves the cell file holding the staged zombie while the resident population does not.
 */
class PopManSaveAdoptFixPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "io/pzstorm/storm/popman/PopManCore";
    private static final String HELPER_CLASS = "io/pzstorm/storm/patch/fixes/PopManSaveAdoptFix";
    private static final long NOW_MS = 5_000L;

    @TempDir Path saveDirectory;

    @Test
    void patchInjectsHelperIntoBothSavesOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new PopManSaveAdoptFixPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(0, countHelperCalls(rawClass, "save", "()V"));
        assertEquals(0, countHelperCalls(rawClass, "saveCell", "(II)V"));
        assertEquals(
                2,
                countHelperCalls(transformed, "save", "()V"),
                "Patched save() must call the helper on entry and exit");
        assertEquals(
                2,
                countHelperCalls(transformed, "saveCell", "(II)V"),
                "Patched saveCell() must call the helper on entry and exit");
        assertEquals(0, countHelperCalls(transformed, "saveRealZombies", null));
        assertEquals(0, countHelperCalls(transformed, "beginSaveRealZombies", null));
        assertEquals(0, countHelperCalls(transformed, "evictIdleCells", null));
    }

    @Test
    void cellSaveWritesTheStagedZombieButKeepsItOutOfTheResidentPopulation() throws Exception {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.realCount = 1;
        PopManZombie live = PopManZombie.spawnedAt(24, 32, () -> 0);
        core.stagedRealZombies().add(live);

        Object snapshot = PopManSaveAdoptFix.beforeSave(core);
        core.saveCell(0, 0);
        PopManSaveAdoptFix.afterSave(core, snapshot);

        assertFalse(PopManSaveAdoptFix.isBroken());
        assertFalse(cell.chunkAt(3, 4).zombies.contains(live), "no virtual twin in memory");
        assertEquals(0, cell.virtualCount);
        assertEquals(1, cell.realCount);
        assertTrue(core.stagedRealZombies().isEmpty());
        assertEquals(1, storedZombies(0, 0, 3, 4), "the file still records the live zombie");
    }

    @Test
    void worldSaveRehomesIntoEveryCellWithoutTwinning() throws Exception {
        PopManCore core = runningCore();
        PopManCell home = core.cells().load(0, 0, 0.0, NOW_MS);
        PopManCell neighbour = core.cells().load(1, 0, 0.0, NOW_MS);
        home.realCount = 2;
        home.virtualCount = 5;
        PopManZombie stayed = PopManZombie.spawnedAt(24, 32, () -> 0);
        PopManZombie crossed = PopManZombie.spawnedAt(300, 32, () -> 0);
        core.stagedRealZombies().add(stayed);
        core.stagedRealZombies().add(crossed);

        Object snapshot = PopManSaveAdoptFix.beforeSave(core);
        core.save();
        PopManSaveAdoptFix.afterSave(core, snapshot);

        assertFalse(PopManSaveAdoptFix.isBroken());
        assertFalse(home.chunkAt(3, 4).zombies.contains(stayed));
        assertFalse(neighbour.chunkAtSquare(300, 32).zombies.contains(crossed));
        assertEquals(5, home.virtualCount);
        assertEquals(2, home.realCount);
        assertEquals(0, neighbour.virtualCount);
        assertEquals(0, neighbour.realCount);
        assertEquals(1, storedZombies(0, 0, 3, 4));
        assertEquals(1, storedZombies(1, 0, (300 - 256) / 8, 4));
    }

    @Test
    void secondSaveDoesNotAccumulateCopiesInMemory() throws Exception {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.realCount = 1;
        PopManZombie live = PopManZombie.spawnedAt(24, 32, () -> 0);

        for (int save = 0; save < 3; save++) {
            core.stagedRealZombies().add(live);
            Object snapshot = PopManSaveAdoptFix.beforeSave(core);
            core.save();
            PopManSaveAdoptFix.afterSave(core, snapshot);
        }

        assertTrue(cell.chunkAt(3, 4).zombies.isEmpty());
        assertEquals(0, cell.virtualCount);
        assertEquals(1, cell.realCount);
        assertEquals(1, storedZombies(0, 0, 3, 4), "the file holds one record, not three");
    }

    @Test
    void nothingStagedIsANoOp() {
        PopManCore core = runningCore();
        PopManCell cell = core.cells().load(0, 0, 0.0, NOW_MS);
        cell.virtualCount = 7;
        cell.chunkAt(1, 1).zombies.add(PopManZombie.spawnedAt(8, 8, () -> 0));

        Object snapshot = PopManSaveAdoptFix.beforeSave(core);
        core.save();
        PopManSaveAdoptFix.afterSave(core, snapshot);

        assertEquals(7, cell.virtualCount);
        assertEquals(1, cell.chunkAt(1, 1).zombies.size());
    }

    private int storedZombies(int cellX, int cellY, int localX, int localY) throws Exception {
        Path file = saveDirectory.resolve("zpop").resolve("zpop_" + cellX + "_" + cellY + ".bin");
        assertTrue(Files.exists(file), file + " must have been written");
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            return ZpopCell.read(in, null).chunk(localX, localY).zombies.size();
        }
    }

    private PopManCore runningCore() {
        PopManCore core = new PopManCore();
        core.setEnvironment(
                new PopManCore.Environment() {
                    @Override
                    public PopManWorld world() {
                        return new PopManWorld() {
                            @Override
                            public int squareFlags(int squareX, int squareY) {
                                return 0;
                            }

                            @Override
                            public int densityByte(int chunkX, int chunkY) {
                                return 0;
                            }
                        };
                    }

                    @Override
                    public Path saveDirectory() {
                        return saveDirectory;
                    }

                    @Override
                    public void requestPath(
                            int fx, int fy, int tx, int ty, PopManRepopulateTask task) {}
                });
        core.init(false, true, 0, 0, 10, 10);
        return core;
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
