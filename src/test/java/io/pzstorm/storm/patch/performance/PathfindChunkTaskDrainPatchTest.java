package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Covers the pathfinder chunk task drain from three sides: the pure loop (order, hand-back, budget,
 * a throwing task), the reflective call into the game's package-private task interface, and the
 * woven {@code PathfindNativeThread} bytecode.
 *
 * <p>The weave half asserts that vanilla's own loop survives behind the advice. The drain is an
 * addition in front of it, not a replacement: with the drain off or latched off, the patched method
 * has to behave exactly as vanilla does today.
 *
 * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
 * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java 25 class files.
 */
class PathfindChunkTaskDrainPatchTest implements UnitTest {

    private static final String THREAD = "zombie/pathfind/nativeCode/PathfindNativeThread";
    private static final String DRIVER =
            "io/pzstorm/storm/patch/performance/PathfindChunkTaskDrain";
    private static final String CLQ = "java/util/concurrent/ConcurrentLinkedQueue";
    private static final long NEVER = Long.MAX_VALUE / 2;

    @Test
    void drainExecutesEveryQueuedTaskInOrderAndHandsEachOneBack() {
        Queue<Object> queue = new ArrayDeque<>(List.of("a", "b", "c", "d"));
        Queue<Object> returned = new ArrayDeque<>();
        List<Object> executed = new ArrayList<>();

        int drained = PathfindChunkTaskDrain.drain(queue, returned, executed::add, () -> 0L, NEVER);

        assertEquals(4, drained);
        assertEquals(List.of("a", "b", "c", "d"), executed, "queue order is the native call order");
        assertEquals(List.of("a", "b", "c", "d"), new ArrayList<>(returned));
        assertTrue(queue.isEmpty());
    }

