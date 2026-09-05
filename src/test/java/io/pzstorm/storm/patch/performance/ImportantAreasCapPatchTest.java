package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.metrics.ImportantAreasMetrics;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.core.ImportantArea;
import zombie.core.random.RandStandard;
import zombie.network.GameServer;

/**
 * Verifies {@link ImportantAreasCapPatch} on two levels: that the advice lands on {@code
 * updateOrAdd} and nowhere else, and that the patched class, asked the real question through the
 * real method on its own static list, honours the configured cap and evicts the
 * least-recently-refreshed entry.
 *
 * <p>⭐ Every behaviour case is paired with the same call through the <em>unpatched</em> class,
 * loaded parent-last from the same bytes. {@link #vanillaControlCapsAtOneHundredAndEvictsAtRandom}
 * is the anchor: it asserts that the engine really does stop at 100 and really does evict a random
 * entry. If that control ever stops failing to be random, or the cap moves, the defect this patch
 * exists for has changed shape and the rest of the suite means nothing.
 */
class ImportantAreasCapPatchTest implements UnitTest {

    private static final String TARGET = "zombie.core.ImportantAreaManager";
    private static final String TARGET_RES = "zombie/core/ImportantAreaManager.class";
    private static final String METHOD = "updateOrAdd";
    private static final String HELPER = "io/pzstorm/storm/patch/performance/ImportantAreasPolicy";

    private static final int VANILLA_CAP = 100;
    private static final int TRIALS = 40;

    private boolean savedServerFlag;

    /** Vanilla's eviction goes through {@code Rand.Next}, which needs the engine RNG seeded. */
    @BeforeAll
    static void seedEngineRandom() {
        RandStandard.INSTANCE.init();
    }

    /**
     * ⛔ {@code GameServer.server} is process-wide; see {@code
     * RequestDataManagerJoinStallPatchTest}.
     */
    @BeforeEach
    void captureState() {
        savedServerFlag = GameServer.server;
        ImportantAreasPolicy.resetForTest();
        ImportantAreasMetrics.resetForTest();
    }

    @AfterEach
    void restoreState() {
        GameServer.server = savedServerFlag;
        ImportantAreasPolicy.resetForTest();
        ImportantAreasMetrics.resetForTest();
    }

    // ---------------------------------------------------------------- placement

    @Test
    void adviceLandsOnUpdateOrAddOnly() throws Exception {
        byte[] raw = readClassBytes();
        byte[] patched = new ImportantAreasCapPatch().transform(raw);
        assertNotNull(patched);
        assertTrue(patched.length > 0);
        assertEquals(0, helperCalls(raw, null), "vanilla must not call the helper anywhere");
        assertTrue(helperCalls(patched, METHOD) >= 1, "advice must land in updateOrAdd");
        assertEquals(0, helperCalls(patched, "process"), "must not touch process");
        assertEquals(0, helperCalls(patched, "load"), "must not touch load");
        assertEquals(0, helperCalls(patched, "save"), "must not touch save");
        assertEquals(0, helperCalls(patched, "saveDataFile"), "must not touch saveDataFile");
    }

