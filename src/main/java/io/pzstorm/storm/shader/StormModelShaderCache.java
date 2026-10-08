package io.pzstorm.storm.shader;

import java.util.concurrent.ConcurrentHashMap;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;

/**
 * Bounded secondary index of successful native singleton lookups. It stores Shader objects, never
 * GL IDs or uniforms. In 42.21.0 the manager is append-only and Shader's identity fields are final;
 * ShaderProgram reload/destroy operates on the same object and remains entirely native.
 */
public final class StormModelShaderCache {
    static final int MAX_ENTRIES = 1024;
    private static final ConcurrentHashMap<Key, Shader> SHADERS = new ConcurrentHashMap<>();
    private static final Object RECORD_LOCK = new Object();

    private StormModelShaderCache() {}

    /** A miss leaves the caller on the native render-context path. Reads never acquire a lock. */
    public static Shader find(String name, boolean isStatic, boolean instanced) {
        if (name == null) return null;
        Shader shader = SHADERS.get(new Key(name, isStatic, instanced));
        return matches(shader, name, isStatic, instanced) ? shader : null;
    }

    /** Called only after getOrCreateShader returns normally, including successful native hits. */
    public static void record(
            ShaderManager manager,
            String name,
            boolean isStatic,
            boolean instanced,
            Shader shader) {
        if (manager != ShaderManager.instance
                || name == null
                || !matches(shader, name, isStatic, instanced)) return;
        Key key = new Key(name, isStatic, instanced);
        // Avoid serializing repeated manager hits once their tuple is recorded.
        if (SHADERS.containsKey(key)) return;
        synchronized (RECORD_LOCK) {
            if (SHADERS.size() < MAX_ENTRIES) SHADERS.putIfAbsent(key, shader);
        }
    }

    /**
     * For integrations that replace native manager contents. Clear on the render context after
     * their reset, with no shader lookup in flight. Native 42.21.0 itself never resets the manager.
     */
    public static void clear() {
        synchronized (RECORD_LOCK) {
            SHADERS.clear();
        }
    }

    static int cachedVariantCount() {
        return SHADERS.size();
    }

    private static boolean matches(
            Shader shader, String name, boolean isStatic, boolean instanced) {
        // Instancing is derived from a compiled attribute, not copied from the constructor
        // argument.
        return shader != null
                && name.equals(shader.getName())
                && isStatic == shader.isStatic()
                && instanced == shader.isInstanced();
    }

    private record Key(String name, boolean isStatic, boolean instanced) {}
}
