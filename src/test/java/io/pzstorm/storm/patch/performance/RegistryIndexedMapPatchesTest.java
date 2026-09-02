package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.core.StormClassTransformer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Every GETFIELD of the vanilla map field on {@code Stats}, {@code Moodles} and {@code
 * CharacterTraits} must become a {@code StormIndexedMaps.mapOf} call (the substitution is {@code
 * relaxed()}, so a matcher miss would be silent); the constructor PUTFIELD stays; each registry key
 * class gains its index field and one allocator call per constructor; {@code Stats.get} reads
 * through the unboxed helper.
 */
class RegistryIndexedMapPatchesTest implements UnitTest {

    private static final String STATS = "zombie/characters/Stats";
    private static final String MOODLES = "zombie/characters/Moodles/Moodles";
    private static final String TRAITS = "zombie/characters/traits/CharacterTraits";
    private static final String STAT_KEY = "zombie/characters/CharacterStat";
    private static final String MOODLE_KEY = "zombie/scripting/objects/MoodleType";
    private static final String TRAIT_KEY = "zombie/scripting/objects/CharacterTrait";

    @Test
    void statsReadsAreRedirectedAndGetIsUnboxed() throws Exception {
        byte[] transformed = assertMapRedirected(STATS, "stats", new StatsGetPatch(), 3);
        Map<String, int[]> after = count(transformed, STATS, "stats", "getFloat");
        int[] get = after.get("get(Lzombie/characters/CharacterStat;)F");
        assertNotNull(get, "Stats.get(CharacterStat) must exist");
        assertEquals(1, get[2], "Stats.get must read through StormIndexedMaps.getFloat");
    }

    @Test
    void moodlesReadsAreRedirected() throws Exception {
        assertMapRedirected(MOODLES, "moodles", new MoodlesIndexedMapPatch(), 5);
    }

    @Test
    void characterTraitsReadsAreRedirected() throws Exception {
        assertMapRedirected(TRAITS, "traits", new CharacterTraitsIndexedMapPatch(), 5);
    }

    @Test
    void registryKeysGainIndexes() throws Exception {
        assertKeyIndexed(STAT_KEY, new CharacterStatIndexPatch(), "nextStat");
        assertKeyIndexed(MOODLE_KEY, new MoodleTypeIndexPatch(), "nextMoodle");
        assertKeyIndexed(TRAIT_KEY, new CharacterTraitIndexPatch(), "nextTrait");
    }

    private static byte[] assertMapRedirected(
            String owner, String field, StormClassTransformer patch, int minVanillaReads)
            throws Exception {
        byte[] raw = readClass(owner);
        byte[] transformed = patch.transform(raw);
        assertNotNull(transformed);
        Map<String, int[]> before = count(raw, owner, field, "mapOf");
        Map<String, int[]> after = count(transformed, owner, field, "mapOf");
        int getsBefore = 0;
        int putsBefore = 0;
        for (Map.Entry<String, int[]> e : before.entrySet()) {
            int[] b = e.getValue();
            int[] a = after.get(e.getKey());
            getsBefore += b[0];
            putsBefore += b[1];
            assertEquals(
                    0, a[0], owner + "." + e.getKey() + " must not read " + field + " directly");
            assertEquals(
                    b[0],
                    a[2],
                    owner + "." + e.getKey() + " helper calls must equal vanilla reads");
            assertEquals(b[1], a[1], owner + "." + e.getKey() + " writes must be untouched");
        }
        assertTrue(
                getsBefore >= minVanillaReads,
                owner + " vanilla reads " + field + ": " + getsBefore);
        assertTrue(putsBefore >= 1, owner + " vanilla ctor initialises " + field);
        Shape shape = shape(transformed);
        assertTrue(shape.fields.contains("stormMap:Ljava/util/Map;"));
        assertTrue(shape.interfaces.contains("io/pzstorm/storm/entity/StormIndexedMapHolder"));
        assertTrue(shape.methods.contains("getStormMap()Ljava/util/Map;"));
        assertTrue(shape.methods.contains("setStormMap(Ljava/util/Map;)V"));
        return transformed;
    }

    private static void assertKeyIndexed(
            String owner, StormClassTransformer patch, String allocator) throws Exception {
        byte[] raw = readClass(owner);
        byte[] transformed = patch.transform(raw);
        assertNotNull(transformed);
        Shape shape = shape(transformed);
        assertEquals(shape(raw).fields.size() + 1, shape.fields.size());
        assertTrue(shape.fields.contains("stormIndex:I"));
        assertTrue(shape.interfaces.contains("io/pzstorm/storm/entity/StormIndexedKeyHolder"));
        assertTrue(shape.methods.contains("getStormIndex()I"));
        Map<String, int[]> after = count(transformed, owner, "stormIndex", allocator);
        int ctors = 0;
        for (Map.Entry<String, int[]> e : after.entrySet()) {
            if (e.getKey().startsWith("<init>")) {
                ctors++;
                assertEquals(
                        1, e.getValue()[2], owner + "." + e.getKey() + " must allocate one index");
                assertEquals(
                        1,
                        e.getValue()[1],
                        owner + "." + e.getKey() + " must write stormIndex once");
            } else {
                assertEquals(0, e.getValue()[2], owner + "." + e.getKey() + " must not allocate");
            }
        }
        assertTrue(ctors >= 1, owner + " constructors: " + ctors);
    }

    /** {0: GETFIELD owner.field, 1: PUTFIELD owner.field, 2: calls named helper}. */
    private static Map<String, int[]> count(
            byte[] classBytes, String owner, String field, String helper) {
        Map<String, int[]> counts = new HashMap<>();
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
                                int[] c =
                                        counts.computeIfAbsent(name + descriptor, k -> new int[3]);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String fowner, String fname, String fdesc) {
                                        if (owner.equals(fowner) && field.equals(fname)) {
                                            if (opcode == Opcodes.GETFIELD) {
                                                c[0]++;
                                            } else if (opcode == Opcodes.PUTFIELD) {
                                                c[1]++;
                                            }
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String mowner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if (mowner.startsWith("io/pzstorm/storm/")
                                                && helper.equals(mname)) {
                                            c[2]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static final class Shape {
        final List<String> interfaces = new ArrayList<>();
        final List<String> fields = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
    }

    private static Shape shape(byte[] classBytes) {
        Shape shape = new Shape();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    String signature,
                                    String superName,
                                    String[] interfaces) {
                                shape.interfaces.addAll(Arrays.asList(interfaces));
                            }

                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                shape.fields.add(name + ":" + descriptor);
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                shape.methods.add(name + descriptor);
                                return null;
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return shape;
    }

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream is =
                RegistryIndexedMapPatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
