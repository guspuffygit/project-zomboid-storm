package io.pzstorm.storm.patch.client;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.jar.asm.*;
import org.junit.jupiter.api.Test;

class VehiclePreviewSkinTransformsPatchTest implements UnitTest {
    private static final String TARGET = "zombie/vehicles/UI3DScene$VehicleRenderData";
    private static final String DATA = "zombie/vehicles/UI3DScene$VehicleModelRenderData";
    private static final String ANIM = "zombie/core/skinnedmodel/animation/AnimationPlayer";
    private static final String SKIN =
            "(Lzombie/core/skinnedmodel/model/SkinningData;)[Lorg/lwjgl/util/vector/Matrix4f;";

    @Test
    void weavesExactlyOnePoolReleaseIntoInstalledGameMethod() throws Exception {
        byte[] bytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream(TARGET + ".class")) {
            assertNotNull(stream);
            bytes = stream.readAllBytes();
        }
        byte[] transformed = new VehiclePreviewSkinTransformsPatch().transform(bytes);
        int[] releases = {0};
        new ClassReader(transformed)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String desc,
                                    String sig,
                                    String[] exceptions) {
                                if (!name.equals("initPartModel")) return null;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int op,
                                            String owner,
                                            String name,
                                            String desc,
                                            boolean itf) {
                                        if (owner.equals(DATA) && name.equals("release"))
                                            releases[0]++;
                                    }
                                };
                            }
                        },
                        0);
        assertEquals(1, releases[0]);
    }

    @Test
    void nullTransformsReleaseAllocationAndReadyTransformsResumeRendering() throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        ClassWriter data = type(DATA);
        MethodVisitor m = method(data, "release", "()V", false);
        m.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "released", "I");
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IADD);
        m.visitFieldInsn(Opcodes.PUTSTATIC, TARGET, "released", "I");
        end(m);
        classes.put(DATA.replace('/', '.'), data.toByteArray());
        ClassWriter anim = type(ANIM);
        anim.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ready", "Z", null, null)
                .visitEnd();
        m = method(anim, "getSkinTransforms", SKIN, true);
        Label ready = new Label();
        m.visitFieldInsn(Opcodes.GETSTATIC, ANIM, "ready", "Z");
        m.visitJumpInsn(Opcodes.IFNE, ready);
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitInsn(Opcodes.ARETURN);
        m.visitLabel(ready);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitTypeInsn(Opcodes.ANEWARRAY, "org/lwjgl/util/vector/Matrix4f");
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        classes.put(ANIM.replace('/', '.'), anim.toByteArray());
        ClassWriter target = type(TARGET);
        target.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "released", "I", null, null)
                .visitEnd();
        target.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "rendered", "I", null, null)
                .visitEnd();
        m =
                method(
                        target,
                        "initPartModel",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                        false);
        m.visitTypeInsn(Opcodes.NEW, DATA);
        m.visitInsn(Opcodes.DUP);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, DATA, "<init>", "()V", false);
        m.visitTypeInsn(Opcodes.CHECKCAST, DATA);
        m.visitVarInsn(Opcodes.ASTORE, 5);
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, ANIM, "getSkinTransforms", SKIN, false);
        m.visitInsn(Opcodes.ARRAYLENGTH);
        m.visitInsn(Opcodes.POP);
        m.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "rendered", "I");
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IADD);
        m.visitFieldInsn(Opcodes.PUTSTATIC, TARGET, "rendered", "I");
        end(m);
        classes.put(
                TARGET.replace('/', '.'),
                new VehiclePreviewSkinTransformsPatch().transform(target.toByteArray()));
        ClassLoader loader =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        synchronized (getClassLoadingLock(name)) {
                            if (!classes.containsKey(name)) return super.loadClass(name, resolve);
                            Class<?> loaded = findLoadedClass(name);
                            if (loaded == null) {
                                byte[] b = classes.get(name);
                                loaded = defineClass(name, b, 0, b.length);
                            }
                            if (resolve) resolveClass(loaded);
                            return loaded;
                        }
                    }
                };
        Class<?> fixture = loader.loadClass(TARGET.replace('/', '.'));
        Object instance = fixture.getConstructor().newInstance();
        var init =
                fixture.getMethod(
                        "initPartModel", Object.class, Object.class, Object.class, Object.class);
        for (int i = 0; i < 100; i++) init.invoke(instance, null, null, null, null);
        assertEquals(100, fixture.getField("released").getInt(null));
        assertEquals(0, fixture.getField("rendered").getInt(null));
        loader.loadClass(ANIM.replace('/', '.')).getField("ready").setBoolean(null, true);
        init.invoke(instance, null, null, null, null);
        assertEquals(1, fixture.getField("rendered").getInt(null));
        assertEquals(100, fixture.getField("released").getInt(null));
    }

    private static ClassWriter type(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor m = method(writer, "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        end(m);
        return writer;
    }

    private static MethodVisitor method(
            ClassWriter writer, String name, String desc, boolean isStatic) {
        MethodVisitor m =
                writer.visitMethod(
                        Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0),
                        name,
                        desc,
                        null,
                        null);
        m.visitCode();
        return m;
    }

    private static void end(MethodVisitor m) {
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
