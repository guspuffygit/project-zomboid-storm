package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Weave-verifies the typed action-condition resolve: the nested lookup class gains the accessor
 * interface, and only {@code resolveValue} of the condition class gains the {@code
 * StormVariableLookup} instanceof (vanilla {@code getValueString} path kept as the fallback).
 */
class CharacterVariablePatchesTest implements UnitTest {

    private static final String LOOKUP_IFACE = "io/pzstorm/storm/entity/StormVariableLookup";
    private static final String CONDITION =
            "zombie/characters/action/conditions/CharacterVariableCondition";

    @Test
    void lookupClassGainsAccessorInterface() throws Exception {
        byte[] raw = readClass(CONDITION + "$CharacterVariableLookup");
        byte[] transformed = new CharacterVariableLookupAccessorPatch().transform(raw);
        assertNotNull(transformed);
        List<String> before = interfaces(raw);
        List<String> after = interfaces(transformed);
        assertFalse(before.contains(LOOKUP_IFACE));
        assertTrue(after.contains(LOOKUP_IFACE));
        assertTrue(
                methods(transformed).contains("getStormVariableReference()Ljava/lang/Object;"),
                "accessor must be generated");
    }

    @Test
    void resolveValueGainsTypedPathAndKeepsFallback() throws Exception {
        byte[] raw = readClass(CONDITION);
        byte[] transformed = new CharacterVariableResolveTypedPatch().transform(raw);
        assertNotNull(transformed);
        Map<String, int[]> before = countPerMethod(raw);
        Map<String, int[]> after = countPerMethod(transformed);
        String resolve = null;
        for (String key : before.keySet()) {
            if (key.startsWith("resolveValue(")) {
                resolve = key;
            }
        }
        assertNotNull(resolve);
        assertEquals(0, before.get(resolve)[0]);
        assertEquals(1, after.get(resolve)[0], "one StormVariableLookup instanceof in resolve");
        assertEquals(1, before.get(resolve)[1]);
        assertEquals(
                2,
                after.get(resolve)[1],
                "advice string path + vanilla fallback both read getValueString");
        assertTrue(after.get(resolve)[2] >= 1, "typed path reads getValueBool");
        for (Map.Entry<String, int[]> entry : before.entrySet()) {
            if (!entry.getKey().equals(resolve)) {
                assertArrayEquals(entry.getValue(), after.get(entry.getKey()), entry.getKey());
            }
        }
    }

    private static void assertArrayEquals(int[] expected, int[] actual, String message) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual), message);
    }

    /** {0: lookup instanceofs, 1: getValueString calls, 2: getValueBool calls, 3: all calls}. */
    private static Map<String, int[]> countPerMethod(byte[] classBytes) {
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
                                    public void visitTypeInsn(int opcode, String type) {
                                        if (opcode == Opcodes.INSTANCEOF
                                                && LOOKUP_IFACE.equals(type)) {
                                            c[0]++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if ("getValueString".equals(mname)) {
                                            c[1]++;
                                        }
                                        if ("getValueBool".equals(mname)) {
                                            c[2]++;
                                        }
                                        c[3]++;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static List<String> interfaces(byte[] classBytes) {
        List<String> out = new ArrayList<>();
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
                                out.addAll(Arrays.asList(interfaces));
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return out;
    }

    private static List<String> methods(byte[] classBytes) {
        List<String> out = new ArrayList<>();
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
                                out.add(name + descriptor);
                                return null;
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return out;
    }

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream is =
                CharacterVariablePatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
