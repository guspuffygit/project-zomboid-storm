package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.shader.NativeShaderTestObjects;
import io.pzstorm.storm.shader.StormModelShaderCache;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.util.CheckClassAdapter;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;

/** Native method bytes and identity fields are exercised without a GL context or model assets. */
class ModelShaderCachePatchTest implements UnitTest {
    private static final String MODEL = "zombie.core.skinnedmodel.model.Model";
    private static final String MODEL_MANAGER = "zombie.core.skinnedmodel.ModelManager";
    private static final String RENDER_THREAD = "zombie.core.opengl.RenderThread";
    private static final String MANAGER = "zombie.core.skinnedmodel.shader.ShaderManager";
    private static final String HELPER = "io/pzstorm/storm/shader/StormModelShaderCache";
    private static final String CREATE = "CreateShader(Ljava/lang/String;)V";
    private static final String GET =
            "getOrCreateShader(Ljava/lang/String;ZZ)Lzombie/core/skinnedmodel/shader/Shader;";

    @AfterEach
    void clear() {
        StormModelShaderCache.clear();
    }

    @Test
    void patchesOnlyReviewedNativeMethodsAndRetainsColdBody() throws Exception {
        byte[] model = nativeBytes(MODEL);
        byte[] manager = nativeBytes(MANAGER);
        byte[] patchedModel = new ModelCreateShaderCachePatch().transform(model);
        byte[] patchedManager = new ShaderManagerWarmCachePatch().transform(manager);
        assertNotSame(model, patchedModel);
        assertNotSame(manager, patchedManager);
        Map<String, Map<String, Integer>> modelCalls = calls(patchedModel);
        Map<String, Map<String, Integer>> managerCalls = calls(patchedManager);
        assertEquals(1, count(modelCalls, CREATE, HELPER + ".find"));
        assertEquals(1, count(managerCalls, GET, HELPER + ".record"));
        assertEquals(
                count(calls(model), CREATE, "zombie/util/Lambda.invoke"),
                count(modelCalls, CREATE, "zombie/util/Lambda.invoke"));
        assertEquals(1, count(managerCalls, GET, "zombie/core/skinnedmodel/shader/Shader.<init>"));
        assertEquals(1, count(managerCalls, GET, "java/util/ArrayList.add"));
        assertOnlyMethodUsesHelper(modelCalls, CREATE);
        assertOnlyMethodUsesHelper(managerCalls, GET);
        structuralVerify(patchedModel);
        structuralVerify(patchedManager);
    }

    @Test
    void installedNativeIdentityContractIsFinalAndGettersReadOnlyTheirFields() throws Exception {
        assertTrue(Modifier.isFinal(Shader.class.getModifiers()));
        for (String field : new String[] {"name", "isStatic", "instanced", "shaderProgram"}) {
            assertTrue(
                    Modifier.isFinal(Shader.class.getDeclaredField(field).getModifiers()), field);
        }
        assertTrue(
                Modifier.isFinal(ShaderManager.class.getDeclaredField("instance").getModifiers()));
        assertTrue(
                Modifier.isFinal(ShaderManager.class.getDeclaredField("shaders").getModifiers()));
        Map<String, Map<String, Integer>> getterCalls = calls(nativeBytes(Shader.class.getName()));
        assertTrue(getterCalls.get("getName()Ljava/lang/String;").isEmpty());
        assertTrue(getterCalls.get("isStatic()Z").isEmpty());
        assertTrue(getterCalls.get("isInstanced()Z").isEmpty());
    }

