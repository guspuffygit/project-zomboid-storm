package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.core.StormClassTransformer;
import java.io.InputStream;
import java.util.ArrayList;
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
 * Weave-verifies the per-character inventory-weight memo: {@link
 * IsoGameCharacterInvWeightMemoPatch} installs the packed weight + epoch fields, the {@code
 * StormInvWeightHolder} accessors, the memo advice on {@code getInventoryWeight()} (vanilla walk
 * kept as the miss path) and the character's own epoch bumps; {@link ItemContainerTrackedListPatch}
 * swaps in the tracked list wherever {@code items} is assigned; and every epoch-source patch bumps
 * exactly its allowlisted methods (every overload) while leaving every other method byte-for-byte
 * equivalent in what it calls.
 */
class InvWeightMemoPatchesTest implements UnitTest {

    private static final String HOLDER = "io/pzstorm/storm/entity/StormInvWeightHolder";
    private static final String WORN_HOLDER = "io/pzstorm/storm/entity/StormWornItemsOwnerHolder";
    private static final String EPOCH_OWNER = "io/pzstorm/storm/inventory/StormInventoryWeight";
    private static final String TRACKED_LIST = "io/pzstorm/storm/inventory/StormTrackedItemList";

    @Test
    void characterPatchInstallsFieldsInterfaceMemoAndOwnBumps() throws Exception {
        byte[] raw = readClass("zombie/characters/IsoGameCharacter");
        byte[] transformed = new IsoGameCharacterInvWeightMemoPatch().transform(raw);
        assertNotNull(transformed);

        Shape before = readShape(raw);
        Shape after = readShape(transformed);
        assertFalse(before.interfaces.contains(HOLDER));
        assertTrue(after.interfaces.contains(HOLDER));
        assertVolatilePublicField(before, after, "stormInvWeight:J");
        assertVolatilePublicField(before, after, "stormInvEpoch:I");
        assertEquals(before.fields.size() + 2, after.fields.size());
        assertTrue(after.methods.contains("getStormInvWeight()J"));
        assertTrue(after.methods.contains("setStormInvWeight(J)V"));
        assertTrue(after.methods.contains("getStormInvEpoch()I"));
        assertTrue(after.methods.contains("setStormInvEpoch(I)V"));
        assertTrue(after.methods.containsAll(before.methods));

        Map<String, Counts> countsBefore = countPerMethod(raw);
        Map<String, Counts> countsAfter = countPerMethod(transformed);
        Counts weighBefore = countsBefore.get("getInventoryWeight()F");
        Counts weighAfter = countsAfter.get("getInventoryWeight()F");
        assertNotNull(weighBefore);
        assertEquals(0, weighBefore.holderInstanceofs);
        assertEquals(
                2,
                weighAfter.holderInstanceofs,
                "enter and exit advice each guard on instanceof StormInvWeightHolder");
        assertTrue(weighAfter.wornHolderInstanceofs >= 1, "miss path stamps the worn-items owner");
        assertTrue(
                weighAfter.unequippedWeightCalls >= 1
                        && weighAfter.unequippedWeightCalls == weighBefore.unequippedWeightCalls,
                "the vanilla item walk must survive as the miss path");
        assertEquals(0, weighAfter.bumps.size(), "the weigh itself must not bump");

        assertBumpsExactly(
                "IsoGameCharacter",
                countsBefore,
                countsAfter,
                "bumpCharacter",
                setOf(
                        "setPrimaryHandItem",
                        "setSecondaryHandItem",
                        "setInventory",
                        "onWornItemsChanged"),
                setOf("getInventoryWeight"));
    }

