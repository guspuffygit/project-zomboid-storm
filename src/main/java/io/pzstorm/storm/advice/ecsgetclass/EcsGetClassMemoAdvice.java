package io.pzstorm.storm.advice.ecsgetclass;

import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import zombie.characters.ecs.ECSComponent;

/**
 * Advice for the static {@code zombie.characters.ecs.ECSComponent.getECSClass(Class)}.
 *
 * <p>The vanilla body walks {@code Class.getSuperclass()} up to {@code ECSComponent} on every call.
 * The result is a pure function of the argument, but the method sits on the component
 * <em>query</em> path ({@code ECSEntity.getECSComponent} / {@code tryGetECSComponent} / {@code
 * hasECSComponent}), which runs per character per tick — live-client sampling in a dense Louisville
 * scene attributed 11.9% of MainThread to this walk when memoized via {@code
 * ConcurrentHashMap.get}, and the raw walk would be far more.
 *
 * <p>Fast path: every known {@code ECSComponent} subclass in vanilla extends {@code ECSComponent}
 * directly, so {@code clazz.getSuperclass() == ECSComponent.class} → return {@code clazz}, no map
 * operation, no synchronization. This shortcut eliminates the {@code ConcurrentHashMap.get} cost
 * that dominated the query path.
 *
 * <p>Slow-path fallback (a modded class that goes {@code MyComponent extends AIComponent extends
 * ECSComponent}) still walks once and memoizes into a {@link ConcurrentHashMap}. Keys are strongly
 * held Class objects from the game's own loader — no unload/leak concern.
 */
public class EcsGetClassMemoAdvice {

    public static final ConcurrentHashMap<Class<?>, Class<?>> CACHE = new ConcurrentHashMap<>();

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Class<?> onEnter(@Advice.Argument(0) Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        if (clazz.getSuperclass() == ECSComponent.class) {
            return clazz;
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
