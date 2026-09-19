package io.pzstorm.storm.bullet.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * The trace API must match the game exactly: every native of {@code zombie.core.physics.Bullet}
 * (read from the class bytes on the test classpath, not by loading the class) is a {@link
 * BulletBackend} method with the same descriptor, and the five upcalls exist in the game with the
 * descriptors the trace uses.
 */
class BulletApiTest implements UnitTest {

    private static byte[] gameClass(String internalName) throws Exception {
        try (InputStream is =
                BulletApiTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** name+descriptor of methods with (or without) ACC_NATIVE, static only. */
    private static Set<String> staticMethods(byte[] bytes, boolean nativeOnly) {
        Set<String> out = new TreeSet<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;
                                if ((access & Opcodes.ACC_STATIC) != 0
                                        && (!nativeOnly || isNative)) {
                                    out.add(name + desc);
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE);
        return out;
    }

    @Test
    void backendCoversExactlyTheGameNatives() throws Exception {
        Set<String> game = staticMethods(gameClass("zombie/core/physics/Bullet"), true);
        Set<String> api = new TreeSet<>();
        for (Sig s : BulletApi.NATIVES) {
            api.add(s.key());
            assertTrue(Modifier.isAbstract(s.method.getModifiers()));
        }
        assertEquals(game, api);
        assertEquals(93, api.size());
    }

    @Test
    void upcallsExistInTheGameWithTheTracedDescriptors() throws Exception {
        assertTrue(
                staticMethods(gameClass("zombie/core/physics/Bullet"), false)
                        .containsAll(
                                Set.of(
                                        BulletApi.UPDATE_PHYSICS_FOR_LEVEL_IF_NEEDED.key(),
                                        BulletApi.ON_VEHICLE_CONSTRAINT_IMPULSE.key())));
        assertTrue(
                staticMethods(gameClass("zombie/debug/DebugLog"), false)
                        .contains(BulletApi.NATIVE_LOG.key()));
        assertTrue(
                staticMethods(gameClass("zombie/core/skinnedmodel/model/SkeletonBone"), false)
                        .containsAll(
                                Set.of(
                                        BulletApi.GET_BONE_NAME.key(),
                                        BulletApi.GET_BONE_ORDINAL.key())));
    }

    @Test
    void everySignatureRoundTripsThroughTheKindTable() {
        for (Sig s : BulletApi.ALL) {
            Sig parsed = new Sig(s.upcall, s.name, s.descriptor);
            assertEquals(s, parsed);
            assertEquals(java.util.List.of(s.params), java.util.List.of(parsed.params), s.key());
            assertEquals(s.ret, parsed.ret, s.key());
            assertEquals(
                    s,
                    s.upcall
                            ? BulletApi.upcallSig(s.name, s.descriptor)
                            : BulletApi.nativeSig(s.name, s.descriptor));
        }
    }
}
