package io.pzstorm.storm.bullet.trace;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The Java port as a backend: {@code io.pzstorm.storm.bullet.StormBullet} statics, with upcalls
 * routed by setting {@code io.pzstorm.storm.bullet.BulletUpcalls.target} to a proxy of its nested
 * {@code Target} interface. Everything is reflective so the harness compiles and runs before (and
 * independently of) the port.
 */
public final class JavaBackend {

    public static final String FACADE = "io.pzstorm.storm.bullet.StormBullet";
    public static final String UPCALLS = "io.pzstorm.storm.bullet.BulletUpcalls";

    private JavaBackend() {}

    /**
     * @throws ClassNotFoundException when the port's facade or upcall hook does not exist yet
     */
    public static BackendSession open(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> facade = Class.forName(FACADE, true, loader);
        Class<?> upcalls = Class.forName(UPCALLS, true, loader);
        Class<?> targetType = Class.forName(UPCALLS + "$Target", true, loader);
        Field target = upcalls.getField("target");
        BulletBackend bullet = StaticClassBackend.of(facade, true);
        return new BackendSession(
                "java:" + FACADE,
                bullet,
                handler -> {
                    try {
                        target.set(null, bridge(targetType, handler));
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("cannot set " + UPCALLS + ".target", e);
                    }
                });
    }

    /** A proxy of the port's Target interface forwarding to {@code handler} by method name. */
    static Object bridge(Class<?> targetType, UpcallHandler handler) {
        return Proxy.newProxyInstance(
                targetType.getClassLoader(),
                new Class<?>[] {targetType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return RecordingBackend.objectMethod(proxy, method, args, "UpcallBridge");
                    }
                    Method m =
                            UpcallHandler.class.getMethod(
                                    method.getName(), method.getParameterTypes());
                    return RecordingBackend.invoke(m, handler, args == null ? new Object[0] : args);
                });
    }
}
