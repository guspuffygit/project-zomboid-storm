package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * {@code MemberSubstitution.relaxed()} silently no-ops on a matcher miss, so every substituted call
 * is counted per method: the named method loses its {@code getVisual}/{@code getItemVisuals} calls
 * and gains the same number of helper calls; every other method is untouched.
 */
class ClothingVisualsPatchesTest implements UnitTest {

    private static final String HELPER = "io/pzstorm/storm/inventory/StormClothingVisuals";

    @Test
    void thermoregulatorUpdateClothingUsesMemo() throws Exception {
        assertSubstituted(
                "zombie/characters/BodyDamage/Thermoregulator",
                new ThermoregulatorClothingVisualsPatch(),
                "updateClothing",
                0,
                1);
    }

    @Test
    void thermalNodeInsulationUsesCachedVisual() throws Exception {
        assertSubstituted(
                "zombie/characters/BodyDamage/Thermoregulator$ThermalNode",
                new ThermalNodeInsulationVisualPatch(),
                "calculateInsulation",
                1,
                0);
    }

    @Test
    void clothingWetnessUpdateWetnessUsesBoth() throws Exception {
        assertSubstituted(
                "zombie/characters/ClothingWetness",
                new ClothingWetnessVisualsPatch(),
                "updateWetness",
                2,
                1);
    }

    @Test
    void inventoryItemGainsVisualAccessor() throws Exception {
        byte[] transformed =
                new InventoryItemVisualFieldPatch()
                        .transform(readClass("zombie/inventory/InventoryItem"));
        assertNotNull(transformed);
        Shape shape = shape(transformed);
        assertTrue(shape.interfaces.contains("io/pzstorm/storm/entity/StormVisualFieldHolder"));
        assertTrue(shape.methods.contains("getStormVisualField()Ljava/lang/Object;"));
    }

    @Test
    void characterGainsWornVisualsSlot() throws Exception {
        byte[] raw = readClass("zombie/characters/IsoGameCharacter");
        byte[] transformed = new IsoGameCharacterWornVisualsMemoPatch().transform(raw);
        assertNotNull(transformed);
        Shape shape = shape(transformed);
        assertEquals(shape(raw).fields.size() + 1, shape.fields.size());
        assertTrue(shape.fields.contains("stormWornVisuals:Ljava/lang/Object;"));
        assertTrue(shape.interfaces.contains("io/pzstorm/storm/entity/StormWornVisualsHolder"));
        assertTrue(shape.methods.contains("getStormWornVisuals()Ljava/lang/Object;"));
        assertTrue(shape.methods.contains("setStormWornVisuals(Ljava/lang/Object;)V"));
    }

    /** {0: getVisual calls, 1: getItemVisuals calls, 2: helper cachedVisual, 3: helper fill}. */
    private static void assertSubstituted(
            String className,
            ClothingVisualsSubstitutionPatch patch,
            String method,
            int expectedGetVisual,
            int expectedGetItemVisuals)
            throws Exception {
        byte[] raw = readClass(className);
        byte[] transformed = patch.transform(raw);
        assertNotNull(transformed);
        Map<String, int[]> before = count(raw);
        Map<String, int[]> after = count(transformed);
        String key = null;
        for (String k : before.keySet()) {
            if (k.startsWith(method + "(")) {
                key = k;
            }
        }
        assertNotNull(key, method + " must exist in vanilla " + className);
        int[] b = before.get(key);
        int[] a = after.get(key);
        assertEquals(expectedGetVisual, b[0], "vanilla getVisual calls in " + method);
        assertEquals(expectedGetItemVisuals, b[1], "vanilla getItemVisuals calls in " + method);
        assertEquals(0, b[2] + b[3]);
        assertEquals(0, a[0], "getVisual calls must be substituted away");
        assertEquals(0, a[1], "getItemVisuals calls must be substituted away");
        assertEquals(expectedGetVisual, a[2], "cachedVisual calls");
        assertEquals(expectedGetItemVisuals, a[3], "fillItemVisuals calls");
        for (Map.Entry<String, int[]> e : before.entrySet()) {
            if (!e.getKey().equals(key)) {
                assertEquals(
                        Arrays.toString(e.getValue()),
                        Arrays.toString(after.get(e.getKey())),
                        e.getKey() + " must be untouched");
            }
        }
    }

    private static Map<String, int[]> count(byte[] classBytes) {
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
                                        counts.computeIfAbsent(name + descriptor, k -> new int[4]);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if (HELPER.equals(owner)) {
                                            if ("cachedVisual".equals(mname)) {
                                                c[2]++;
                                            } else if ("fillItemVisuals".equals(mname)) {
                                                c[3]++;
                                            }
                                        } else if ("getVisual".equals(mname)
                                                && "()Lzombie/core/skinnedmodel/visual/ItemVisual;"
                                                        .equals(mdesc)) {
                                            c[0]++;
                                        } else if ("getItemVisuals".equals(mname)) {
                                            c[1]++;
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
                            public net.bytebuddy.jar.asm.FieldVisitor visitField(
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
                ClothingVisualsPatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
