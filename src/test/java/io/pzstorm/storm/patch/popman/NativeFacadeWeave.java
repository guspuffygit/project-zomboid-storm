package io.pzstorm.storm.patch.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Shared assertions for the {@link NativeFacadePatch} family, run against real game bytes. */
final class NativeFacadeWeave {

    private NativeFacadeWeave() {}

    /** The patch list must be exactly the natives this game version declares. */
    static void assertCoversDeclaredNatives(String targetResource, String[] natives)
            throws Exception {
        Set<String> declared = nativeNames(readClassBytes(targetResource));
        assertEquals(
                new TreeSet<>(declared),
                new TreeSet<>(Set.of(natives)),
                "the patch must cover exactly the natives this game version declares");
    }

    /** No native survives, and each {@code n_*} INVOKESTATICs its same-named facade method. */
    static void assertEveryNativeForwards(
            NativeFacadePatch patch, String targetResource, String facadeInternalName)
            throws Exception {
        assertFacadeSignaturesDoNotNameTarget(targetResource, facadeInternalName);
        byte[] transformed = patch.transform(readClassBytes(targetResource));
        assertTrue(
                nativeNames(transformed).isEmpty(),
                "no method may remain native after the patch: " + nativeNames(transformed));

        Set<String> forwarding = new TreeSet<>();
        new ClassReader(transformed)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                if (!name.startsWith("n_")) {
                                    return super.visitMethod(access, name, desc, sig, ex);
                                }
                                return new MethodVisitor(
                                        Opcodes.ASM9,
                                        super.visitMethod(access, name, desc, sig, ex)) {
                                    @Override
                                    public void visitMethodInsn(
                                            int op,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean itf) {
                                        if (op == Opcodes.INVOKESTATIC
                                                && facadeInternalName.equals(owner)
                                                && name.equals(mName)) {
                                            forwarding.add(name);
                                        }
                                        super.visitMethodInsn(op, owner, mName, mDesc, itf);
                                    }
                                };
                            }
                        },
                        0);

        assertEquals(
                new TreeSet<>(Set.of(patch.natives())),
                forwarding,
                "each n_* must call its same-named facade counterpart");
    }

    /**
     * ByteBuddy reflects over the facade's methods while the target class is still being defined; a
     * facade signature naming the target (e.g. {@code @This ZombiePopulationRenderer}) triggers a
     * {@link ClassCircularityError} at boot that no in-test weave can reproduce, because the test
     * classpath has the target loaded already. Use {@code @This Object} and cast inside.
     */
    static void assertFacadeSignaturesDoNotNameTarget(
            String targetResource, String facadeInternalName) throws Exception {
        String targetRef = "L" + targetResource.replace(".class", "") + ";";
        Set<String> offenders = new TreeSet<>();
        new ClassReader(readClassBytes(facadeInternalName + ".class"))
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                if (desc.contains(targetRef)) {
                                    offenders.add(name + desc);
                                }
                                return super.visitMethod(access, name, desc, sig, ex);
                            }
                        },
                        0);
        assertTrue(
                offenders.isEmpty(),
                "facade signatures must not name the target class (boot-time"
                        + " ClassCircularityError): "
                        + offenders);
    }

    static byte[] readClassBytes(String resourcePath) throws Exception {
        try (InputStream is =
                NativeFacadeWeave.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, resourcePath + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    static Set<String> nativeNames(byte[] classBytes) {
        Set<String> names = new HashSet<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                if ((access & Opcodes.ACC_NATIVE) != 0) {
                                    names.add(name);
                                }
                                return super.visitMethod(access, name, desc, sig, ex);
                            }
                        },
                        0);
        return names;
    }
}
