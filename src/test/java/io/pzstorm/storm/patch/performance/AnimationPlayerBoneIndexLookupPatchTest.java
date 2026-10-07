package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.animation.StormBoneIndexLookup;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import zombie.core.skinnedmodel.model.SkinningData;

/**
 * Executes the real native getter/lookup bytecode without initializing the full game animation
 * pool.
 */
class AnimationPlayerBoneIndexLookupPatchTest implements UnitTest {
    private static final String TARGET = "zombie.core.skinnedmodel.animation.AnimationPlayer";
    private static final String OWNER = TARGET.replace('.', '/');
    private static final String LOOKUP = "(Ljava/lang/String;I)I";

    @Test
    void liveChangesAndEveryDefaultMatchNativeBytecode() throws Exception {
        Pair pair = pair();
        for (int defaultValue : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            pair.assertResult(null, "Bip01_Head", defaultValue);
            SkinningData data = data(null);
            pair.assertResult(data, "Bip01_Head", defaultValue);

            HashMap<String, Integer> indices = new HashMap<>();
            data.boneIndices = indices;
            pair.assertResult(data, "missing", defaultValue);
            for (int value : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
                indices.put("Bip01_Head", value);
                pair.assertResult(data, "Bip01_Head", defaultValue);
                pair.assertResult(data, new String("Bip01_Head"), defaultValue);
            }
            indices.put(null, 27);
            pair.assertResult(data, null, defaultValue);
            indices.remove("Bip01_Head");
            pair.assertResult(data, "Bip01_Head", defaultValue);
            indices.put("Bip01_Head", 19);
            pair.assertResult(data, "Bip01_Head", defaultValue);

            data.boneIndices = new HashMap<>(Map.of("Bip01_Head", 33));
            pair.assertResult(data, "Bip01_Head", defaultValue);
            pair.assertResult(
                    data(new HashMap<>(Map.of("Bip01_Head", 45))), "Bip01_Head", defaultValue);
        }
    }

