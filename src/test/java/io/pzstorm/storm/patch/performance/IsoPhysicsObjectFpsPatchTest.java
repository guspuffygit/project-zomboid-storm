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
 * Verifies the patched {@code IsoPhysicsObject} bytecode rewrites both halves of the FPS-resolution
 * ternary inside {@code update()}: the {@code GameServer.server} field read becomes a static call
 * to {@link IsoPhysicsObjectFpsConfig#alwaysFalse()}, and the {@code
 * PerformanceSettings.getLockFPS()} call becomes {@link IsoPhysicsObjectFpsConfig#resolveFps()}.
 * Substitutions must not leak into other methods on the class.
 *
 * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
 * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class IsoPhysicsObjectFpsPatchTest implements UnitTest {

    private static final String ISO_PHYSICS_OBJECT = "zombie/iso/IsoPhysicsObject";
    private static final String GAME_SERVER = "zombie/network/GameServer";
    private static final String PERFORMANCE_SETTINGS = "zombie/core/PerformanceSettings";
    private static final String CONFIG_INTERNAL =
            IsoPhysicsObjectFpsConfig.class.getName().replace('.', '/');

    @Test
    void patchRewritesOnlyTheUpdateCallSites() throws Exception {
        String resourcePath = ISO_PHYSICS_OBJECT + ".class";
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "IsoPhysicsObject.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new IsoPhysicsObjectFpsPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Counts counts = countInvocations(transformed);

        // The GameServer.server GETSTATIC inside update() should be replaced by a call to
        // alwaysFalse(); no raw field read should remain in update().
        assertEquals(
                0,
                counts.updateServerFieldReads,
                "update() should have no remaining GETSTATIC on GameServer.server after rewrite; got "
                        + counts.updateServerFieldReads);
        assertEquals(
                1,
                counts.updateAlwaysFalseCalls,
                "update() should contain exactly one INVOKESTATIC to "
                        + "IsoPhysicsObjectFpsConfig.alwaysFalse; got "
                        + counts.updateAlwaysFalseCalls);

        // The PerformanceSettings.getLockFPS() call inside update() should be replaced by
        // resolveFps(); no raw call should remain in update().
        assertEquals(
                0,
                counts.updateGetLockFpsCalls,
                "update() should have no remaining INVOKESTATIC on PerformanceSettings.getLockFPS"
                        + " after rewrite; got "
                        + counts.updateGetLockFpsCalls);
        assertEquals(
                1,
                counts.updateResolveFpsCalls,
                "update() should contain exactly one INVOKESTATIC to "
                        + "IsoPhysicsObjectFpsConfig.resolveFps; got "
                        + counts.updateResolveFpsCalls);

        // Substitutions must not leak into methods outside update(). IsoPhysicsObject does not
        // currently reference these members elsewhere; assert hard zero so a scope widening is
        // caught.
        assertEquals(
                0,
                counts.otherMethodAlwaysFalseCalls,
                "alwaysFalse substitution must not leak outside update(); got "
                        + counts.otherMethodAlwaysFalseCalls);
        assertEquals(
                0,
                counts.otherMethodResolveFpsCalls,
                "resolveFps substitution must not leak outside update(); got "
                        + counts.otherMethodResolveFpsCalls);
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
                                final boolean isUpdate =
                                        "update".equals(name) && "()V".equals(descriptor);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && GAME_SERVER.equals(owner)
                                                && "server".equals(fName)
                                                && "Z".equals(fDesc)) {
                                            if (isUpdate) counts.updateServerFieldReads++;
                                            else counts.otherMethodServerFieldReads++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        boolean isVanillaGetLockFps =
                                                opcode == Opcodes.INVOKESTATIC
                                                        && PERFORMANCE_SETTINGS.equals(owner)
                                                        && "getLockFPS".equals(mName)
                                                        && "()I".equals(mDesc);
                                        boolean isAlwaysFalseCall =
                                                opcode == Opcodes.INVOKESTATIC
                                                        && CONFIG_INTERNAL.equals(owner)
                                                        && "alwaysFalse".equals(mName)
                                                        && "()Z".equals(mDesc);
                                        boolean isResolveFpsCall =
                                                opcode == Opcodes.INVOKESTATIC
                                                        && CONFIG_INTERNAL.equals(owner)
                                                        && "resolveFps".equals(mName)
                                                        && "()I".equals(mDesc);
                                        if (isVanillaGetLockFps) {
                                            if (isUpdate) counts.updateGetLockFpsCalls++;
                                            else counts.otherMethodGetLockFpsCalls++;
                                        }
                                        if (isAlwaysFalseCall) {
                                            if (isUpdate) counts.updateAlwaysFalseCalls++;
                                            else counts.otherMethodAlwaysFalseCalls++;
                                        }
                                        if (isResolveFpsCall) {
                                            if (isUpdate) counts.updateResolveFpsCalls++;
                                            else counts.otherMethodResolveFpsCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class Counts {
        int updateServerFieldReads;
        int updateGetLockFpsCalls;
        int updateAlwaysFalseCalls;
        int updateResolveFpsCalls;
        int otherMethodServerFieldReads;
        int otherMethodGetLockFpsCalls;
        int otherMethodAlwaysFalseCalls;
        int otherMethodResolveFpsCalls;
    }
}
