package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.advice.animsetlock.AnimSetLockAdvice;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies the four patches closing the SPVThread vs main-thread animset-load race actually land in
 * the bytecode: {@code ACC_SYNCHRONIZED} flags on the {@code ActionGroup}/{@code AssetManager} map
 * accessors, and {@link AnimSetLockAdvice#LOCK} acquisition woven into {@code
 * AnimationSet.GetAnimationSet}/{@code Reset} and {@code LuaManager$GlobalObject.refreshAnimSets}.
 */
class AnimAssetSyncPatchesTest implements UnitTest {

    private static final String LOCK_OWNER = AnimSetLockAdvice.class.getName().replace('.', '/');

    @Test
    void actionGroupMapAccessorsAreSynchronized() throws Exception {
        byte[] transformed =
                new ActionGroupSyncPatch()
                        .transform(readClass("zombie/characters/action/ActionGroup"));

        Map<String, List<Integer>> access = methodAccessFlags(transformed);
        assertSynchronized(access, "getActionGroup", true);
        assertSynchronized(access, "reloadAll", true);
        // The hot read-only accessors must stay lock-free.
        assertSynchronized(access, "getName", false);
        assertSynchronized(access, "findState", false);
    }

    @Test
    void assetManagerTableAccessorsAreSynchronized() throws Exception {
        byte[] transformed =
                new AssetManagerSyncPatch().transform(readClass("zombie/asset/AssetManager"));

        Map<String, List<Integer>> access = methodAccessFlags(transformed);
        assertSynchronized(access, "load", true);
        assertSynchronized(access, "get", true);
        assertSynchronized(access, "destroy", true);
        assertSynchronized(access, "removeUnreferenced", true);
        assertSynchronized(access, "enableUnload", true);
        // Async-loader callbacks and plain getters keep vanilla threading.
        assertSynchronized(access, "startLoading", false);
        assertSynchronized(access, "getAssetTable", false);
        assertSynchronized(access, "getOwner", false);
    }

    @Test
    void animationSetLoadPathsTakeTheSharedLock() throws Exception {
        byte[] transformed =
                new AnimationSetLockPatch()
                        .transform(
                                readClass(
                                        "zombie/core/skinnedmodel/advancedanimation/AnimationSet"));

        Set<String> locking = methodsReferencingLock(transformed);
        assertTrue(locking.contains("GetAnimationSet"), "GetAnimationSet must take the lock");
        assertTrue(locking.contains("Reset"), "Reset must take the lock");
        assertEquals(
                Set.of("GetAnimationSet", "Reset"),
                locking,
                "lock must not leak into other AnimationSet methods");
    }

    @Test
    void refreshAnimSetsTakesTheSharedLock() throws Exception {
        byte[] transformed =
                new RefreshAnimSetsLockPatch()
                        .transform(readClass("zombie/Lua/LuaManager$GlobalObject"));

        Set<String> locking = methodsReferencingLock(transformed);
        assertEquals(
                Set.of("refreshAnimSets"), locking, "exactly refreshAnimSets must take the lock");
    }

    private static byte[] readClass(String internalName) throws Exception {
        try (InputStream is =
                AnimAssetSyncPatchesTest.class
                        .getClassLoader()
                        .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + ".class must be on the test classpath");
            return is.readAllBytes();
        }
    }

    /** Asserts every overload of {@code method} matches the expected synchronization state. */
    private static void assertSynchronized(
            Map<String, List<Integer>> access, String method, boolean expected) {
        List<Integer> overloads = access.get(method);
        assertNotNull(overloads, "method " + method + " not found in transformed class");
        for (int flags : overloads) {
            assertEquals(
                    expected,
                    (flags & Opcodes.ACC_SYNCHRONIZED) != 0,
                    "method " + method + (expected ? " must" : " must NOT") + " be synchronized");
        }
    }

    private static Map<String, List<Integer>> methodAccessFlags(byte[] classBytes) {
        Map<String, List<Integer>> perMethod = new HashMap<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int accessFlags,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                perMethod
                                        .computeIfAbsent(name, k -> new ArrayList<>())
                                        .add(accessFlags);
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE);
        return perMethod;
    }

    private static Set<String> methodsReferencingLock(byte[] classBytes) {
        Set<String> methods = new HashSet<>();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int accessFlags,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode,
                                            String owner,
                                            String fieldName,
                                            String fieldDescriptor) {
                                        if (opcode == Opcodes.GETSTATIC
                                                && LOCK_OWNER.equals(owner)
                                                && "LOCK".equals(fieldName)) {
                                            methods.add(name);
                                        }
                                    }
                                };
                            }
                        },
                        0);
        return methods;
    }
}
