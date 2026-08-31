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
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Weave-verifies {@link DesignationZoneAnimalGetAllDZonesPatch} against the real game class bytes:
 * exactly the static three-arg {@code getAllDZones} gains a call into {@link
 * DesignationZoneAnimalConnectedZones}, and no other method in the class is touched.
 */
class DesignationZoneAnimalGetAllDZonesPatchTest implements UnitTest {

    private static final String LOGIC_OWNER =
            "io/pzstorm/storm/patch/performance/DesignationZoneAnimalConnectedZones";

    @Test
    void weavesOnlyGetAllDZones() throws Exception {
        byte[] raw = readClass("zombie/iso/areas/DesignationZoneAnimal");
        byte[] transformed = new DesignationZoneAnimalGetAllDZonesPatch().transform(raw);
        assertNotNull(transformed);

        for (Map.Entry<String, Integer> entry : logicCallsPerMethod(raw).entrySet()) {
            assertEquals(0, entry.getValue(), "vanilla method " + entry.getKey());
        }

        Map<String, Integer> after = logicCallsPerMethod(transformed);
        String target =
                "getAllDZones(Ljava/util/ArrayList;Lzombie/iso/areas/DesignationZoneAnimal;"
                        + "Lzombie/iso/areas/DesignationZoneAnimal;)Ljava/util/ArrayList;";
        assertTrue(after.containsKey(target), "target method must exist: " + target);
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            if (entry.getKey().equals(target)) {
                assertEquals(1, entry.getValue(), "getAllDZones should call the logic class once");
            } else {
                assertEquals(0, entry.getValue(), "advice must not leak into " + entry.getKey());
            }
        }
    }

    private static Map<String, Integer> logicCallsPerMethod(byte[] classBytes) {
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
                                                && LOGIC_OWNER.equals(owner)
                                                && "getAllDZones".equals(mname)) {
                                            counts.merge(key, 1, Integer::sum);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream is =
                DesignationZoneAnimalGetAllDZonesPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