    @Test
    void drainStopsWhenTheBudgetIsSpentAndLeavesTheRestForVanilla() {
        Queue<Object> queue = new ArrayDeque<>(List.of("a", "b", "c", "d", "e"));
        Queue<Object> returned = new ArrayDeque<>();
        // One clock read for the deadline, then one after each task: 0, 4, 8, 12 against a budget
        // of 10 stops after the third task.
        long[] clock = {-4L};

        int drained =
                PathfindChunkTaskDrain.drain(
                        queue, returned, task -> {}, () -> clock[0] += 4L, 10L);

        assertEquals(3, drained);
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(returned));
        assertEquals(List.of("d", "e"), new ArrayList<>(queue), "unreached tasks stay queued");
    }

    @Test
    void drainSurvivesANanoTimeWrap() {
        Queue<Object> queue = new ArrayDeque<>(List.of("a", "b", "c"));
        Queue<Object> returned = new ArrayDeque<>();
        // nanoTime may be anywhere in the long range; a deadline that overflows must not end the
        // drain early or run it forever.
        long[] clock = {Long.MAX_VALUE - 5L - 4L};

        int drained =
                PathfindChunkTaskDrain.drain(
                        queue, returned, task -> {}, () -> clock[0] += 4L, 10L);

        assertEquals(3, drained, "0, 4, 8 of a 10 budget: all three fit, across the wrap");
        assertTrue(queue.isEmpty());
    }

    @Test
    void aThrowingTaskPropagatesIsNotHandedBackAndLeavesLaterTasksQueued() {
        Queue<Object> queue = new ArrayDeque<>(List.of("a", "boom", "c"));
        Queue<Object> returned = new ArrayDeque<>();
        IllegalStateException failure = new IllegalStateException("native said no");
        Consumer<Object> execute =
                task -> {
                    if ("boom".equals(task)) throw failure;
                };

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                PathfindChunkTaskDrain.drain(
                                        queue, returned, execute, () -> 0L, NEVER));

        // Vanilla: the throw leaves updateThread, the run loop logs it, the task is never returned
        // to its pool and the next frame carries on with the task behind it.
        assertSame(failure, thrown);
        assertEquals(List.of("a"), new ArrayList<>(returned));
        assertEquals(List.of("c"), new ArrayList<>(queue));
    }

    @Test
    void theExecutorReachesTheGamesPackagePrivateTaskInterface() throws Exception {
        Class<?> taskInterface = Class.forName("zombie.pathfind.nativeCode.IPathfindTask");
        List<String> calls = new ArrayList<>();
        RuntimeException failure = new RuntimeException("from execute");
        Object task =
                Proxy.newProxyInstance(
                        taskInterface.getClassLoader(),
                        new Class<?>[] {taskInterface},
                        (proxy, method, args) -> {
                            calls.add(method.getName());
                            if (calls.size() == 2) throw failure;
                            return null;
                        });

        Consumer<Object> execute = PathfindChunkTaskDrain.resolveExecutor();
        assertNotNull(execute, "IPathfindTask.execute() must resolve against this game build");

        execute.accept(task);
        assertEquals(
                List.of("execute"), calls, "execute, and never release: the main thread owns it");

        assertSame(
                failure,
                assertThrows(RuntimeException.class, () -> execute.accept(task)),
                "what the task threw comes out, not the reflection wrapper");
    }

    @Test
    void patchAddsTheDrainInFrontOfUpdateThreadAndKeepsTheVanillaLoop() throws Exception {
        byte[] rawClass = readClass();
        Counts vanilla = count(rawClass);
        byte[] transformed = new PathfindChunkTaskDrainPatch().transform(rawClass);
        assertNotNull(transformed);
        Counts patched = count(transformed);

        assertEquals(0, vanilla.drainCalls, "sanity: vanilla does not call the driver");
        assertEquals(1, patched.drainCalls, "updateThread calls the driver exactly once");
        assertEquals(
                0, patched.drainCallsElsewhere, "the advice must not leak outside updateThread");

        for (String field : List.of("chunkTaskQueue", "taskReturnQueue")) {
            assertEquals(
                    vanilla.queueReads.getOrDefault(field, 0) + 1,
                    patched.queueReads.getOrDefault(field, 0),
                    "the driver is handed the thread's own " + field);
        }

        assertEquals(
                List.of("chunkTaskQueue", "taskReturnQueue"),
                patched.drainArguments,
                "argument order: swapped, the drain would empty the return queue into the work"
                    + " queue");

        assertTrue(vanilla.polls > 0, "sanity: vanilla's loops poll their queues");
        assertEquals(vanilla.polls, patched.polls, "every vanilla poll survives behind the drain");
        assertEquals(1, vanilla.tenConstants, "sanity: vanilla's ten-per-frame cap is one bipush");
        assertEquals(vanilla.tenConstants, patched.tenConstants, "the vanilla cap is still there");
    }

    private static byte[] readClass() throws Exception {
        try (InputStream is =
                PathfindChunkTaskDrainPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(THREAD + ".class")) {
            assertNotNull(is, "PathfindNativeThread.class must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static Counts count(byte[] classBytes) {
        Counts counts = new Counts();
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
                                boolean isUpdateThread = "updateThread".equals(name);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    // The two field reads immediately ahead of an instruction.
                                    private final ArrayDeque<String> recentReads =
                                            new ArrayDeque<>();

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (DRIVER.equals(owner) && "drain".equals(mName)) {
                                            if (isUpdateThread) {
                                                counts.drainCalls++;
                                                counts.drainArguments.addAll(recentReads);
                                            } else {
                                                counts.drainCallsElsewhere++;
                                            }
                                        }
                                        if (isUpdateThread
                                                && CLQ.equals(owner)
                                                && "poll".equals(mName)) {
                                            counts.polls++;
                                        }
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        if (isUpdateThread
                                                && opcode == Opcodes.GETFIELD
                                                && THREAD.equals(owner)) {
                                            counts.queueReads.merge(fName, 1, Integer::sum);
                                            recentReads.addLast(fName);
                                            if (recentReads.size() > 2) recentReads.removeFirst();
                                        }
                                    }

                                    @Override
                                    public void visitIntInsn(int opcode, int operand) {
                                        if (isUpdateThread
                                                && opcode == Opcodes.BIPUSH
                                                && operand == 10) {
                                            counts.tenConstants++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static final class Counts {
        int drainCalls;
        int drainCallsElsewhere;
        int polls;
        int tenConstants;
        final Map<String, Integer> queueReads = new LinkedHashMap<>();
        final List<String> drainArguments = new ArrayList<>();
    }
}
