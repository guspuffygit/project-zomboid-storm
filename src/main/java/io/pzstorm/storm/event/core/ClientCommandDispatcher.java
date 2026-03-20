package io.pzstorm.storm.event.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.lua.OnClientCommandEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;

/**
 * Routes {@link OnClientCommandEvent} instances to typed {@link ClientCommandEvent} handlers based
 * on module and command matching.
 *
 * <p>When a handler method annotated with {@link OnClientCommand} is registered via {@link
 * StormEventDispatcher}, it is delegated here. The handler's parameter type must extend {@link
 * ClientCommandEvent} and be annotated with {@link ClientCommand} to specify the module and command
 * it handles.
 */
public class ClientCommandDispatcher {

    private static final Map<String, Class<? extends ClientCommandEvent>> EVENT_TYPE_REGISTRY =
            new HashMap<>();

    private static final Map<Class<? extends ClientCommandEvent>, Set<HandlerMethod>>
            HANDLER_REGISTRY = new HashMap<>();

    /** Clear all registries. Intended for use in tests only. */
    public static void reset() {
        EVENT_TYPE_REGISTRY.clear();
        HANDLER_REGISTRY.clear();
    }

    /**
     * Register a typed client command event class. Reads the {@link ClientCommand} annotation to
     * determine the module and command key.
     *
     * @param eventClass the event class to register.
     * @throws IllegalArgumentException if the class is not annotated with {@link ClientCommand}.
     */
    @SuppressWarnings("unchecked")
    static void registerEventType(Class<? extends ClientCommandEvent> eventClass) {
        ClientCommand annotation = eventClass.getAnnotation(ClientCommand.class);
        if (annotation == null) {
            throw new IllegalArgumentException(
                    "ClientCommandEvent class "
                            + eventClass.getName()
                            + " must be annotated with @ClientCommand");
        }
        String key = annotation.module() + ":" + annotation.command();
        Class<? extends ClientCommandEvent> existing = EVENT_TYPE_REGISTRY.get(key);
        if (existing != null && existing != eventClass) {
            LOGGER.warn(
                    "Duplicate client command event type for '{}': {} replaces {}",
                    key,
                    eventClass.getSimpleName(),
                    existing.getSimpleName());
        }
        EVENT_TYPE_REGISTRY.put(key, eventClass);
        LOGGER.debug(
                "Registered client command event type: {} -> {}", key, eventClass.getSimpleName());
    }

    /**
     * Register a handler method annotated with {@link OnClientCommand}. Called by {@link
     * StormEventDispatcher} during handler scanning.
     *
     * @param method the handler method.
     * @param handler the handler instance, or {@code null} for static methods.
     */
    @SuppressWarnings("unchecked")
    static void registerHandler(Method method, @Nullable Object handler) {
        Class<? extends ClientCommandEvent> eventClass =
                (Class<? extends ClientCommandEvent>) method.getParameterTypes()[0];

        if (!EVENT_TYPE_REGISTRY.containsValue(eventClass)) {
            registerEventType(eventClass);
        }

        HANDLER_REGISTRY
                .computeIfAbsent(eventClass, k -> new HashSet<>())
                .add(new HandlerMethod(method, handler));

        LOGGER.debug(
                "Registered @OnClientCommand handler: {}:{} -> {}",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                eventClass.getSimpleName());
    }

    /**
     * Dispatch an {@link OnClientCommandEvent} to any matching typed handlers.
     *
     * @param event the raw client command event.
     */
    public static void dispatch(OnClientCommandEvent event) {
        String key = event.getModule() + ":" + event.getCommand();
        Class<? extends ClientCommandEvent> eventClass = EVENT_TYPE_REGISTRY.get(key);

        if (eventClass == null) {
            return;
        }

        Set<HandlerMethod> handlers = HANDLER_REGISTRY.get(eventClass);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }

        ClientCommandEvent typedEvent;
        try {
            Constructor<? extends ClientCommandEvent> ctor =
                    eventClass.getConstructor(IsoPlayer.class, KahluaTable.class);
            typedEvent = ctor.newInstance(event.getPlayer(), event.getArgs().orElse(null));
        } catch (ReflectiveOperationException e) {
            LOGGER.error(
                    "Failed to construct typed client command event: {}", eventClass.getName(), e);
            return;
        }

        for (HandlerMethod handlerMethod : handlers) {
            LOGGER.trace("Dispatching client command event {} to handler", key);
            handlerMethod.invoke(typedEvent);
        }
    }

    private static class HandlerMethod {
        private final Method method;
        private final @Nullable Object handler;

        private HandlerMethod(Method method, @Nullable Object handler) {
            this.method = method;
            this.handler = handler;
        }

        private void invoke(ClientCommandEvent event) {
            try {
                method.invoke(handler, event);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
