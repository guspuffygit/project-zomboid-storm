package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.IntegrationTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Bytecode-shape integration test for {@link IsoZombieUpdateFixPatch}. Loads {@code
 * zombie.characters.IsoZombie} off the test classpath, runs the real patch's {@code transform()},
 * and walks the resulting method table with ASM to confirm that {@code IsoZombie.update()} now
 * contains an {@code INVOKESTATIC} to {@link IsoZombieMapInvariant#ensureMapEntry(Object)}.
 *
 * <p>Why bytecode shape only: instantiating {@code IsoZombie} in a unit test would pull in {@code
 * SharedSkeleAnimationRepository} and the entire animation/graphics stack via the class's static
 * initializer — too much surface for a unit test to set up reliably. The decision logic is covered
 * by {@link IsoZombieMapInvariantTest}; this test covers the wiring (that the advice actually gets
 * inlined into the patched method).
 *
 * <p>The shape check catches a class of regressions where someone widens or narrows the matcher,
 * renames the advice class, or removes the advice altogether — any of which would let the patch
 * register successfully but silently stop healing zombies.
 */
class IsoZombieUpdateFixPatchTest implements IntegrationTest {

    private static final String ISO_ZOMBIE = "zombie.characters.IsoZombie";
    private static final String UPDATE = "update";
    private static final String INVARIANT_INTERNAL =
            IsoZombieMapInvariant.class.getName().replace('.', '/');
    private static final String ENSURE_MAP_ENTRY = "ensureMapEntry";

    @Test
    void patchedUpdateCallsInvariantHelper() throws Exception {
        ClassLoader parent = IsoZombieUpdateFixPatchTest.class.getClassLoader();
        String resourcePath = ISO_ZOMBIE.replace('.', '/') + ".class";
        byte[] rawClass;
        try (InputStream is = parent.getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new IsoZombieUpdateFixPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        int before = countInvariantCallsInUpdate(rawClass);
        int after = countInvariantCallsInUpdate(transformed);

        assertEquals(0, before, "Vanilla update() must not reference the invariant helper");
        assertTrue(
                after >= 1,
                "Patched update() must call IsoZombieMapInvariant.ensureMapEntry; got " + after);
    }

    private static int countInvariantCallsInUpdate(byte[] classBytes) {
        int[] hits = new int[1];
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
                                if (!UPDATE.equals(name) || !"()V".equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && INVARIANT_INTERNAL.equals(owner)
                                                && ENSURE_MAP_ENTRY.equals(mName)) {
                                            hits[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return hits[0];
    }
}
