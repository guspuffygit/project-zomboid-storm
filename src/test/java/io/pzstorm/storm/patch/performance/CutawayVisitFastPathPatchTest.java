package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies the patched {@code FBORenderCutaways} bytecode wires {@code cutawayVisit} through the
 * fast-path advice: exactly one inlined {@code CutawayVisitFastPath.visit} dispatch at method
 * entry, confined to {@code cutawayVisit}, with the vanilla body left in place as the runtime
 * fallback the fail-soft latch relies on.
 *
 * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
 * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class CutawayVisitFastPathPatchTest implements UnitTest {

    private static final String FBO_RENDER_CUTAWAYS = "zombie/iso/fboRenderChunk/FBORenderCutaways";
    private static final String FAST_PATH_INTERNAL =
            "io/pzstorm/storm/advice/cutawayvisit/CutawayVisitFastPath";

    @Test
    void patchInlinesFastPathDispatchOnlyIntoCutawayVisit() throws Exception {
        String resourcePath = FBO_RENDER_CUTAWAYS + ".class";
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "FBORenderCutaways.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new CutawayVisitFastPathPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Counts counts = countInvocations(transformed);

        assertEquals(
                1,
                counts.cutawayVisitFastPathCalls,
                "cutawayVisit should contain exactly one inlined INVOKESTATIC to "
                        + "CutawayVisitFastPath.visit; got "
                        + counts.cutawayVisitFastPathCalls);
        assertEquals(
                0,
                counts.otherMethodFastPathCalls,
                "the advice must not leak outside cutawayVisit; got "
                        + counts.otherMethodFastPathCalls);

        // The vanilla body must survive as the runtime fallback: the fail-soft latch skips the
        // fast path by falling through to it. IsCutawaySquare is only reachable from the vanilla
        // loop inside cutawayVisit.
        assertTrue(
                counts.cutawayVisitIsCutawaySquareCalls >= 1,
                "cutawayVisit should retain the vanilla IsCutawaySquare call as fallback; got "
                        + counts.cutawayVisitIsCutawaySquareCalls);
    }

    private static Counts countInvocations(byte[] classBytes) {
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
                                final boolean isCutawayVisit = "cutawayVisit".equals(name);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        boolean isFastPathCall =
                                                opcode == Opcodes.INVOKESTATIC
                                                        && FAST_PATH_INTERNAL.equals(owner)
                                                        && "visit".equals(mName);
                                        boolean isVanillaIsCutawaySquare =
                                                FBO_RENDER_CUTAWAYS.equals(owner)
                                                        && "IsCutawaySquare".equals(mName);
                                        if (isFastPathCall) {
                                            if (isCutawayVisit) counts.cutawayVisitFastPathCalls++;
                                            else counts.otherMethodFastPathCalls++;
                                        }
                                        if (isVanillaIsCutawaySquare && isCutawayVisit) {
                                            counts.cutawayVisitIsCutawaySquareCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class Counts {
        int cutawayVisitFastPathCalls;
        int otherMethodFastPathCalls;
        int cutawayVisitIsCutawaySquareCalls;
    }
}
