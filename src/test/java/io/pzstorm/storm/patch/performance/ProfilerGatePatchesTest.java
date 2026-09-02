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
 * Per method: {0: GETSTATIC StormGameProfilerGate.running, 1: onTransition calls, 2: GETFIELD
 * isProfilerRunning}. Only the targeted methods change, each in the expected way.
 */
class ProfilerGatePatchesTest implements UnitTest {

    private static final String GATE = "io/pzstorm/storm/profiling/StormGameProfilerGate";

    @Test
    void gameProfilerFrameMethodsReportAndIsRunningGates() throws Exception {
        byte[] raw = readClass("zombie/GameProfiler");
        byte[] transformed = new GameProfilerGatePatch().transform(raw);
        assertNotNull(transformed);
        Map<String, int[]> before = count(raw);
        Map<String, int[]> after = count(transformed);
        assertTrue(before.containsKey("isRunning()Z"));
        for (Map.Entry<String, int[]> e : before.entrySet()) {
            String key = e.getKey();
            int[] b = e.getValue();
            int[] a = after.get(key);
            assertEquals(0, b[0] + b[1], key + " vanilla must not reference the gate");
            if (key.startsWith("startFrame(") || key.equals("endFrame()V")) {
                assertTrue(a[1] >= 1, key + " must report its transition");
                assertEquals(0, a[0]);
            } else if (key.equals("isRunning()Z")) {
                assertEquals(1, a[0], "static isRunning reads the gate once");
                assertEquals(0, a[1]);
            } else {
                assertEquals(0, a[0] + a[1], key + " must be untouched");
            }
        }
    }

    @Test
    void probeStartAndEndSkipWhenIdle() throws Exception {
        byte[] raw = readClass("zombie/core/profiling/AbstractPerformanceProfileProbe");
        byte[] transformed = new PerformanceProbeGatePatch().transform(raw);
        assertNotNull(transformed);
        Map<String, int[]> before = count(raw);
        Map<String, int[]> after = count(transformed);
        assertEquals(1, before.get("start()V")[2]);
        assertEquals(1, before.get("end()V")[2]);
        for (Map.Entry<String, int[]> e : before.entrySet()) {
            String key = e.getKey();
            int[] b = e.getValue();
            int[] a = after.get(key);
            assertEquals(0, b[0]);
            if (key.equals("start()V")) {
                assertEquals(1, a[0], "start reads the gate once");
                assertEquals(2, a[2], "start reads isProfilerRunning once more");
            } else if (key.equals("end()V")) {
                assertEquals(0, a[0], "end needs no global gate");
                assertEquals(2, a[2], "end reads isProfilerRunning once more");
            } else {
                assertEquals(0, a[0], key);
                assertEquals(b[2], a[2], key + " must be untouched");
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
                                        counts.computeIfAbsent(name + descriptor, k -> new int[3]);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fname, String fdesc) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && GATE.equals(owner)
                                                && "running".equals(fname)) {
                                            c[0]++;
                                        } else if (opcode == Opcodes.GETFIELD
                                                && "isProfilerRunning".equals(fname)) {
                                            c[2]++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean isInterface) {
                                        if (GATE.equals(owner) && "onTransition".equals(mname)) {
                                            c[1]++;
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
                ProfilerGatePatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
