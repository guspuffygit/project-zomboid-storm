package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Weave-verifies the three constructor-exit list-swap patches against the real game class bytes:
 * each constructor gains exactly one {@code StormFastContainsList.copyOf} call per swapped field
 * (and a write back to it), the advice leaks into no other method, and the {@code final} modifier
 * is stripped from exactly the swapped fields — with visibility untouched — so the constructor-exit
 * {@code PUTFIELD} verifies on JDK 9+ class files.
 */
class FastContainsSwapPatchesTest implements UnitTest {

    private static final String COPY_OF_OWNER = "io/pzstorm/storm/util/StormFastContainsList";

    @Test
    void isoCellCtorSwapsBothProcessListsAndDropsFinal() throws Exception {
        byte[] raw = readClass("zombie/iso/IsoCell");
        byte[] transformed = new IsoCellProcessListsFastContainsPatch().transform(raw);
        assertNotNull(transformed);

        assertCopyOfOnlyInConstructors(raw, transformed, 2);
        assertCopyOfWritesField(transformed, "processItems");
        assertCopyOfWritesField(transformed, "processWorldItems");

        Map<String, Integer> before = fieldAccessFlags(raw);
        Map<String, Integer> after = fieldAccessFlags(transformed);
        for (String field : new String[] {"processItems", "processWorldItems"}) {
            assertTrue(
                    (before.get(field) & Opcodes.ACC_FINAL) != 0,
                    field
                            + " should be final in vanilla IsoCell (else drop the"
                            + " ModifierAdjustment)");
            assertEquals(
                    before.get(field) & ~Opcodes.ACC_FINAL,
                    after.get(field),
                    field + " should lose exactly the final flag");
        }
    }

    @Test
    void serverMapCtorSwapsReleventNowWithoutTouchingModifiers() throws Exception {
        byte[] raw = readClass("zombie/network/ServerMap");
        byte[] transformed = new ServerMapReleventNowFastContainsPatch().transform(raw);
        assertNotNull(transformed);

        assertCopyOfOnlyInConstructors(raw, transformed, 1);
        assertCopyOfWritesField(transformed, "releventNow");

        Map<String, Integer> before = fieldAccessFlags(raw);
        Map<String, Integer> after = fieldAccessFlags(transformed);
        assertEquals(
                before.get("releventNow"),
                after.get("releventNow"),
                "releventNow is not final in vanilla — its flags must be untouched");
    }

    @Test
    void designationZoneAnimalCtorSwapsFoodOnGroundAndDropsFinal() throws Exception {
        byte[] raw = readClass("zombie/iso/areas/DesignationZoneAnimal");
        byte[] transformed = new DesignationZoneAnimalFoodFastContainsPatch().transform(raw);
        assertNotNull(transformed);

        assertCopyOfOnlyInConstructors(raw, transformed, 1);
        assertCopyOfWritesField(transformed, "foodOnGround");

        Map<String, Integer> before = fieldAccessFlags(raw);
        Map<String, Integer> after = fieldAccessFlags(transformed);
        assertTrue(
                (before.get("foodOnGround") & Opcodes.ACC_FINAL) != 0,
                "foodOnGround should be final in vanilla DesignationZoneAnimal");
        assertEquals(
                before.get("foodOnGround") & ~Opcodes.ACC_FINAL,
                after.get("foodOnGround"),
                "foodOnGround should lose exactly the final flag");
        assertTrue(
                (after.get("foodOnGround") & Opcodes.ACC_PUBLIC) != 0,
                "visibility must be retained by the modifier adjustment");
    }

    /**
     * Every constructor must gain exactly {@code perCtor} copyOf calls; every non-constructor
     * method must have none (before and after).
     */
    private static void assertCopyOfOnlyInConstructors(byte[] raw, byte[] transformed, int perCtor)
            throws Exception {
        Map<String, Integer> before = copyOfCallsPerMethod(raw);
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            assertEquals(0, entry.getValue(), "vanilla method " + entry.getKey());
        }
        Map<String, Integer> after = copyOfCallsPerMethod(transformed);
        int constructors = 0;
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            if (entry.getKey().startsWith("<init>")) {
                constructors++;
                assertEquals(
                        perCtor,
                        entry.getValue(),
                        "constructor "
                                + entry.getKey()
                                + " should call copyOf "
                                + perCtor
                                + " time(s)");
            } else {
                assertEquals(
                        0,
                        entry.getValue(),
                        "the advice must not leak outside constructors: " + entry.getKey());
            }
        }
        assertTrue(constructors > 0, "target class should declare at least one constructor");
    }

    /** The constructor must write the copyOf result back into the named field. */
    private static void assertCopyOfWritesField(byte[] transformed, String fieldName)
            throws Exception {
        boolean[] found = new boolean[1];
        new ClassReader(transformed)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (!"<init>".equals(name)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fname, String fdesc) {
                                        if (opcode == Opcodes.PUTFIELD && fieldName.equals(fname)) {
                                            found[0] = true;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        assertTrue(found[0], "constructor should write the swapped list back into " + fieldName);
    }

    private static Map<String, Integer> copyOfCallsPerMethod(byte[] classBytes) {
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
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && COPY_OF_OWNER.equals(owner)
                                                && "copyOf".equals(mname)) {
                                            counts.merge(key, 1, Integer::sum);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static Map<String, Integer> fieldAccessFlags(byte[] classBytes) {
        Map<String, Integer> flags = new HashMap<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                flags.put(name, access);
                                return null;
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return flags;
    }

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream is =
                FastContainsSwapPatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