    @Test
    void nullValuedBoneKeepsNativeUnboxingFailure() throws Exception {
        Pair pair = pair();
        HashMap<String, Integer> indices = new HashMap<>();
        indices.put("nullBone", null);
        pair.setData(data(indices));
        assertInstanceOf(NullPointerException.class, pair.nativeLookup.failure("nullBone", -1));
        assertInstanceOf(NullPointerException.class, pair.patchedLookup.failure("nullBone", -1));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void adviceFailureStillRunsNativeTypeCheck() throws Exception {
        Pair pair = pair();
        HashMap indices = new HashMap();
        indices.put("invalidBone", "not an Integer");
        pair.setData(data(indices));
        assertInstanceOf(ClassCastException.class, pair.nativeLookup.failure("invalidBone", -1));
        assertInstanceOf(ClassCastException.class, pair.patchedLookup.failure("invalidBone", -1));
    }

    @Test
    void customMapKeepsNativeCallOrderAndCanOverrideGet() throws Exception {
        Pair pair = pair();
        RecordingMap indices = new RecordingMap();
        indices.put("Bip01_Head", 2);
        pair.setData(data(indices));

        assertEquals(73, pair.nativeLookup.lookup("Bip01_Head", -1));
        assertEquals("contains,get,", indices.calls.toString());
        indices.calls.setLength(0);
        assertEquals(73, pair.patchedLookup.lookup("Bip01_Head", -1));
        assertEquals("contains,get,", indices.calls.toString());
        indices.calls.setLength(0);
        assertEquals(-1, pair.patchedLookup.lookup("missing", -1));
        assertEquals("contains,", indices.calls.toString());
    }

    @Test
    void helperIsAddedOnlyToReviewedNativeOverload() throws Exception {
        byte[] transformed = new AnimationPlayerBoneIndexLookupPatch().transform(nativeBytes());
        int[] calls = {0};
        new ClassReader(transformed)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String method,
                                            String desc,
                                            boolean isInterface) {
                                        if (owner.equals(
                                                StormBoneIndexLookup.class
                                                        .getName()
                                                        .replace('.', '/'))) {
                                            assertEquals("getSkinningBoneIndex", name);
                                            assertEquals(LOOKUP, descriptor);
                                            assertEquals("existingIndex", method);
                                            calls[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertEquals(1, calls[0]);
    }

    private Pair pair() throws Exception {
        byte[] fixture = nativeLookupFixture(nativeBytes());
        byte[] patched = new AnimationPlayerBoneIndexLookupPatch().transform(fixture);
        assertNotSame(fixture, patched, "supported native fixture must actually be patched");
        return new Pair(new Lookup(fixture), new Lookup(patched));
    }

    private byte[] nativeBytes() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(OWNER + ".class")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    private static SkinningData data(HashMap<String, Integer> indices) {
        return new SkinningData(
                new HashMap<>(), List.of(), List.of(), List.of(), List.of(), indices);
    }

    private static byte[] nativeLookupFixture(byte[] nativeBytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(nativeBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    String signature,
                                    String superName,
                                    String[] interfaces) {
                                writer.visit(
                                        version,
                                        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                                        name,
                                        null,
                                        "java/lang/Object",
                                        null);
                                MethodVisitor constructor =
                                        writer.visitMethod(
                                                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                                constructor.visitCode();
                                constructor.visitVarInsn(Opcodes.ALOAD, 0);
                                constructor.visitMethodInsn(
                                        Opcodes.INVOKESPECIAL,
                                        "java/lang/Object",
                                        "<init>",
                                        "()V",
                                        false);
                                constructor.visitInsn(Opcodes.RETURN);
                                constructor.visitMaxs(1, 1);
                                constructor.visitEnd();
                            }

                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                return name.equals("skinningData")
                                        ? writer.visitField(
                                                access, name, descriptor, signature, value)
                                        : null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (name.equals("getSkinningBoneIndex") && descriptor.equals(LOOKUP)
                                        || name.equals("getSkinningBoneIndices")
                                                && descriptor.equals("()Ljava/util/HashMap;")) {
                                    return writer.visitMethod(
                                            access, name, descriptor, signature, exceptions);
                                }
                                return null;
                            }

                            @Override
                            public void visitEnd() {
                                writer.visitEnd();
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }

    private static final class Lookup {
        final Object instance;
        final Method lookup;
        final Field data;

        Lookup(byte[] bytes) throws Exception {
            Class<?> type =
                    new ByteArrayClassLoader.ChildFirst(
                                    AnimationPlayerBoneIndexLookupPatchTest.class.getClassLoader(),
                                    Map.of(TARGET, bytes),
                                    ByteArrayClassLoader.PersistenceHandler.MANIFEST)
                            .loadClass(TARGET);
            instance = type.getConstructor().newInstance();
            lookup = type.getMethod("getSkinningBoneIndex", String.class, int.class);
            data = type.getDeclaredField("skinningData");
            data.setAccessible(true);
        }

        int lookup(String name, int defaultValue) throws Exception {
            return (int) lookup.invoke(instance, name, defaultValue);
        }

        Throwable failure(String name, int defaultValue) {
            return assertThrows(InvocationTargetException.class, () -> lookup(name, defaultValue))
                    .getCause();
        }
    }

    private record Pair(Lookup nativeLookup, Lookup patchedLookup) {
        void setData(SkinningData data) throws Exception {
            nativeLookup.data.set(nativeLookup.instance, data);
            patchedLookup.data.set(patchedLookup.instance, data);
        }

        void assertResult(SkinningData data, String name, int defaultValue) throws Exception {
            setData(data);
            assertEquals(
                    nativeLookup.lookup(name, defaultValue),
                    patchedLookup.lookup(name, defaultValue));
        }
    }

    private static final class RecordingMap extends HashMap<String, Integer> {
        final StringBuilder calls = new StringBuilder();

        @Override
        public boolean containsKey(Object key) {
            calls.append("contains,");
            return super.containsKey(key);
        }

        @Override
        public Integer get(Object key) {
            calls.append("get,");
            return 73;
        }
    }
}
