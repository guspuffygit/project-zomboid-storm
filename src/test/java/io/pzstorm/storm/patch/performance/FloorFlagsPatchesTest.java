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

/** Each of the three predicates gains exactly one {@code StormFloorFlags} call; nothing else. */
class FloorFlagsPatchesTest implements UnitTest {

    private static final String HELPER = "io/pzstorm/storm/iso/StormFloorFlags";

    @Test
    void gridSquarePredicatesRouteThroughHelper() throws Exception {
        byte[] raw = readClass("zombie/iso/IsoGridSquare");
        byte[] transformed = new IsoGridSquareFloorFlagsPatch().transform(raw);
        assertNotNull(transformed);
        Map<String, Integer> before = helperCalls(raw);
        Map<String, Integer> after = helperCalls(transformed);
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            assertEquals(0, e.getValue());
            String key = e.getKey();
            int expected =
                    key.equals("hasNaturalFloor()Z")
                                    || key.equals("hasSand()Z")
                                    || key.equals("hasDirt()Z")
                            ? 1
                            : 0;
            assertEquals(expected, after.get(key), key);
        }
        assertTrue(before.containsKey("hasSand()Z"));
    }

    @Test
    void spriteGainsFlagsSlot() throws Exception {
        byte[] raw = readClass("zombie/iso/sprite/IsoSprite");
        byte[] transformed = new IsoSpriteFloorFlagsPatch().transform(raw);
        assertNotNull(transformed);
        Shape shape = shape(transformed);
        assertEquals(shape(raw).fields.size() + 1, shape.fields.size());
        assertTrue(shape.fields.contains("stormFloorFlags:Ljava/lang/Object;"));
        assertTrue(
                shape.interfaces.contains("io/pzstorm/storm/entity/StormSpriteFloorFlagsHolder"));
        assertTrue(shape.methods.contains("getStormFloorFlags()Ljava/lang/Object;"));
        assertTrue(shape.methods.contains("setStormFloorFlags(Ljava/lang/Object;)V"));
    }

    private static Map<String, Integer> helperCalls(byte[] classBytes) {
        Map<String, Integer> counts = new HashMap<>();
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
                                String key = name + descriptor;
                                counts.putIfAbsent(key, 0);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if (HELPER.equals(owner)) {
                                            counts.merge(key, 1, Integer::sum);
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
                FloorFlagsPatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
