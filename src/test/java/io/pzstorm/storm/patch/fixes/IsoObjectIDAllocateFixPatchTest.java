package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.IntegrationTest;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import zombie.core.random.RandAbstract;
import zombie.core.random.RandStandard;

/**
 * End-to-end test for {@link IsoObjectIDAllocateFixPatch}. Reads {@code zombie.network.IsoObjectID}
 * off the test classpath, runs the real patch's {@code transform()}, defines the result in a fresh
 * child {@link ClassLoader} so it has a distinct identity from the parent-loaded class, and then
 * exercises the patched {@code allocateID()} against several map shapes to confirm the vanilla
 * counter has been replaced with a probe-for-free.
 *
 * <p>The shape assertion in {@link #patchedAllocateIdCallsProbeFromAllocateId()} catches a
 * regression where the matcher widens or narrows; the runtime tests catch a regression where the
 * advice itself stops behaving like the algorithm it wraps.
 */
class IsoObjectIDAllocateFixPatchTest implements IntegrationTest {

    private static final String ISO_OBJECT_ID = "zombie.network.IsoObjectID";
    private static final String ALLOCATE_ID = "allocateID";
    private static final String PROBE_INTERNAL = IsoObjectIDProbe.class.getName().replace('.', '/');
    private static final String NEXT_FREE_ID = "nextFreeId";

    private static Class<?> patchedClass;

    @BeforeAll
    static void applyPatch() throws Exception {
        // IsoObjectID's constructor calls Rand.Next(32766) to seed nextId. Rand delegates to
        // RandStandard.INSTANCE, whose `rand` field is only populated by RandStandard.init() at
        // game boot — not in tests. Inject a deterministic Random so the constructor doesn't NPE.
        Field randField = RandAbstract.class.getDeclaredField("rand");
        randField.setAccessible(true);
        if (randField.get(RandStandard.INSTANCE) == null) {
            randField.set(RandStandard.INSTANCE, new Random(0));
        }

        ClassLoader parent = IsoObjectIDAllocateFixPatchTest.class.getClassLoader();
        String resourcePath = ISO_OBJECT_ID.replace('.', '/') + ".class";
        byte[] rawClass;
        try (InputStream is = parent.getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new IsoObjectIDAllocateFixPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        patchedClass = defineClassFromBytes(parent, ISO_OBJECT_ID, transformed);
    }

    @Test
    void patchedAllocateIdCallsProbeFromAllocateId() throws Exception {
        // Bytecode shape: after the transform, allocateID() must contain an INVOKESTATIC to
        // IsoObjectIDProbe.nextFreeId. If a future change drops this (matcher narrowed, advice
        // class renamed, etc.) the runtime probe never runs and the fix silently regresses.
        ClassLoader parent = IsoObjectIDAllocateFixPatchTest.class.getClassLoader();
        String resourcePath = ISO_OBJECT_ID.replace('.', '/') + ".class";
        byte[] rawClass;
        try (InputStream is = parent.getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            rawClass = is.readAllBytes();
        }
        byte[] transformed = new IsoObjectIDAllocateFixPatch().transform(rawClass);

        int before = countProbeCallsInAllocateId(rawClass);
        int after = countProbeCallsInAllocateId(transformed);

        assertEquals(0, before, "Vanilla allocateID() must not reference the probe helper");
        assertTrue(
                after >= 1,
                "Patched allocateID() must call IsoObjectIDProbe.nextFreeId; got " + after);
    }

    @Test
    void patchedAllocateIdReturnsFreeIdInEmptyMap() throws Exception {
        Object instance = newIsoObjectIDInstance();
        setNextId(instance, (short) 100);

        short id = invokeAllocateID(instance);

        // Patched allocateID returns the FIRST free slot from cursor+1 — with an empty map that's
        // just cursor+1.
        assertEquals((short) 101, id);
        // And the cursor field must have advanced to match — confirms the @FieldValue writeback.
        assertEquals((short) 101, getNextId(instance));
    }

    @Test
    void patchedAllocateIdSkipsOccupiedSlots() throws Exception {
        Object instance = newIsoObjectIDInstance();
        setNextId(instance, (short) 100);

        ConcurrentMap<Short, Object> map = getIdToObjectMap(instance);
        Object filler = new Object();
        for (short id = 101; id <= 110; id++) {
            map.put(id, filler);
        }

        short allocated = invokeAllocateID(instance);
        assertEquals((short) 111, allocated, "Patched allocateID must skip the occupied run");
        assertEquals((short) 111, getNextId(instance));
    }

    @Test
    void patchedAllocateIdSkipsInvalidSentinelOnWrap() throws Exception {
        Object instance = newIsoObjectIDInstance();
        setNextId(instance, (short) -2);

        short id = invokeAllocateID(instance);

        // From cursor=-2, vanilla would return -1 (which is the sentinel — illegal). The patched
        // version walks past -1 and returns 0.
        assertNotEquals(
                IsoObjectIDProbe.ID_INVALID, id, "Patched allocateID must never hand out -1");
        assertEquals((short) 0, id);
    }

    @Test
    void patchedAllocateIdReturnsInvalidWhenPoolIsExhausted() throws Exception {
        Object instance = newIsoObjectIDInstance();
        setNextId(instance, (short) 0);

        ConcurrentMap<Short, Object> map = getIdToObjectMap(instance);
        Object filler = new Object();
        for (int i = Short.MIN_VALUE; i <= Short.MAX_VALUE; i++) {
            if (i == IsoObjectIDProbe.ID_INVALID) {
                continue;
            }
            map.put((short) i, filler);
        }

        short id = invokeAllocateID(instance);

        assertEquals(IsoObjectIDProbe.ID_INVALID, id);
        // Cursor must NOT have advanced on exhaustion — the next call needs to probe from the
        // same starting point so a just-freed slot is picked up immediately.
        assertEquals((short) 0, getNextId(instance));
    }

    @Test
    void patchedAllocateIdNeverHandsOutDuplicatesAcrossManyCalls() throws Exception {
        Object instance = newIsoObjectIDInstance();
        setNextId(instance, (short) 0);

        ConcurrentMap<Short, Object> map = getIdToObjectMap(instance);
        Object filler = new Object();

        // Pre-fill ~10k slots to simulate the production "uncapped zombies" shape where the
        // collision rate is high under the vanilla counter.
        for (int i = 0; i < 10_000; i++) {
            map.put((short) (i * 3), filler);
        }
        int prefill = map.size();

        Set<Short> handedOut = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            short id = invokeAllocateID(instance);
            assertNotEquals(IsoObjectIDProbe.ID_INVALID, id, "Should not exhaust mid-test");
            assertTrue(handedOut.add(id), "Duplicate ID returned: " + id);
            map.put(id, filler);
        }

        assertEquals(prefill + 5_000, map.size(), "No collisions should overwrite seeded entries");
    }

