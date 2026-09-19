package io.pzstorm.storm.patch.bullet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.trace.BulletApi;
import io.pzstorm.storm.bullet.trace.Sig;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;

/**
 * Weave validation of the {@code -Dstorm.bullet.record} patches against the real game classes (the
 * AllPatchesIntegrationTest pattern; clientPatchTest runs with {@code storm.bullet.java}, which
 * switches the recorder off, so they are validated here). The patches fail soft, so a broken weave
 * would not throw: {@code lastError()} must be null and the bytecode must show the rewiring.
 */
class BulletRecordPatchTest implements UnitTest {

    private static final String INTERCEPTOR =
            "io/pzstorm/storm/patch/bullet/BulletRecordPatch$NativeInterceptor";
    private static final String UPCALLS = "io/pzstorm/storm/patch/bullet/BulletRecordUpcalls";
    private static final String RECORDER = "io/pzstorm/storm/patch/bullet/BulletRecorder";

    private static byte[] game(String internalName) throws Exception {
        try (InputStream is =
                BulletRecordPatchTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** method name+desc -> (access, set of "owner.name" static calls it makes). */
    private record MethodInfo(int access, Set<String> calls) {}

    private static Map<String, MethodInfo> methods(byte[] bytes) {
        Map<String, MethodInfo> out = new TreeMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                Set<String> calls = new TreeSet<>();
                                out.put(name + desc, new MethodInfo(access, calls));
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int op, String owner, String n, String d, boolean itf) {
                                        calls.add(owner + "." + n);
                                    }
                                };
                            }
                        },
                        0);
        return out;
    }

    private static void assertResolvable(String className, byte[] bytes) {
        TypePool pool = TypePool.Default.of(ClassFileLocator.Simple.of(className, bytes));
        assertTrue(pool.describe(className).isResolved(), className + " must resolve");
    }

    @Test
    void gameBulletNativesBecomeRecordingWrappers() throws Exception {
        byte[] raw = game("zombie/core/physics/Bullet");
        BulletRecordPatch patch = new BulletRecordPatch();
        byte[] out = patch.transform(raw);
        assertNull(patch.lastError(), () -> "weave failed: " + patch.lastError());
        assertNotSame(raw, out);
        assertResolvable("zombie.core.physics.Bullet", out);

        Map<String, MethodInfo> before = methods(raw);
        Map<String, MethodInfo> after = methods(out);
        int natives = 0;
        for (Sig s : BulletApi.NATIVES) {
            MethodInfo m = after.get(s.key());
            assertNotNull(m, s.key());
            assertEquals(0, m.access() & Opcodes.ACC_NATIVE, s.key() + " must not stay native");
            assertTrue(m.calls().contains(INTERCEPTOR + ".call"), s.key() + " -> " + m.calls());
            assertTrue((before.get(s.key()).access() & Opcodes.ACC_NATIVE) != 0);
            natives++;
        }
        assertEquals(93, natives);
        assertTrue(after.values().stream().noneMatch(m -> (m.access() & Opcodes.ACC_NATIVE) != 0));
        MethodInfo load = after.get("loadLibrary(Ljava/lang/String;)V");
        assertEquals(Set.of(INTERCEPTOR + ".loadLibrary"), load.calls());
        assertTrue(Modifier.isPrivate(load.access()), "loadLibrary keeps its access");
        // everything else is untouched
        assertEquals(
                before.get("init()V").calls(), after.get("init()V").calls(), "init() is unchanged");
    }

    @Test
    void isolatedCopyKeepsNativesAndRedirectsUpcalls() throws Exception {
        byte[] raw = game("zombie/core/physics/Bullet");
        BulletRecordPatch patch = new BulletRecordPatch();
        patch.transform(raw);
        assertTrue(BulletRecorder.isPrepared());
        byte[] iso = BulletRecorder.preparedBytes();
        assertResolvable("zombie.core.physics.Bullet", iso);
        Map<String, MethodInfo> m = methods(iso);
        for (Sig s : BulletApi.NATIVES) {
            assertTrue(
                    (m.get(s.key()).access() & Opcodes.ACC_NATIVE) != 0, s.key() + " stays native");
        }
        assertTrue(
                m.get(BulletApi.UPDATE_PHYSICS_FOR_LEVEL_IF_NEEDED.key())
                        .calls()
                        .contains(UPCALLS + ".updatePhysicsForLevelIfNeeded"));
        assertTrue(
                m.get(BulletApi.ON_VEHICLE_CONSTRAINT_IMPULSE.key())
                        .calls()
                        .contains(UPCALLS + ".onVehicleConstraintImpulse"));
        assertEquals(
                methods(raw).get("loadLibrary(Ljava/lang/String;)V").calls(),
                m.get("loadLibrary(Ljava/lang/String;)V").calls(),
                "the copy loads the library itself");
    }

    @Test
    void isolatedLoaderDefinesOnlyBulletAndSharesTheRest() throws Exception {
        new BulletRecordPatch().transform(game("zombie/core/physics/Bullet"));
        byte[] iso = BulletRecorder.preparedBytes();
        ClassLoader parent = getClass().getClassLoader();
        BulletRecorder.IsolatedLoader loader = new BulletRecorder.IsolatedLoader(parent, iso);
        Class<?> copy = Class.forName("zombie.core.physics.Bullet", false, loader);
        assertSame(loader, copy.getClassLoader());
        assertNotSame(Class.forName("zombie.core.physics.Bullet", false, parent), copy);
        assertSame(
                copy, Class.forName("zombie.core.physics.Bullet", false, loader), "defined once");
        assertSame(
                Class.forName("zombie.debug.DebugLog", false, parent),
                Class.forName("zombie.debug.DebugLog", false, loader),
                "the library's FindClass reaches the game's DebugLog");
        assertSame(
                BulletRecordUpcalls.class, Class.forName(UPCALLS.replace('/', '.'), false, loader));
        assertTrue(Modifier.isNative(copy.getDeclaredMethod("initPZBullet").getModifiers()));
    }

    @Test
    void upcallAdviceWeavesIntoDebugLogAndSkeletonBone() throws Exception {
        BulletUpcallLogPatch log = BulletUpcallLogPatch.debugLog();
        byte[] logOut = log.transform(game("zombie/debug/DebugLog"));
        assertNull(log.lastError(), () -> "weave failed: " + log.lastError());
        assertResolvable("zombie.debug.DebugLog", logOut);
        Set<String> calls = methods(logOut).get(BulletApi.NATIVE_LOG.key()).calls();
        assertTrue(
                calls.contains(RECORDER + ".adviceEnter")
                        && calls.contains(RECORDER + ".adviceExit"),
                calls.toString());

        BulletUpcallLogPatch bone = BulletUpcallLogPatch.skeletonBone();
        byte[] boneOut = bone.transform(game("zombie/core/skinnedmodel/model/SkeletonBone"));
        assertNull(bone.lastError(), () -> "weave failed: " + bone.lastError());
        assertResolvable("zombie.core.skinnedmodel.model.SkeletonBone", boneOut);
        Map<String, MethodInfo> m = methods(boneOut);
        assertTrue(
                m.get(BulletApi.GET_BONE_NAME.key()).calls().contains(RECORDER + ".adviceEnter"));
        assertTrue(
                m.get(BulletApi.GET_BONE_ORDINAL.key()).calls().contains(RECORDER + ".adviceExit"));
    }

    @Test
    void brokenInputFailsSoftAndReturnsTheBytesUnchanged() {
        byte[] garbage = {(byte) 0xCA, (byte) 0xFE, 0, 1, 2, 3};
        BulletRecordPatch patch = new BulletRecordPatch();
        assertArrayEquals(garbage, patch.transform(garbage));
        assertNotNull(patch.lastError());
        BulletUpcallLogPatch log = BulletUpcallLogPatch.debugLog();
        assertArrayEquals(garbage, log.transform(garbage));
        assertNotNull(log.lastError());
    }

    @Test
    void adviceIsInertWithoutARecorder() {
        // the game's own Java calls to nativeLog/getBoneName outside any recorded native call
        assertTrue(
                !BulletRecorder.adviceEnter(
                        BulletRecorder.NATIVE_LOG, new Object[] {"a", "b", "c"}));
    }

    @Test
    void pathTokens() {
        String p = BulletRecorder.resolvePath("/tmp/bullet-{pid}-{time}.pzbt.gz");
        assertTrue(p.contains("-" + ProcessHandle.current().pid() + "-"), p);
        assertTrue(p.matches("/tmp/bullet-\\d+-\\d{8}-\\d{6}\\.pzbt\\.gz"), p);
        Map<String, String> unused = new HashMap<>();
        assertTrue(unused.isEmpty());
    }
}
