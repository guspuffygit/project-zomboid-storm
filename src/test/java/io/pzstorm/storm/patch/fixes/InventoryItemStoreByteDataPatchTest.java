package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link InventoryItemStoreByteDataPatch} weaves {@link ItemByteDataWriter} into
 * {@code InventoryItem.storeInByteData} and only there, and that the writer reproduces vanilla's
 * byte layout while growing past vanilla's fixed 20 KB scratch buffer.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class InventoryItemStoreByteDataPatchTest implements UnitTest {

    private static final String TARGET_CLASS = "zombie/inventory/InventoryItem";
    private static final String WRITER_CLASS = "io/pzstorm/storm/patch/fixes/ItemByteDataWriter";

    private static final String TARGET_METHOD = "storeInByteData";
    private static final String TARGET_DESC = "(Lzombie/iso/IsoObject;)V";

    // The reader half of the same byteData contract, an easy over-match for a sloppy matcher.
    private static final String SIBLING_METHOD = "loadFromByteData";

    @Test
    void patchInjectsWriterIntoStoreInByteDataOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new InventoryItemStoreByteDataPatch().transform(rawClass);
        assertNotNull(transformed);

        assertEquals(
                0,
                countWriterCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla storeInByteData must not reference the Storm writer");
        assertTrue(
                countWriterCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched storeInByteData must call " + WRITER_CLASS + ".write");
        assertEquals(
                0,
                countWriterCalls(transformed, SIBLING_METHOD, null),
                "Advice must not leak into " + SIBLING_METHOD);
    }

    @Test
    void fillGrowsPastTheVanillaCapacity() {
        int size = ItemByteDataWriter.VANILLA_CAPACITY * 3;
        ByteBuffer saved = ItemByteDataWriter.fill(out -> writeBytes(out, size), getClass());

        assertNotNull(saved, "fill must grow instead of overflowing");
        assertEquals(size, saved.limit());
    }

    @Test
    void prependHeaderMatchesTheVanillaLayout() {
        ByteBuffer saved = ItemByteDataWriter.fill(out -> writeBytes(out, 64), getClass());
        assertNotNull(saved);

        ByteBuffer out = ItemByteDataWriter.prependHeader(saved, null);

        // 'W' 'V' 'E' 'R', the world version, then the payload minus the two dropped lead bytes.
        assertEquals(64 - 2 + 8, out.limit());
        assertEquals((byte) 'W', out.get());
        assertEquals((byte) 'V', out.get());
        assertEquals((byte) 'E', out.get());
        assertEquals((byte) 'R', out.get());
        assertTrue(out.getInt() > 0, "world version must be written");
        assertEquals((byte) 2, out.get(), "payload must resume at the third saved byte");
    }

    @Test
    void prependHeaderReusesABufferThatIsLargeEnough() {
        ByteBuffer saved = ItemByteDataWriter.fill(out -> writeBytes(out, 64), getClass());
        ByteBuffer existing = ByteBuffer.allocate(1024);

        assertEquals(existing, ItemByteDataWriter.prependHeader(saved, existing));

        ByteBuffer bigger = ItemByteDataWriter.fill(out -> writeBytes(out, 4096), getClass());
        assertTrue(
                ItemByteDataWriter.prependHeader(bigger, existing) != existing,
                "a buffer too small must not be reused");
    }

    private static void writeBytes(ByteBuffer out, int count) {
        for (int i = 0; i < count; i++) {
            out.put((byte) i);
        }
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** Counts {@code INVOKESTATIC ItemByteDataWriter.write} inside one method. */
    private static int countWriterCalls(byte[] classBytes, String method, String desc) {
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
                                if (!method.equals(name)
                                        || (desc != null && !desc.equals(descriptor))) {
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
                                                && WRITER_CLASS.equals(owner)
                                                && "write".equals(mName)) {
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
