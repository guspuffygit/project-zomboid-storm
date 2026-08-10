package io.pzstorm.storm.patch.fixes;

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
 * Verifies that {@link IsoGridSquareGetRoomNullDefGuardPatch} injects the gutted-room guard advice
 * into {@code IsoGridSquare.getRoom()} and only into that method.
 *
 * <p>Detection signal: the inlined advice reads the advice class's {@code WARNED} latch (a {@code
 * GETSTATIC} on {@code IsoGridSquareGetRoomNullDefGuardAdvice}). Vanilla {@code getRoom} contains
 * no such read, so seeing one after the transform proves the advice landed on the right method;
 * seeing none in {@code getRoomDef} (a caller of {@code getRoom}, so an easy over-match) proves the
 * matcher didn't leak.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class IsoGridSquareGetRoomNullDefGuardPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/iso/IsoGridSquare";
    private static final String ADVICE_CLASS =
            "io/pzstorm/storm/advice/isogridsquaregetroomnulldefguard/"
                    + "IsoGridSquareGetRoomNullDefGuardAdvice";
    private static final String WARNED_FIELD = "WARNED";

    private static final String TARGET_METHOD = "getRoom";
    private static final String TARGET_DESC = "()Lzombie/iso/areas/IsoRoom;";

    // An unrelated method on the same class used to assert no scope leak.
    private static final String SIBLING_METHOD = "getRoomDef";
    private static final String SIBLING_DESC = "()Lzombie/iso/RoomDef;";

    @Test
    void patchInjectsAdviceIntoGetRoomOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new IsoGridSquareGetRoomNullDefGuardPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        int targetBefore = countWarnedReadsInMethod(rawClass, TARGET_METHOD, TARGET_DESC);
        int targetAfter = countWarnedReadsInMethod(transformed, TARGET_METHOD, TARGET_DESC);
        int siblingBefore = countWarnedReadsInMethod(rawClass, SIBLING_METHOD, SIBLING_DESC);
        int siblingAfter = countWarnedReadsInMethod(transformed, SIBLING_METHOD, SIBLING_DESC);

        // Vanilla getRoom has no reference to the advice's WARNED latch.
        assertEquals(
                0,
                targetBefore,
                "Vanilla getRoom should not read the advice WARNED latch before patch");

        // After transform the advice's once-per-session log latch must be inlined, producing at
        // least one GETSTATIC <Advice>.WARNED in the method body.
        assertTrue(
                targetAfter >= 1,
                "Patched getRoom must contain >=1 GETSTATIC "
                        + ADVICE_CLASS
                        + ".WARNED (advice not injected); got "
                        + targetAfter);

        // Scope check: the matcher is named("getRoom").and(takesArguments(0)), so getRoomDef must
        // remain untouched.
        assertEquals(
                siblingBefore,
                siblingAfter,
                "Advice must not leak into IsoGridSquare."
                        + SIBLING_METHOD
                        + "; before="
                        + siblingBefore
                        + " after="
                        + siblingAfter);
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static int countWarnedReadsInMethod(byte[] classBytes, String method, String desc) {
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
                                if (!method.equals(name) || !desc.equals(descriptor)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && ADVICE_CLASS.equals(owner)
                                                && WARNED_FIELD.equals(fName)) {
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
