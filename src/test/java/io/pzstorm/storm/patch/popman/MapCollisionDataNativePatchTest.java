package io.pzstorm.storm.patch.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import java.util.Map;
import java.util.TreeMap;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

class MapCollisionDataNativePatchTest implements UnitTest {

    private static final String TARGET = "zombie/MapCollisionData.class";
    private static final String FACADE = "io/pzstorm/storm/popman/StormMapCollisionData";

    @Test
    void patchListMatchesTheGameClass() throws Exception {
        NativeFacadeWeave.assertCoversDeclaredNatives(TARGET, MapCollisionDataNativePatch.NATIVES);
    }

    @Test
    void everyNativeForwardsToTheJavaFacade() throws Exception {
        NativeFacadeWeave.assertEveryNativeForwards(
                new MapCollisionDataNativePatch(), TARGET, FACADE);
    }

    /** Five {@code n_setGameState} overloads: each must land on the facade overload of its type. */
    @Test
    void eachSetGameStateOverloadForwardsToItsOwnDescriptor() throws Exception {
        byte[] transformed =
                new MapCollisionDataNativePatch()
                        .transform(NativeFacadeWeave.readClassBytes(TARGET));
        Map<String, String> forwardedDescriptor = new TreeMap<>();
        new ClassReader(transformed)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                if (!"n_setGameState".equals(name)) {
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
                                        if (FACADE.equals(owner) && name.equals(mName)) {
                                            forwardedDescriptor.put(desc, mDesc);
                                        }
                                        super.visitMethodInsn(op, owner, mName, mDesc, itf);
                                    }
                                };
                            }
                        },
                        0);
        assertEquals(5, forwardedDescriptor.size(), forwardedDescriptor.toString());
        forwardedDescriptor.forEach(
                (own, called) -> assertEquals(own, called, "overload must keep its own types"));
    }
}
