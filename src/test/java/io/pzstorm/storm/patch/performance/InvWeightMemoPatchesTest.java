package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Weave-verifies the inventory-weight memo: {@link IsoGameCharacterInvWeightMemoPatch} installs the
 * packed {@code stormInvWeight} field, the {@code StormInvWeightHolder} accessor pair, the memo
 * advice on {@code getInventoryWeight()} (vanilla walk kept as the miss path) and epoch bumps on
 * the hand setters; {@link ItemContainerMutationEpochPatch} and {@link WornItemsMutationEpochPatch}
 * bump the epoch on exactly the allowlisted mutators and touch nothing else.
 */
class InvWeightMemoPatchesTest implements UnitTest {

    private static final String HOLDER = "io/pzstorm/storm/entity/StormInvWeightHolder";
    private static final String EPOCH_OWNER = "io/pzstorm/storm/inventory/StormInventoryWeight";
    private static final String FIELD = "stormInvWeight";
    private static final String FIELD_DESC = "J";

    private static final Set<String> ITEM_CONTAINER_MUTATORS =
            new HashSet<>(
                    Arrays.asList(
                            "addItem",
                            "addItems",
                            "AddItem",
                            "AddItems",
                            "AddItemBlind",
                            "DoAddItem",
                            "DoAddItemBlind",
                            "Remove",
                            "RemoveAll",
                            "RemoveOneOf",
                            "DoRemoveItem",
                            "removeItemOnServer",
                            "removeAllItems",
                            "removeItemWithID",
                            "removeItemWithIDRecurse",
                            "emptyIt",
                            "clear",
                            "setItems"));

    private static final Set<String> WORN_ITEMS_MUTATORS =
            new HashSet<>(Arrays.asList("setItem", "remove", "clear", "setFromItemVisuals"));

    @Test
    void characterPatchInstallsFieldInterfaceMemoAndHandBumps() throws Exception {
        byte[] raw = readClass("zombie/characters/IsoGameCharacter");
        byte[] transformed = new IsoGameCharacterInvWeightMemoPatch().transform(raw);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Shape before = readShape(raw);
        Shape after = readShape(transformed);

        assertFalse(before.interfaces.contains(HOLDER));
        assertTrue(
                after.interfaces.contains(HOLDER),
                "patched IsoGameCharacter must implement StormInvWeightHolder");

        assertFalse(before.fields.containsKey(FIELD + ":" + FIELD_DESC));
        Integer fieldAccess = after.fields.get(FIELD + ":" + FIELD_DESC);
        assertNotNull(fieldAccess, "patched class must declare stormInvWeight J");
        assertTrue((fieldAccess & Opcodes.ACC_PUBLIC) != 0, "stormInvWeight must be public");
        assertTrue((fieldAccess & Opcodes.ACC_VOLATILE) != 0, "stormInvWeight must be volatile");
        assertEquals(
                before.fields.size() + 1, after.fields.size(), "exactly one field must be added");

        assertTrue(after.methods.contains("getStormInvWeight()J"), "getter must be generated");
        assertTrue(after.methods.contains("setStormInvWeight(J)V"), "setter must be generated");
        assertTrue(
                after.methods.containsAll(before.methods),
                "no vanilla method may disappear from IsoGameCharacter");

        Map<String, Counts> countsBefore = countPerMethod(raw);
        Map<String, Counts> countsAfter = countPerMethod(transformed);

        Counts weighBefore = countsBefore.get("getInventoryWeight()F");
        Counts weighAfter = countsAfter.get("getInventoryWeight()F");
        assertNotNull(weighBefore, "vanilla IsoGameCharacter must declare getInventoryWeight()F");
        assertEquals(0, weighBefore.holderInstanceofs, "vanilla must not know the holder");
        assertEquals(
                2,
                weighAfter.holderInstanceofs,
                "enter and exit advice each guard on instanceof StormInvWeightHolder");
        assertTrue(
                weighAfter.epochReads >= 2,
                "enter (validate) and exit (store) each read StormInventoryWeight.epoch");
        assertTrue(
                weighAfter.unequippedWeightCalls >= 1
                        && weighAfter.unequippedWeightCalls == weighBefore.unequippedWeightCalls,
                "the vanilla item walk must survive as the miss path");

        for (String hand : Arrays.asList("setPrimaryHandItem", "setSecondaryHandItem")) {
            int bumps = 0;
            for (Map.Entry<String, Counts> entry : countsAfter.entrySet()) {
                if (entry.getKey().startsWith(hand + "(")) {
                    bumps += entry.getValue().bumpCalls;
                }
            }
            assertTrue(bumps >= 1, hand + " must bump the epoch");
        }

        for (Map.Entry<String, Counts> entry : countsBefore.entrySet()) {
            String key = entry.getKey();
            if (key.equals("getInventoryWeight()F")
                    || key.startsWith("setPrimaryHandItem(")
                    || key.startsWith("setSecondaryHandItem(")) {
                continue;
            }
            assertEquals(
                    entry.getValue(),
                    countsAfter.get(key),
                    "method " + key + " must be untouched by the patch");
        }
    }

