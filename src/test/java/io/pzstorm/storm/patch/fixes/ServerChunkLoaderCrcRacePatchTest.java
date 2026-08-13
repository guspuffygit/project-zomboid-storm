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
 * Verifies both chunk-save CRC race patches at the bytecode level: every {@code GETFIELD} of the
 * shared {@code CRC32} ({@code SaveChunkThread.crc32} in {@code addLoadedJob}, outer {@code
 * ServerChunkLoader.crcSave} in {@code SaveLoadedTask.save}) is replaced by an {@code INVOKESTATIC}
 * to {@code StormChunkSaveCrc.crc(Object)}, and the substitution does not leak into other methods.
 *
 * <p>Counts are anchored to the raw class ("as many scratch calls as there were raw reads") rather
 * than hard-coded, so a PZ update that adds or removes a read keeps the test meaningful. The raw
 * read count being non-zero also proves the vanilla shape assumption (nestmate direct field access,
 * no synthetic accessor) still holds.
 *
 * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
 * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class ServerChunkLoaderCrcRacePatchTest implements UnitTest {

    private static final String SAVE_CHUNK_THREAD =
            "zombie/network/ServerChunkLoader$SaveChunkThread";
    private static final String SAVE_LOADED_TASK =
            "zombie/network/ServerChunkLoader$SaveLoadedTask";
    private static final String OUTER = "zombie/network/ServerChunkLoader";
    private static final String CRC32_DESC = "Ljava/util/zip/CRC32;";
    private static final String SCRATCH_INTERNAL = "io/pzstorm/storm/map/StormChunkSaveCrc";
    private static final String SCRATCH_DESC = "(Ljava/lang/Object;)Ljava/util/zip/CRC32;";

    @Test
    void patchRewritesCrc32ReadsInAddLoadedJobOnly() throws Exception {
        byte[] rawClass = readClass(SAVE_CHUNK_THREAD);

        Counts raw = count(rawClass, SAVE_CHUNK_THREAD, "crc32", "addLoadedJob");
        assertTrue(
                raw.targetMethodFieldReads > 0,
                "vanilla addLoadedJob should read SaveChunkThread.crc32 directly; got "
                        + raw.targetMethodFieldReads
                        + " — the vanilla shape changed, re-verify the patch");

        byte[] transformed = new SaveChunkThreadCrcRacePatch().transform(rawClass);
        assertNotNull(transformed);
        Counts patched = count(transformed, SAVE_CHUNK_THREAD, "crc32", "addLoadedJob");

        assertEquals(
                0,
                patched.targetMethodFieldReads,
                "addLoadedJob should have no remaining GETFIELD on crc32 after rewrite");
        assertEquals(
                raw.targetMethodFieldReads,
                patched.targetMethodScratchCalls,
                "every removed crc32 read in addLoadedJob should become a StormChunkSaveCrc.crc"
                        + " call");
        assertEquals(
                raw.otherMethodFieldReads,
                patched.otherMethodFieldReads,
                "crc32 reads outside addLoadedJob must be untouched");
        assertEquals(
                0,
                patched.otherMethodScratchCalls,
                "the substitution must not leak outside addLoadedJob");
    }

    @Test
    void patchRewritesCrcSaveReadsInSaveOnly() throws Exception {
        byte[] rawClass = readClass(SAVE_LOADED_TASK);

        Counts raw = count(rawClass, OUTER, "crcSave", "save");
        assertTrue(
                raw.targetMethodFieldReads > 0,
                "vanilla SaveLoadedTask.save should read ServerChunkLoader.crcSave directly; got "
                        + raw.targetMethodFieldReads
                        + " — the vanilla shape changed, re-verify the patch");

        byte[] transformed = new SaveLoadedTaskCrcRacePatch().transform(rawClass);
        assertNotNull(transformed);
        Counts patched = count(transformed, OUTER, "crcSave", "save");

        assertEquals(
                0,
                patched.targetMethodFieldReads,
                "save() should have no remaining GETFIELD on crcSave after rewrite");
        assertEquals(
                raw.targetMethodFieldReads,
                patched.targetMethodScratchCalls,
                "every removed crcSave read in save() should become a StormChunkSaveCrc.crc call");
        assertEquals(
                raw.otherMethodFieldReads,
                patched.otherMethodFieldReads,
                "crcSave reads outside save() must be untouched");
        assertEquals(
                0,
                patched.otherMethodScratchCalls,
                "the substitution must not leak outside save()");
    }

    private byte[] readClass(String internalName) throws Exception {
        String resourcePath = internalName + ".class";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private static Counts count(
            byte[] classBytes, String fieldOwner, String fieldName, String targetMethod) {
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
                                final boolean isTarget = targetMethod.equals(name);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        if (opcode == Opcodes.GETFIELD
                                                && fieldOwner.equals(owner)
                                                && fieldName.equals(fName)
                                                && CRC32_DESC.equals(fDesc)) {
                                            if (isTarget) counts.targetMethodFieldReads++;
                                            else counts.otherMethodFieldReads++;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && SCRATCH_INTERNAL.equals(owner)
                                                && "crc".equals(mName)
                                                && SCRATCH_DESC.equals(mDesc)) {
                                            if (isTarget) counts.targetMethodScratchCalls++;
                                            else counts.otherMethodScratchCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class Counts {
        int targetMethodFieldReads;
        int otherMethodFieldReads;
        int targetMethodScratchCalls;
        int otherMethodScratchCalls;
    }
}