    @Test
    void itemContainerPatchInstallsTrackedListWhereverItemsIsAssigned() throws Exception {
        byte[] raw = readClass("zombie/inventory/ItemContainer");
        byte[] transformed = new ItemContainerTrackedListPatch().transform(raw);
        Map<String, Counts> before = countPerMethod(raw);
        Map<String, Counts> after = countPerMethod(transformed);
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Counts> entry : after.entrySet()) {
            String key = entry.getKey();
            String name = key.substring(0, key.indexOf('('));
            if (name.equals("<init>") || name.equals("setItems") || name.equals("emptyIt")) {
                seen.add(name);
                assertEquals(0, before.get(key).trackedListNews, key);
                assertEquals(1, entry.getValue().trackedListNews, key + " must wrap items");
                assertEquals(1, entry.getValue().bumps.getOrDefault("bumpContainer", 0), key);
            } else {
                assertEquals(before.get(key), entry.getValue(), "ItemContainer." + key);
            }
        }
        assertEquals(setOf("<init>", "setItems", "emptyIt"), seen);
    }

    @Test
    void wornItemsPatchInstallsOwnerAndBumpsExactlyTheMutators() throws Exception {
        byte[] raw = readClass("zombie/characters/WornItems/WornItems");
        byte[] transformed = new WornItemsMutationEpochPatch().transform(raw);
        Shape before = readShape(raw);
        Shape after = readShape(transformed);
        assertFalse(before.interfaces.contains(WORN_HOLDER));
        assertTrue(after.interfaces.contains(WORN_HOLDER));
        assertVolatilePublicField(before, after, "stormOwner:Ljava/lang/Object;");
        assertTrue(after.methods.contains("getStormOwner()Ljava/lang/Object;"));
        assertTrue(after.methods.contains("setStormOwner(Ljava/lang/Object;)V"));
        assertBumpsExactly(
                "WornItems",
                countPerMethod(raw),
                countPerMethod(transformed),
                "bumpWornItems",
                setOf("setItem", "remove", "clear", "setFromItemVisuals", "copyFrom", "load"),
                setOf());
    }

    @Test
    void inventoryItemPatchBumpsExactlyTheWeightInputs() throws Exception {
        assertNamedPatch(
                "zombie/inventory/InventoryItem",
                new InventoryItemInvEpochPatch(),
                "bumpItem",
                setOf(
                        "setActualWeight",
                        "setCurrentAmmoCount",
                        "setAttachedSlot",
                        "setName",
                        "setCustomWeight",
                        "addExtraItem",
                        "load"));
    }

    @Test
    void foodPatchBumpsExactlyTheWeightInputs() throws Exception {
        assertNamedPatch(
                "zombie/inventory/types/Food",
                new FoodInvEpochPatch(),
                "bumpItem",
                setOf("setHungChange", "setThirstChange", "setBaseHunger"));
    }

    @Test
    void handWeaponPatchBumpsExactlyTheWeightInputs() throws Exception {
        assertNamedPatch(
                "zombie/inventory/types/HandWeapon",
                new HandWeaponInvEpochPatch(),
                "bumpItem",
                setOf("setWeaponPart", "clearWeaponPart", "clearAllWeaponParts"));
    }

    @Test
    void fluidContainerPatchBumpsExactlyTheAmountMutators() throws Exception {
        assertNamedPatch(
                "zombie/entity/components/fluids/FluidContainer",
                new FluidContainerInvEpochPatch(),
                "bumpFluidContainer",
                setOf(
                        "setCapacity",
                        "adjustAmount",
                        "adjustSpecificFluidAmount",
                        "addFluid",
                        "removeFluid",
                        "copyFluidsFrom",
                        "Empty",
                        "removeFluidInstanceIfEmpty",
                        "load"));
    }

    private static void assertNamedPatch(
            String internalName, StormClassTransformer patch, String bump, Set<String> mutators)
            throws Exception {
        byte[] raw = readClass(internalName);
        byte[] transformed = patch.transform(raw);
        assertNotNull(transformed);
        Shape before = readShape(raw);
        Shape after = readShape(transformed);
        assertEquals(before.fields.size(), after.fields.size());
        assertEquals(before.interfaces, after.interfaces);
        assertBumpsExactly(
                internalName,
                countPerMethod(raw),
                countPerMethod(transformed),
                bump,
                mutators,
                setOf());
    }

    private static void assertBumpsExactly(
            String className,
            Map<String, Counts> before,
            Map<String, Counts> after,
            String bump,
            Set<String> mutators,
            Set<String> otherwiseAdvised) {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Counts> entry : after.entrySet()) {
            String key = entry.getKey();
            String name = key.substring(0, key.indexOf('('));
            if (mutators.contains(name)) {
                seen.add(name);
                assertEquals(
                        1,
                        entry.getValue().bumps.getOrDefault(bump, 0),
                        className + "." + key + " must call " + bump + " exactly once");
                assertEquals(1, entry.getValue().bumps.size(), className + "." + key);
            } else if (!otherwiseAdvised.contains(name) && before.containsKey(key)) {
                assertEquals(
                        before.get(key),
                        entry.getValue(),
                        className + "." + key + " must be untouched");
            }
        }
        assertEquals(
                mutators,
                seen,
                "every allowlisted mutator must exist in "
                        + className
                        + " — a missing name means the vanilla method was renamed");
    }

    private static void assertVolatilePublicField(Shape before, Shape after, String field) {
        assertFalse(before.fields.containsKey(field), field + " must not exist in vanilla");
        Integer access = after.fields.get(field);
        assertNotNull(access, "patched class must declare " + field);
        assertTrue((access & Opcodes.ACC_PUBLIC) != 0, field + " must be public");
        assertTrue((access & Opcodes.ACC_VOLATILE) != 0, field + " must be volatile");
    }

    private static Set<String> setOf(String... names) {
        return new HashSet<>(Arrays.asList(names));
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
                                        if (opcode == Opcodes.INSTANCEOF
                                                && WORN_HOLDER.equals(type)) {
                                            method.wornHolderInstanceofs++;
                                        }
                                        if (opcode == Opcodes.NEW && TRACKED_LIST.equals(type)) {
                                            method.trackedListNews++;
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
                                                && EPOCH_OWNER.equals(owner)) {
                                            method.bumps.merge(mname, 1, Integer::sum);
                                        }
                                        if ("getUnequippedWeight".equals(mname)) {
                                            method.unequippedWeightCalls++;
                                        }
                                        method.calls++;
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

    private static final class Counts {
        int holderInstanceofs;
        int wornHolderInstanceofs;
        int trackedListNews;
        int unequippedWeightCalls;
        int calls;
        final Map<String, Integer> bumps = new HashMap<>();

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Counts)) {
                return false;
            }
            Counts other = (Counts) o;
            return holderInstanceofs == other.holderInstanceofs
                    && wornHolderInstanceofs == other.wornHolderInstanceofs
                    && trackedListNews == other.trackedListNews
                    && unequippedWeightCalls == other.unequippedWeightCalls
                    && calls == other.calls
                    && bumps.equals(other.bumps);
        }

        @Override
        public int hashCode() {
            return calls * 31 + bumps.hashCode();
        }

        @Override
        public String toString() {
            return "calls="
                    + calls
                    + " bumps="
                    + bumps
                    + " holderInstanceofs="
                    + holderInstanceofs
                    + " trackedListNews="
                    + trackedListNews;
        }
    }
}