    @Test
    void patchRefusesIfTheEngineMethodIsGone() throws Exception {
        byte[] renamed = renameMethod(readClassBytes(), METHOD, "somethingElse");
        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> new ImportantAreasCapPatch().transform(renamed));
        Throwable cause = thrown.getCause() == null ? thrown : thrown.getCause();
        assertTrue(
                cause.getMessage() != null && cause.getMessage().contains(METHOD),
                "the refusal must name the method that moved, got: " + cause.getMessage());
    }

    @Test
    void patchRefusesIfTheListFieldIsGone() throws Exception {
        byte[] renamed = renameField(readClassBytes(), "ImportantAreas", "Areas");
        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> new ImportantAreasCapPatch().transform(renamed));
        Throwable cause = thrown.getCause() == null ? thrown : thrown.getCause();
        assertTrue(
                cause.getMessage() != null && cause.getMessage().contains("ImportantAreas"),
                "the refusal must name the field that moved, got: " + cause.getMessage());
    }

    // ---------------------------------------------------------------- behaviour

    /**
     * The anchor control: the unpatched engine stops at 100, returns {@code null} to the caller
     * that pushed it over, and picks its victim at random. Forty trials on a list whose oldest
     * entry is always at index 0: the chance vanilla's {@code Rand.Next(0, 100)} lands on index 0
     * forty times running is one in ten to the eighty.
     */
    @Test
    void vanillaControlCapsAtOneHundredAndEvictsAtRandom() throws Exception {
        Harness vanilla = new Harness(readClassBytes());
        int lruEvictions = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            fillAscending(vanilla, VANILLA_CAP);
            assertNull(vanilla.book(VANILLA_CAP, 0), "the 101st booking gets nothing");
            assertEquals(VANILLA_CAP - 1, vanilla.areas.size(), "one incumbent lost its slot");
            if (evictedIndex(vanilla, VANILLA_CAP) == 0) {
                lruEvictions++;
            }
        }
        assertTrue(
                lruEvictions < TRIALS,
                "vanilla must evict at random, not the oldest; it evicted the oldest "
                        + lruEvictions
                        + "/"
                        + TRIALS);
    }

    /**
     * At the default the cap is still 100 and the return is still null, but the victim is the
     * oldest.
     */
    @Test
    void patchedDefaultKeepsVanillasCapAndEvictsTheLeastRecentlyRefreshed() throws Exception {
        GameServer.server = true;
        Harness patched = new Harness(patched());
        assertEquals(ImportantAreasPolicy.VANILLA_MAXIMUM, ImportantAreasPolicy.getMaximum());
        for (int trial = 0; trial < TRIALS; trial++) {
            fillAscending(patched, VANILLA_CAP);
            assertNull(patched.book(VANILLA_CAP, 0), "the 101st booking still gets nothing");
            assertEquals(VANILLA_CAP - 1, patched.areas.size());
            assertEquals(
                    0, evictedIndex(patched, VANILLA_CAP), "the oldest entry goes, every time");
        }
        assertEquals(TRIALS, ImportantAreasMetrics.evictions);
        assertEquals(VANILLA_CAP - 1, ImportantAreasMetrics.size);
    }

    /**
     * The oldest is not necessarily the earliest booked: a stale entry in the middle goes first.
     */
    @Test
    void aStaleEntryInTheMiddleIsTheOneEvicted() throws Exception {
        GameServer.server = true;
        Harness patched = new Harness(patched());
        fillAscending(patched, VANILLA_CAP);
        for (ImportantArea area : patched.areas) {
            area.lastUpdate = 50_000L;
        }
        patched.areas.get(42).lastUpdate = 1_000L;

        assertNull(patched.book(VANILLA_CAP, 0));
        assertEquals(42, evictedIndex(patched, VANILLA_CAP));
    }

    @Test
    void refreshingAnExistingEntryNeverAddsOrEvicts() throws Exception {
        GameServer.server = true;
        Harness patched = new Harness(patched());
        fillAscending(patched, VANILLA_CAP);
        ImportantArea before = patched.areas.get(5);
        long stampBefore = before.lastUpdate;

        ImportantArea returned = patched.book(5, 0);

        assertSame(before, returned, "the existing entry is returned, as vanilla returns it");
        assertEquals(VANILLA_CAP, patched.areas.size());
        assertTrue(returned.lastUpdate > stampBefore, "and its refresh stamp moved");
        assertEquals(0, ImportantAreasMetrics.evictions);
    }

    /** The option is live: a raised cap admits more, a lowered one trims one entry per miss. */
    @Test
    void theCapIsTheSandboxOptionAndAppliesLive() throws Exception {
        GameServer.server = true;
        Harness patched = new Harness(patched());
        ImportantAreasPolicy.setMaximum(300);

        for (int i = 0; i < 300; i++) {
            assertNotNull(patched.book(i, 0), "booking " + i + " must fit under a cap of 300");
        }
        assertEquals(300, patched.areas.size());
        stampAscending(patched);
        assertNull(patched.book(300, 0), "the 301st gets nothing");
        assertEquals(299, patched.areas.size());
        assertEquals(0, evictedIndex(patched, 300));

        ImportantAreasPolicy.setMaximum(100);
        assertNull(patched.book(301, 0));
        assertEquals(298, patched.areas.size(), "a lowered cap trims one per miss, not to the cap");
        assertEquals(2, ImportantAreasMetrics.evictions);
    }

    /** Off the server the advice changes nothing: vanilla's 100 and vanilla's random victim. */
    @Test
    void clientPathIsUntouched() throws Exception {
        GameServer.server = false;
        ImportantAreasPolicy.setMaximum(300);
        Harness patched = new Harness(patched());
        fillAscending(patched, VANILLA_CAP);
        assertNull(patched.book(VANILLA_CAP, 0), "vanilla's cap, not the option's");
        assertEquals(VANILLA_CAP - 1, patched.areas.size());
        assertEquals(0, ImportantAreasMetrics.evictions, "the policy never ran");
    }

    @Test
    void aLatchedFailureRestoresVanilla() throws Exception {
        GameServer.server = true;
        ImportantAreasPolicy.setMaximum(300);
        ImportantAreasPolicy.latchForTest();
        Harness patched = new Harness(patched());
        fillAscending(patched, VANILLA_CAP);
        assertNull(patched.book(VANILLA_CAP, 0), "latched: vanilla's cap");
        assertEquals(VANILLA_CAP - 1, patched.areas.size());
        assertEquals(0, ImportantAreasMetrics.evictions);
    }

    /** Same call, both classes, below the cap: identical answers, so nothing else moved. */
    @Test
    void belowTheCapPatchedAndVanillaAgree() throws Exception {
        GameServer.server = true;
        Harness vanilla = new Harness(readClassBytes());
        Harness patched = new Harness(patched());
        for (int i = 0; i < 50; i++) {
            ImportantArea v = vanilla.book(i * 3, i * 7);
            ImportantArea p = patched.book(i * 3, i * 7);
            assertEquals(v.sx, p.sx);
            assertEquals(v.sy, p.sy);
        }
        assertEquals(vanilla.areas.size(), patched.areas.size());
        assertSame(patched.areas.get(3), patched.book(9, 21), "refresh returns the same entry");
        assertSame(vanilla.areas.get(3), vanilla.book(9, 21));
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] patched() throws Exception {
        return new ImportantAreasCapPatch().transform(readClassBytes());
    }

    /** Books {@code n} distinct areas at sx = 0..n-1 and stamps them oldest-first. */
    private static void fillAscending(Harness h, int n) throws Exception {
        h.areas.clear();
        for (int i = 0; i < n; i++) {
            assertNotNull(h.book(i, 0));
        }
        assertEquals(n, h.areas.size());
        stampAscending(h);
    }

    private static void stampAscending(Harness h) {
        long stamp = 1_000L;
        for (ImportantArea area : h.areas) {
            area.lastUpdate = stamp++;
        }
    }

    /** Which of the areas sx = 0..n-1 is no longer on the list. Exactly one must be missing. */
    private static int evictedIndex(Harness h, int n) {
        Set<Integer> present = new HashSet<>();
        for (ImportantArea area : h.areas) {
            present.add(area.sx);
        }
        int missing = -1;
        for (int i = 0; i < n; i++) {
            if (!present.contains(i)) {
                assertEquals(-1, missing, "exactly one entry must have been evicted");
                missing = i;
            }
        }
        assertTrue(missing >= 0, "one entry must have been evicted");
        return missing;
    }

    /**
     * Loads the manager under test parent-last, so its static list and singleton are its own, and
     * drives the real {@code updateOrAdd} through reflection exactly as a stove or vehicle would.
     * Coordinates are passed in tiles; {@code book(sx, sy)} lands in area {@code (sx, sy)}.
     */
    private static final class Harness extends ClassLoader {
        private final byte[] target;
        private final Object manager;
        private final Method updateOrAdd;

        @SuppressWarnings("unchecked")
        final LinkedList<ImportantArea> areas;

        @SuppressWarnings("unchecked")
        Harness(byte[] target) throws Exception {
            super(ImportantAreasCapPatchTest.class.getClassLoader());
            this.target = target;
            Class<?> c = loadClass(TARGET);
            this.manager = c.getMethod("getInstance").invoke(null);
            this.updateOrAdd = c.getMethod(METHOD, int.class, int.class);
            this.areas = (LinkedList<ImportantArea>) c.getField("ImportantAreas").get(null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (TARGET.equals(name)) {
                Class<?> already = findLoadedClass(name);
                if (already != null) {
                    return already;
                }
                return defineClass(name, target, 0, target.length);
            }
            return super.loadClass(name, resolve);
        }

        ImportantArea book(int sx, int sy) throws Exception {
            return (ImportantArea)
                    updateOrAdd.invoke(
                            manager,
                            sx * ImportantAreasPolicy.AREA_TILES + 3,
                            sy * ImportantAreasPolicy.AREA_TILES + 5);
        }
    }

    private static byte[] readClassBytes() throws Exception {
        try (InputStream is =
                ImportantAreasCapPatchTest.class.getClassLoader().getResourceAsStream(TARGET_RES)) {
            assertNotNull(is, TARGET_RES + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** Declaration only, enough for {@code TypePool.describe} to stop finding it; never loaded. */
    private static byte[] renameMethod(byte[] classBytes, String from, String to) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                String renamed = from.equals(name) ? to : name;
                                return super.visitMethod(
                                        access, renamed, descriptor, signature, exceptions);
                            }
                        },
                        0);
        return writer.toByteArray();
    }

    private static byte[] renameField(byte[] classBytes, String from, String to) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public net.bytebuddy.jar.asm.FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                String renamed = from.equals(name) ? to : name;
                                return super.visitField(
                                        access, renamed, descriptor, signature, value);
                            }
                        },
                        0);
        return writer.toByteArray();
    }

    /** INVOKESTATIC calls into the helper's {@code decide}, in {@code method} (or anywhere). */
    private static int helperCalls(byte[] classBytes, String method) {
        int[] count = {0};
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
                                if (method != null && !method.equals(name)) {
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
                                                && HELPER.equals(owner)
                                                && "decide".equals(mName)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }
}
