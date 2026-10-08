package io.pzstorm.storm.shader;

import java.lang.reflect.Field;
import sun.misc.Unsafe;
import zombie.core.skinnedmodel.shader.Shader;

/** Real native Shader objects with only final identity populated; no GL constructor is run. */
public final class NativeShaderTestObjects {
    private static final Unsafe UNSAFE = unsafe();

    private NativeShaderTestObjects() {}

    public static Shader shader(String name, boolean isStatic, boolean instanced) throws Exception {
        Shader shader = (Shader) UNSAFE.allocateInstance(Shader.class);
        set(shader, "name", name);
        set(shader, "isStatic", isStatic);
        set(shader, "instanced", instanced);
        // shaderProgram deliberately remains null: cache access must never inspect a GL program.
        return shader;
    }

    private static void set(Shader shader, String name, Object value) throws Exception {
        Field field = Shader.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(shader, value);
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
