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
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Weave-verifies the two halves of the ECS component-lookup memo: {@link EcsEntityTryGetMemoPatch}
 * confines the memo advice to {@code ECSEntity.tryGetECSComponent(Class)} while keeping the vanilla
 * map lookup as the miss path, and {@link IsoGameCharacterEcsMemoPatch} installs the {@code
 * stormEcsMemo} storage field (public volatile {@code Object[]}) plus the {@code
 * StormEcsMemoHolder} accessor pair on {@code IsoGameCharacter}.
 */
class EcsMemoPatchesTest implements UnitTest {

    private static final String HOLDER = "io/pzstorm/storm/entity/StormEcsMemoHolder";
    private static final String ECS_COMPONENT = "zombie/characters/ecs/ECSComponent";
    private static final String MEMO_FIELD = "stormEcsMemo";
    private static final String MEMO_DESC = "[Ljava/lang/Object;";

    @Test
    void memoAdviceIsConfinedToTryGetAndKeepsVanillaLookup() throws Exception {
        byte[] raw = readClass("zombie/characters/ecs/ECSEntity");
        byte[] transformed = new EcsEntityTryGetMemoPatch().transform(raw);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Map<String, Counts> before = countPerMethod(raw);
        Map<String, Counts> after = countPerMethod(transformed);

        String tryGet = findSingleMethod(before.keySet(), "tryGetECSComponent(");
        Counts tryGetBefore = before.get(tryGet);
        Counts tryGetAfter = after.get(tryGet);
        assertEquals(0, tryGetBefore.holderInstanceofs, "vanilla must not know the holder");
        assertEquals(
                2,
                tryGetAfter.holderInstanceofs,
                "enter and exit advice each guard on instanceof StormEcsMemoHolder");
        assertEquals(
                tryGetBefore.identityHashCodes,
                tryGetAfter.identityHashCodes,
                "the memo is scanned by class identity, never slot-addressed by identityHashCode"
                        + " (per-boot slot collisions silently defeated the memo, scan #12)");
        assertTrue(
                tryGetAfter.getEcsClassCalls >= 1
                        && tryGetAfter.getEcsClassCalls == tryGetBefore.getEcsClassCalls,
                "the vanilla ECSComponent.getECSClass map-lookup path must survive as the miss"
                        + " path");

        String setComponent = findSingleMethod(before.keySet(), "setECSComponent(");
        Counts setBefore = before.get(setComponent);
        Counts setAfter = after.get(setComponent);
        assertEquals(0, setBefore.holderInstanceofs);
        assertEquals(
                1,
                setAfter.holderInstanceofs,
                "setECSComponent exit advice drops the memo behind one holder instanceof");
        assertEquals(setBefore.identityHashCodes, setAfter.identityHashCodes);
        assertEquals(setBefore.getEcsClassCalls, setAfter.getEcsClassCalls);

        for (Map.Entry<String, Counts> entry : before.entrySet()) {
            if (tryGet.equals(entry.getKey()) || setComponent.equals(entry.getKey())) {
                continue;
            }
            assertEquals(
                    entry.getValue(),
                    after.get(entry.getKey()),
                    "method " + entry.getKey() + " must be untouched by the patch");
        }
    }

    @Test
    void stateMachinePatchAddsFieldAndAdvisesOnlyTheGetter() throws Exception {
        byte[] raw = readClass("zombie/characters/IsoGameCharacter");
        byte[] transformed = new IsoGameCharacterStateMachineMemoPatch().transform(raw);
        assertNotNull(transformed);

        Shape before = readShape(raw);
        Shape after = readShape(transformed);
        String field = "stormStateMachine:Ljava/lang/Object;";
        assertFalse(before.fields.containsKey(field));
        Integer access = after.fields.get(field);
        assertNotNull(access, "patched class must declare stormStateMachine Object");
        assertTrue((access & Opcodes.ACC_PUBLIC) != 0);
        assertEquals(before.fields.size() + 1, after.fields.size());
        assertTrue(after.methods.containsAll(before.methods));

        Map<String, Integer> ownerBefore = countOwnerCalls(raw);
        Map<String, Integer> ownerAfter = countOwnerCalls(transformed);
        String getter = "getStateMachineComponent()";
        String getterKey = null;
        for (String key : ownerBefore.keySet()) {
            if (key.startsWith(getter)) {
                getterKey = key;
            }
        }
        assertNotNull(getterKey, "IsoGameCharacter must declare getStateMachineComponent()");
        assertEquals(0, ownerBefore.get(getterKey));
        assertEquals(
                1,
                ownerAfter.get(getterKey),
                "enter advice validates the cached component by getECSOwnerEntity once");
        for (Map.Entry<String, Integer> entry : ownerBefore.entrySet()) {
            if (getterKey.equals(entry.getKey())) {
                continue;
            }
            assertEquals(
                    entry.getValue(),
                    ownerAfter.get(entry.getKey()),
                    "method " + entry.getKey() + " must be untouched by the patch");
        }
    }

