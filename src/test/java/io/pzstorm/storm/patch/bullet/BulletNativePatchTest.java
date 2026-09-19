package io.pzstorm.storm.patch.bullet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.patch.popman.NativeFacadeWeave;
import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

class BulletNativePatchTest implements UnitTest {

    private static final String TARGET = "zombie/core/physics/Bullet.class";
    private static final String FACADE = "io/pzstorm/storm/bullet/StormBullet";

    @Test
    void patchListMatchesTheGameClass() throws Exception {
        NativeFacadeWeave.assertCoversDeclaredNatives(TARGET, BulletNativePatch.NATIVES);
    }

    @Test
    void everyNativeForwardsToTheJavaFacade() throws Exception {
        NativeFacadeWeave.assertEveryNativeForwards(new BulletNativePatch(), TARGET, FACADE);
    }

    @Test
    void facadeSignaturesDoNotNameTheGameClass() throws Exception {
        NativeFacadeWeave.assertFacadeSignaturesDoNotNameTarget(TARGET, FACADE);
    }

    /**
     * The library is never loaded: {@code init()} must lose its call to the private {@code
     * loadLibrary(String)}, which is what calls {@code System.loadLibrary}.
     */
    @Test
    void initNoLongerLoadsTheLibrary() throws Exception {
        byte[] original = NativeFacadeWeave.readClassBytes(TARGET);
        assertTrue(loadLibraryCallers(original).contains("init"), "vanilla loads it in init()");
        byte[] transformed = new BulletNativePatch().transform(original);
        assertFalse(
                loadLibraryCallers(transformed).contains("init"),
                "loadLibrary must be stubbed out of init()");
    }

    /** Methods calling any {@code loadLibrary}: {@code System}'s or Bullet's private wrapper. */
    private static Set<String> loadLibraryCallers(byte[] classBytes) {
        Set<String> callers = new HashSet<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
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
                                        if ("loadLibrary".equals(mName)) {
                                            callers.add(name);
                                        }
                                        super.visitMethodInsn(op, owner, mName, mDesc, itf);
                                    }
                                };
                            }
                        },
                        0);
        return callers;
    }
}