    @Test
    void nativeColdPathQueuesAndWarmPathUsesExactSameShaderWithoutQueue() throws Exception {
        Shader shader = NativeShaderTestObjects.shader("basicEffect", false, false);
        withNativeManagerShader(
                shader,
                () -> {
                    Facade nativeModel = facade(false);
                    Facade patchedModel = facade(true);
                    nativeModel.create("basicEffect");
                    patchedModel.create("basicEffect");
                    assertSame(shader, nativeModel.effect());
                    assertSame(shader, patchedModel.effect());
                    assertEquals(1, nativeModel.queueCount());
                    assertEquals(
                            1,
                            patchedModel.queueCount(),
                            "a cold cache must invoke the native queue");

                    StormModelShaderCache.record(
                            ShaderManager.instance, "basicEffect", false, false, shader);
                    nativeModel.create("basicEffect");
                    patchedModel.create(new String("basicEffect"));
                    assertSame(nativeModel.effect(), patchedModel.effect());
                    assertEquals(2, nativeModel.queueCount());
                    assertEquals(
                            1,
                            patchedModel.queueCount(),
                            "warm hit must bypass native queue submission");
                });
    }

    @Test
    void noOpenGLEarlyReturnPreservesExistingEffectEvenWhenWarm() throws Exception {
        Shader cached = NativeShaderTestObjects.shader("basicEffect", false, false);
        Shader previous = NativeShaderTestObjects.shader("previous", false, false);
        StormModelShaderCache.record(ShaderManager.instance, "basicEffect", false, false, cached);
        for (boolean patched : new boolean[] {false, true}) {
            Facade model = facade(patched);
            model.setEffect(previous);
            model.noOpenGL(true);
            model.create("basicEffect");
            assertSame(previous, model.effect());
            assertEquals(0, model.queueCount());
        }
    }

    @Test
    void warmCacheRetainsCaseSensitiveNativeFailureAndStaticVariantFallback() throws Exception {
        Shader shader = NativeShaderTestObjects.shader("basicEffect", false, false);
        withNativeManagerShader(
                shader,
                () -> {
                    StormModelShaderCache.record(
                            ShaderManager.instance, "basicEffect", false, false, shader);
                    for (boolean patched : new boolean[] {false, true}) {
                        Facade model = facade(patched);
                        InvocationTargetException failure =
                                assertThrows(
                                        InvocationTargetException.class,
                                        () -> model.create("BasicEffect"));
                        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                        assertEquals(
                                "shader filenames are case-sensitive",
                                failure.getCause().getMessage());
                        assertNull(model.effect());
                        assertEquals(1, model.queueCount());
                    }
                    Shader staticShader =
                            NativeShaderTestObjects.shader("basicEffect", true, false);
                    withNativeManagerShader(
                            staticShader,
                            () -> {
                                Facade model = facade(true);
                                model.isStatic(true);
                                model.create("basicEffect");
                                assertSame(staticShader, model.effect());
                                assertEquals(
                                        1,
                                        model.queueCount(),
                                        "wrong static variant must remain native");
                            });
                });
    }