    // ---- helpers ----

    private static Object newIsoObjectIDInstance() throws Exception {
        // IsoObjectID is generic — the constructor takes Class<T>. Use String.class to keep
        // dependencies minimal (no game classes are pulled in by the type parameter).
        return patchedClass.getDeclaredConstructor(Class.class).newInstance(String.class);
    }

    private static void setNextId(Object isoObjectID, short value) throws Exception {
        Field f = patchedClass.getDeclaredField("nextId");
        f.setAccessible(true);
        f.setShort(isoObjectID, value);
    }

    private static short getNextId(Object isoObjectID) throws Exception {
        Field f = patchedClass.getDeclaredField("nextId");
        f.setAccessible(true);
        return f.getShort(isoObjectID);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<Short, Object> getIdToObjectMap(Object isoObjectID)
            throws Exception {
        Field f = patchedClass.getDeclaredField("idToObjectMap");
        f.setAccessible(true);
        return (ConcurrentMap<Short, Object>) f.get(isoObjectID);
    }

    private static short invokeAllocateID(Object isoObjectID) throws Exception {
        Method m = patchedClass.getDeclaredMethod(ALLOCATE_ID);
        return (short) m.invoke(isoObjectID);
    }

    private static Class<?> defineClassFromBytes(ClassLoader parent, String name, byte[] bytes) {
        return new ClassLoader(parent) {
            Class<?> define() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.define();
    }

    private static int countProbeCallsInAllocateId(byte[] classBytes) {
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
                                if (!ALLOCATE_ID.equals(name) || !"()S".equals(descriptor)) {
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
                                                && PROBE_INTERNAL.equals(owner)
                                                && NEXT_FREE_ID.equals(mName)) {
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
