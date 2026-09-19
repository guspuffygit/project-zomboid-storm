package io.pzstorm.storm.bullet.trace;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Wraps a backend and its upcall handler so every call and upcall is written to a trace. */
public final class RecordingBackend {

    private RecordingBackend() {}

    public static BulletBackend wrap(BulletBackend delegate, TraceRecorder recorder) {
        return (BulletBackend)
                Proxy.newProxyInstance(
                        BulletBackend.class.getClassLoader(),
                        new Class<?>[] {BulletBackend.class},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return objectMethod(proxy, method, args, "RecordingBackend");
                            }
                            Object[] a = args == null ? new Object[0] : args;
                            Sig sig = BulletApi.of(method);
                            return recorder.call(sig, a, x -> invoke(method, delegate, x));
                        });
    }

    public static UpcallHandler wrap(UpcallHandler delegate, TraceRecorder recorder) {
        return (UpcallHandler)
                Proxy.newProxyInstance(
                        UpcallHandler.class.getClassLoader(),
                        new Class<?>[] {UpcallHandler.class},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return objectMethod(proxy, method, args, "RecordingUpcalls");
                            }
                            Object[] a = args == null ? new Object[0] : args;
                            Sig sig = BulletApi.of(method);
                            return recorder.upcall(sig, a, x -> invoke(method, delegate, x));
                        });
    }

    /**
     * A session whose native calls and upcalls are all recorded; the upcall handler installed on
     * the result is wrapped before it reaches the underlying session.
     */
    public static BackendSession wrap(BackendSession session, TraceRecorder recorder) {
        return new BackendSession(
                session.name(),
                wrap(session.bullet(), recorder),
                handler -> session.installUpcalls(wrap(handler, recorder)));
    }

    /** Reflective call that rethrows the target's own exception. */
    static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static Object objectMethod(Object proxy, Method method, Object[] args, String name) {
        return switch (method.getName()) {
            case "toString" -> name;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
}