    @Test
    void itemContainerPatchBumpsExactlyTheMutators() throws Exception {
        assertBumpsExactly(
                "zombie/inventory/ItemContainer",
                new ItemContainerMutationEpochPatch()
                        .transform(readClass("zombie/inventory/ItemContainer")),
                readClass("zombie/inventory/ItemContainer"),
                ITEM_CONTAINER_MUTATORS);
    }

    @Test
    void wornItemsPatchBumpsExactlyTheMutators() throws Exception {
        assertBumpsExactly(
                "zombie/characters/WornItems/WornItems",
                new WornItemsMutationEpochPatch()
                        .transform(readClass("zombie/characters/WornItems/WornItems")),
                readClass("zombie/characters/WornItems/WornItems"),
                WORN_ITEMS_MUTATORS);
    }

    private static void assertBumpsExactly(
            String className, byte[] transformed, byte[] raw, Set<String> mutators) {
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);
        Map<String, Counts> before = countPerMethod(raw);
        Map<String, Counts> after = countPerMethod(transformed);

        Set<String> mutatorsSeen = new HashSet<>();
        for (Map.Entry<String, Counts> entry : after.entrySet()) {
            String name = entry.getKey().substring(0, entry.getKey().indexOf('('));
            if (mutators.contains(name)) {
                mutatorsSeen.add(name);
                assertTrue(
                        entry.getValue().bumpCalls >= 1,
                        className + "." + entry.getKey() + " must bump the epoch");
            } else {
                assertEquals(
                        before.get(entry.getKey()),
                        entry.getValue(),
                        className + "." + entry.getKey() + " must be untouched");
            }
        }
        assertEquals(
                mutators,
                mutatorsSeen,
                "every allowlisted mutator must exist in "
                        + className
                        + " — a missing name means"
                        + " the vanilla method was renamed and the bump silently vanished");
    }

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream is =
                InvWeightMemoPatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
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
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fname, String fdesc) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && EPOCH_OWNER.equals(owner)
                                                && "epoch".equals(fname)) {
                                            method.epochReads++;
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
                                                && EPOCH_OWNER.equals(owner)
                                                && "bump".equals(mname)) {
                                            method.bumpCalls++;
                                        }
                                        if ("getUnequippedWeight".equals(mname)) {
                                            method.unequippedWeightCalls++;
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
        final List<String> interfaces = new java.util.ArrayList<>();
        final Map<String, Integer> fields = new HashMap<>();
        final Set<String> methods = new HashSet<>();
    }

    private static final class Counts {
        int holderInstanceofs;
        int epochReads;
        int bumpCalls;
        int unequippedWeightCalls;

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Counts)) {
                return false;
            }
            Counts other = (Counts) o;
            return holderInstanceofs == other.holderInstanceofs
                    && epochReads == other.epochReads
                    && bumpCalls == other.bumpCalls
                    && unequippedWeightCalls == other.unequippedWeightCalls;
        }

        @Override
        public int hashCode() {
            return ((holderInstanceofs * 31 + epochReads) * 31 + bumpCalls) * 31
                    + unequippedWeightCalls;
        }

        @Override
        public String toString() {
            return "Counts{holderInstanceofs="
                    + holderInstanceofs
                    + ", epochReads="
                    + epochReads
                    + ", bumpCalls="
                    + bumpCalls
                    + ", unequippedWeightCalls="
                    + unequippedWeightCalls
                    + '}';
        }
    }
}
