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
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Every GETFIELD of {@code Item.itemTags} must become a {@code StormItemTags.tagsOf} call (the
 * substitution is {@code relaxed()}, so a matcher miss would be silent); the constructor PUTFIELD
 * stays; {@code ItemTag} gains its index field and one allocator call per constructor.
 */
class ItemTagPatchesTest implements UnitTest {

    private static final String ITEM = "zombie/scripting/objects/Item";
    private static final String ITEM_TAG = "zombie/scripting/objects/ItemTag";

    @Test
    void itemFieldReadsAreRedirected() throws Exception {
        byte[] raw = readClass(ITEM);
        byte[] transformed = new ItemTagMaskPatch().transform(raw);
        assertNotNull(transformed);
        Map<String, int[]> before = count(raw, ITEM, "itemTags", "tagsOf");
        Map<String, int[]> after = count(transformed, ITEM, "itemTags", "tagsOf");
        int getsBefore = 0;
        for (Map.Entry<String, int[]> e : before.entrySet()) {
            int[] b = e.getValue();
            int[] a = after.get(e.getKey());
            getsBefore += b[0];
            assertEquals(0, a[0], e.getKey() + " must not read itemTags directly");
            assertEquals(b[0], a[2], e.getKey() + " helper calls must equal vanilla reads");
            assertEquals(b[1], a[1], e.getKey() + " itemTags writes must be untouched");
        }
        assertTrue(getsBefore >= 3, "vanilla reads itemTags in hasTag, getItemTags and the parser");
        assertEquals(1, before.get("<init>()V")[1], "vanilla ctor initialises itemTags");
        Shape shape = shape(transformed);
        assertTrue(shape.fields.contains("stormItemTags:Ljava/util/Set;"));
        assertTrue(shape.interfaces.contains("io/pzstorm/storm/entity/StormItemTagsHolder"));
        assertTrue(shape.methods.contains("getStormItemTags()Ljava/util/Set;"));
        assertTrue(shape.methods.contains("setStormItemTags(Ljava/util/Set;)V"));
    }

    @Test
    void itemTagGainsIndex() throws Exception {
        byte[] raw = readClass(ITEM_TAG);
        byte[] transformed = new ItemTagIndexPatch().transform(raw);
        assertNotNull(transformed);
        Shape shape = shape(transformed);
        assertEquals(shape(raw).fields.size() + 1, shape.fields.size());
        assertTrue(shape.fields.contains("stormIndex:I"));
        assertTrue(shape.interfaces.contains("io/pzstorm/storm/entity/StormItemTagIndexHolder"));
        assertTrue(shape.methods.contains("getStormIndex()I"));
        Map<String, int[]> after = count(transformed, ITEM_TAG, "stormIndex", "next");
        int ctors = 0;
        for (Map.Entry<String, int[]> e : after.entrySet()) {
            if (e.getKey().startsWith("<init>")) {
                ctors++;
                assertEquals(1, e.getValue()[2], e.getKey() + " must allocate one index");
                assertEquals(1, e.getValue()[1], e.getKey() + " must write stormIndex once");
            } else {
                assertEquals(0, e.getValue()[2], e.getKey() + " must not allocate");
            }
        }
        assertEquals(1, ctors);
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
                ItemTagPatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
