package io.pzstorm.storm.bullet.trace;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link BulletBackend} whose every method forwards to the same-named static method (same
 * parameter types) of a class — the harness {@code zombie.core.physics.Bullet} stub (real natives)
 * or {@code io.pzstorm.storm.bullet.StormBullet} (the Java port). Exceptions thrown by the target
 * propagate unwrapped.
 */
public final class StaticClassBackend {

    private StaticClassBackend() {}

    /**
     * @param missingOk when true, backend methods the class lacks throw {@link
     *     UnsupportedOperationException} at call time instead of failing creation
     */
    public static BulletBackend of(Class<?> target, boolean missingOk) {
        Map<Method, Method> map = new HashMap<>();
        StringBuilder missing = new StringBuilder();
        for (Sig sig : BulletApi.NATIVES) {
            Method m;
            try {
                m = target.getDeclaredMethod(sig.name, sig.method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.append(' ').append(sig.key());
                continue;
            }
            if (!Modifier.isStatic(m.getModifiers())
                    || m.getReturnType() != sig.method.getReturnType()) {
                missing.append(' ').append(sig.key()).append("(bad modifiers/return)");
                continue;
            }
            m.setAccessible(true);
            map.put(sig.method, m);
        }
        if (!missingOk && !missing.isEmpty()) {
            throw new IllegalStateException(target.getName() + " lacks natives:" + missing);
        }
        return (BulletBackend)
                Proxy.newProxyInstance(
                        BulletBackend.class.getClassLoader(),
                        new Class<?>[] {BulletBackend.class},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "toString" ->
                                            "StaticClassBackend[" + target.getName() + "]";
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    default -> throw new UnsupportedOperationException();
                                };
                            }
                            Method m = map.get(method);
                            if (m == null) {
                                throw new UnsupportedOperationException(
                                        target.getName() + " has no " + method.getName());
                            }
                            try {
                                return m.invoke(null, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }
}
