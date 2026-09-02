package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link AdvancedAnimatorMissingFolderPatch} lands on {@code
 * AdvancedAnimator.searchFolders(URI, Path)} only, and that the patched method returns an empty
 * list for a missing folder instead of walking it.
 *
 * <p>Detection signal for the weave: the inlined enter advice calls {@code Files.isDirectory}.
 * Vanilla {@code searchFolders} never does (it hands the path straight to {@code walkFileTree}),
 * and {@code loadModMedia} — the caller, and an easy over-match — must stay clean.
 *
 * <p>The behavioral half defines the transformed class in a throwaway class loader and invokes
 * {@code searchFolders} reflectively. The skip branch touches nothing but {@code java.nio}, so no
 * game static state is initialised.
 *
 * <p>Uses ByteBuddy's bundled ASM ({@code net.bytebuddy.jar.asm.*}) because the standalone {@code
 * org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class AdvancedAnimatorMissingFolderPatchTest implements UnitTest {

    private static final String TARGET_CLASS =
            "zombie/core/skinnedmodel/advancedanimation/AdvancedAnimator";
    private static final String TARGET_METHOD = "searchFolders";
    private static final String TARGET_DESC =
            "(Ljava/net/URI;Ljava/nio/file/Path;)Ljava/util/List;";

    private static final String SIBLING_METHOD = "loadModMedia";
    private static final String SIBLING_DESC = "(Ljava/lang/String;)Ljava/util/List;";

    @Test
    void patchInjectsExistenceCheckIntoSearchFoldersOnly() throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new AdvancedAnimatorMissingFolderPatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        assertEquals(
                0,
                countIsDirectoryCalls(rawClass, TARGET_METHOD, TARGET_DESC),
                "Vanilla searchFolders should not call Files.isDirectory before patch");
        assertTrue(
                countIsDirectoryCalls(transformed, TARGET_METHOD, TARGET_DESC) >= 1,
                "Patched searchFolders must call Files.isDirectory (advice not injected)");
        assertEquals(
                countIsDirectoryCalls(rawClass, SIBLING_METHOD, SIBLING_DESC),
                countIsDirectoryCalls(transformed, SIBLING_METHOD, SIBLING_DESC),
                "Advice must not leak into AdvancedAnimator." + SIBLING_METHOD);
    }

    @Test
    void patchedSearchFoldersReturnsEmptyListForMissingFolder(@TempDir Path tmp) throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new AdvancedAnimatorMissingFolderPatch().transform(rawClass);
        Method searchFolders =
                loadIsolated(transformed).getMethod(TARGET_METHOD, URI.class, Path.class);

        Path missing = tmp.resolve("media").resolve("AnimSets");
        assertTrue(Files.notExists(missing));

        Object result = searchFolders.invoke(null, tmp.toUri(), missing);
        assertNotNull(result, "Skipped searchFolders must return a list, not null");
        assertTrue(((List<?>) result).isEmpty(), "Missing folder must contribute no files");
    }

    @Test
    void patchedSearchFoldersStillWalksExistingFolder(@TempDir Path tmp) throws Exception {
        byte[] rawClass = readClassBytes(TARGET_CLASS + ".class");
        byte[] transformed = new AdvancedAnimatorMissingFolderPatch().transform(rawClass);
        Method searchFolders =
                loadIsolated(transformed).getMethod(TARGET_METHOD, URI.class, Path.class);

        // A non-XML file exercises visitFile without reaching ZomboidFileSystem (only .xml files
        // are resolved through it), so the walk completes with no game state touched.
        Path existing = Files.createDirectories(tmp.resolve("media").resolve("AnimSets"));
        Files.writeString(existing.resolve("readme.txt"), "not an animset");

        Object result = searchFolders.invoke(null, tmp.toUri(), existing);
        assertNotNull(result);
        assertTrue(((List<?>) result).isEmpty(), "Non-XML files are not anim-set files");
    }

    private byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    private Class<?> loadIsolated(byte[] classBytes) {
        String binaryName = TARGET_CLASS.replace('/', '.');
        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (!name.equals(binaryName) && !name.startsWith(binaryName + "$")) {
                            return super.loadClass(name, resolve);
                        }
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded != null) {
                            return loaded;
                        }
                        if (name.equals(binaryName)) {
                            return defineClass(name, classBytes, 0, classBytes.length);
                        }
                        // Inner classes (the FileVisitor) come from the untouched classpath copy.
                        String resource = name.replace('.', '/') + ".class";
                        try (InputStream is = getParent().getResourceAsStream(resource)) {
                            if (is == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] bytes = is.readAllBytes();
                            return defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                };
        try {
            return Class.forName(binaryName, true, loader);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static int countIsDirectoryCalls(byte[] classBytes, String method, String desc) {
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
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (opcode == Opcodes.INVOKESTATIC
                                                && "java/nio/file/Files".equals(owner)
                                                && "isDirectory".equals(mName)) {
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