    @Test
    void unsupportedMethodDescriptorLeavesOriginalBytes() throws Exception {
        byte[] fixture = nativeModelFixture(nativeBytes(MODEL));
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(fixture)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                return super.visitMethod(
                                        access,
                                        name.equals("CreateShader") ? "renamedCreateShader" : name,
                                        descriptor,
                                        signature,
                                        exceptions);
                            }
                        },
                        0);
        byte[] unsupported = writer.toByteArray();
        assertSame(unsupported, new ModelCreateShaderCachePatch().transform(unsupported));
    }

    private Facade facade(boolean patched) throws Exception {
        byte[] model = nativeModelFixture(nativeBytes(MODEL));
        if (patched) model = new ModelCreateShaderCachePatch().transform(model);
        ClassLoader loader =
                new ByteArrayClassLoader.ChildFirst(
                        getClass().getClassLoader(),
                        Map.of(
                                MODEL,
                                model,
                                MODEL_MANAGER,
                                noOpenGLFacade(),
                                RENDER_THREAD,
                                renderQueueFacade()),
                        ByteArrayClassLoader.PersistenceHandler.MANIFEST);
        return new Facade(loader);
    }

    private static byte[] nativeModelFixture(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes)
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
                                emptyConstructor(writer);
                            }

                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                return name.equals("effect") || name.equals("isStatic")
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
                                return (name + descriptor).equals(CREATE)
                                                || name.startsWith("lambda$CreateShader$")
                                        ? writer.visitMethod(
                                                access, name, descriptor, signature, exceptions)
                                        : null;
                            }

                            @Override
                            public void visitEnd() {
                                writer.visitEnd();
                            }
                        },
                        ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }

    private static byte[] noOpenGLFacade() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                MODEL_MANAGER.replace('.', '/'),
                null,
                "java/lang/Object",
                null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "noOpenGL", "Z", null, null)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] renderQueueFacade() {
        String owner = RENDER_THREAD.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "queueCount", "I", null, null)
                .visitEnd();
        MethodVisitor method =
                writer.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "invokeOnRenderContext",
                        "(Ljava/lang/Runnable;)V",
                        null,
                        null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, "queueCount", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IADD);
        method.visitFieldInsn(Opcodes.PUTSTATIC, owner, "queueCount", "I");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emptyConstructor(ClassWriter writer) {
        MethodVisitor constructor =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
    }

    @SuppressWarnings("unchecked")
    private static void withNativeManagerShader(Shader shader, CheckedRunnable action)
            throws Exception {
        Field field = ShaderManager.class.getDeclaredField("shaders");
        field.setAccessible(true);
        ArrayList<Shader> list = (ArrayList<Shader>) field.get(ShaderManager.instance);
        ArrayList<Shader> previous = new ArrayList<>(list);
        try {
            list.clear();
            list.add(shader);
            action.run();
        } finally {
            list.clear();
            list.addAll(previous);
        }
    }

    private byte[] nativeBytes(String name) throws Exception {
        try (InputStream input =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private static Map<String, Map<String, Integer>> calls(byte[] bytes) {
        Map<String, Map<String, Integer>> methods = new HashMap<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                Map<String, Integer> calls = new HashMap<>();
                                methods.put(name + descriptor, calls);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String name,
                                            String descriptor,
                                            boolean isInterface) {
                                        calls.merge(owner + "." + name, 1, Integer::sum);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static int count(
            Map<String, Map<String, Integer>> calls, String method, String target) {
        return calls.get(method).getOrDefault(target, 0);
    }

    private static void assertOnlyMethodUsesHelper(
            Map<String, Map<String, Integer>> calls, String permitted) {
        calls.forEach(
                (method, invocations) -> {
                    if (!method.equals(permitted)) {
                        assertTrue(
                                invocations.keySet().stream()
                                        .noneMatch(name -> name.startsWith(HELPER + ".")),
                                method);
                    }
                });
    }

    private static void structuralVerify(byte[] bytes) {
        // ASM 9.1 is the repository's verifier dependency. Normalize only the version header of
        // this verification copy; native instructions/frames are unchanged. Runtime facades below
        // retain the original game version and undergo the JVM's real bytecode verification.
        byte[] verificationCopy = bytes.clone();
        verificationCopy[4] = 0;
        verificationCopy[5] = 0;
        verificationCopy[6] = 0;
        verificationCopy[7] = 61;
        new org.objectweb.asm.ClassReader(verificationCopy)
                .accept(new CheckClassAdapter(new org.objectweb.asm.ClassWriter(0), false), 0);
    }

    private record Facade(ClassLoader loader, Object model, Method create) {
        Facade(ClassLoader loader) throws Exception {
            this(
                    loader,
                    loader.loadClass(MODEL).getConstructor().newInstance(),
                    loader.loadClass(MODEL).getMethod("CreateShader", String.class));
        }

        void create(String name) throws Exception {
            create.invoke(model, name);
        }

        Shader effect() throws Exception {
            return (Shader) model.getClass().getField("effect").get(model);
        }

        void setEffect(Shader value) throws Exception {
            model.getClass().getField("effect").set(model, value);
        }

        void isStatic(boolean value) throws Exception {
            model.getClass().getField("isStatic").setBoolean(model, value);
        }

        void noOpenGL(boolean value) throws Exception {
            loader.loadClass(MODEL_MANAGER).getField("noOpenGL").setBoolean(null, value);
        }

        int queueCount() throws Exception {
            return loader.loadClass(RENDER_THREAD).getField("queueCount").getInt(null);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