    private static Map<String, Integer> countOwnerCalls(byte[] classBytes) {
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
                                        if (ECS_COMPONENT.equals(owner)
                                                && "getECSOwnerEntity".equals(mname)) {
                                            counts.merge(key, 1, Integer::sum);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    @Test
    void characterPatchInstallsFieldInterfaceAndAccessors() throws Exception {
        byte[] raw = readClass("zombie/characters/IsoGameCharacter");
        byte[] transformed = new IsoGameCharacterEcsMemoPatch().transform(raw);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Shape before = readShape(raw);
        Shape after = readShape(transformed);

        assertFalse(before.interfaces.contains(HOLDER));
        assertTrue(
                after.interfaces.contains(HOLDER),
                "patched IsoGameCharacter must implement StormEcsMemoHolder");

        assertFalse(before.fields.containsKey(MEMO_FIELD + ":" + MEMO_DESC));
        Integer fieldAccess = after.fields.get(MEMO_FIELD + ":" + MEMO_DESC);
        assertNotNull(fieldAccess, "patched class must declare stormEcsMemo [Ljava/lang/Object;");
        assertTrue((fieldAccess & Opcodes.ACC_PUBLIC) != 0, "stormEcsMemo must be public");
        assertTrue((fieldAccess & Opcodes.ACC_VOLATILE) != 0, "stormEcsMemo must be volatile");
        assertEquals(
                before.fields.size() + 1, after.fields.size(), "exactly one field must be added");

        assertTrue(
                after.methods.contains("getStormEcsMemo()" + MEMO_DESC),
                "getter must be generated");
        assertTrue(
                after.methods.contains("setStormEcsMemo(" + MEMO_DESC + ")V"),
                "setter must be generated");
        assertTrue(
                after.methods.containsAll(before.methods),
                "no vanilla method may disappear from IsoGameCharacter");
    }

    private static String findSingleMethod(Iterable<String> keys, String prefix) {
        String found = null;
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                assertEquals(null, found, "expected exactly one method matching " + prefix);
                found = key;
            }
        }
        assertNotNull(found, "ECSEntity must declare " + prefix + "...)");
        return found;
    }

    private static Map<String, Counts> countPerMethod(byte[] classBytes) {
        Map<String, Counts> counts = new HashMap<>();
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
                                Counts method =
                                        counts.computeIfAbsent(
                                                name + descriptor, k -> new Counts());
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitTypeInsn(int opcode, String type) {
                                        if (opcode == Opcodes.INSTANCEOF && HOLDER.equals(type)) {
                                            method.holderInstanceofs++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && "java/lang/System".equals(owner)
                                                && "identityHashCode".equals(mname)) {
                                            method.identityHashCodes++;
                                        }
                                        if (ECS_COMPONENT.equals(owner)
                                                && "getECSClass".equals(mname)) {
                                            method.getEcsClassCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static Shape readShape(byte[] classBytes) {
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
                                shape.fields.put(name + ":" + descriptor, access);
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

    private static final class Shape {
        final List<String> interfaces = new ArrayList<>();
        final Map<String, Integer> fields = new HashMap<>();
        final List<String> methods = new ArrayList<>();
    }

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream is =
                EcsMemoPatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static final class Counts {
        int holderInstanceofs;
        int identityHashCodes;
        int getEcsClassCalls;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Counts)) {
                return false;
            }
            Counts other = (Counts) o;
            return holderInstanceofs == other.holderInstanceofs
                    && identityHashCodes == other.identityHashCodes
                    && getEcsClassCalls == other.getEcsClassCalls;
        }

        @Override
        public int hashCode() {
            return holderInstanceofs * 31 * 31 + identityHashCodes * 31 + getEcsClassCalls;
        }

        @Override
        public String toString() {
            return "holderInstanceofs="
                    + holderInstanceofs
                    + " identityHashCodes="
                    + identityHashCodes
                    + " getEcsClassCalls="
                    + getEcsClassCalls;
        }
    }
}
