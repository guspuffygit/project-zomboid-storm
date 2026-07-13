package io.pzstorm.storm.advice.ecsgetclass;

import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;

/**
 * Advice for the static {@code zombie.characters.ecs.ECSComponent.getECSClass(Class)}.
 *
 * <p>The vanilla body walks {@code Class.getSuperclass()} up to {@code ECSComponent} on every call.
 * The result is a pure function of the argument, but the method sits on the component
 * <em>query</em> path ({@code ECSEntity.getECSComponent} / {@code tryGetECSComponent} / {@code
 * hasECSComponent}), which runs per character per tick — live-client sampling in a dense Louisville
 * scene attributed 3.7% of MainThread to this walk.
 *
 * <p>The advice memoizes argument-to-result in a {@link ConcurrentHashMap}. The mapping can never
 * change for a given class, so the cache needs no invalidation. Class keys are strongly held; the
 * set of component classes is a small fixed population owned by the game's own class loader, so
 * there is no unload/leak concern.
 *
 * <p>Pattern: enter advice returns the cached value and skips the body on a hit; exit advice either
 * installs the cached value as the return or records the freshly computed result.
 */
public class EcsGetClassMemoAdvice {

    public static final ConcurrentHashMap<Class<?>, Class<?>> CACHE = new ConcurrentHashMap<>();

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Class<?> onEnter(@Advice.Argument(0) Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        return CACHE.get(clazz);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) Class<?> clazz,
            @Advice.Enter Class<?> cached,
            @Advice.Return(readOnly = false) Class<?> ret) {
        if (cached != null) {
            ret = cached;
            return;
        }
        if (clazz != null && ret != null) {
            CACHE.putIfAbsent(clazz, ret);
        }
    }
}
