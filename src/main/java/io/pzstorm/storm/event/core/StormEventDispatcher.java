package io.pzstorm.storm.event.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.google.common.collect.Sets;
import io.pzstorm.storm.event.lua.OnClientCommandEvent;
import io.pzstorm.storm.event.zomboid.OnPacketReceivedEvent;
import io.pzstorm.storm.event.zomboid.OnTriggerLuaEvent;
import io.pzstorm.storm.http.GameHttpEndpoint;
import io.pzstorm.storm.http.GameHttpEndpointDispatcher;
import io.pzstorm.storm.http.HttpEndpoint;
import io.pzstorm.storm.http.HttpEndpointDispatcher;
import io.pzstorm.storm.http.HttpRequestEvent;
import io.pzstorm.storm.metrics.EventDispatchMetrics;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * This class is responsible for registering event handlers and dispatching {@link ZomboidEvent}
 * instances.
 *
 * <p>To register an event handler call one of the following methods:
 *
 * <ul>
 *   <li>{@link #registerEventHandler(Object)} - when subscribed methods are instance methods.
 *   <li>{@link #registerEventHandler(Class)} - when subscribed methods are static methods.
 * </ul>
 *
 * All methods in registered event handlers annotated with {@link SubscribeEvent} will be called by
 * {@link #dispatchEvent(ZomboidEvent)} method when an appropriate event is created by installed
 * {@code StormHook}. Each subscribed method has to have exactly one parameter that matches the type
 * of event it wants to subscribe to. For example if a method wanted to subscribe to {@code
 * OnRenderEvent} it would define itself in one of two ways depending on the handler registration
 * method used: <br>
 * <br>
 *
 * <pre>
 *     // handler must be registered as a class
 *     public static void handleRenderEvent(OnRenderEvent event) {
 *         ...
 *     }
 *     // handler must be registered as an instance
 *     public void handleRenderEvent(OnRenderEvent event) {
 *         ...
 *     }
 * </pre>
 *
 * Do not mix static and instance subscribed methods. A registered handler has to have all
 * subscribed methods declared as either static or instance methods depending on the method used to
 * register the handler.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class StormEventDispatcher {

    /**
     * Internal registry that maps {@link ZomboidEvent} classes to handler methods. These methods
     * are then invoked when dispatching matching events. Event registration happens on-demand which
     * means that registry will only contain an event entry if at least one registered handler
     * contains at least one method that subscribe to that event.
     */
    private static final Map<Class<? extends ZomboidEvent>, Set<EventHandlerMethod>>
            DISPATCH_REGISTRY = new HashMap<>();

    /**
     * True when at least one registered handler makes the per-trigger Lua event bridge worth
     * running: an external (non-{@link StormEventHandler}) subscriber to {@link OnTriggerLuaEvent},
     * or a subscriber to any typed {@link LuaEvent}. Recomputed on every handler registration;
     * consulted once per {@code LuaEventManager.triggerEvent} by {@code TriggerEventAdvice}, so a
     * volatile read is the entire per-trigger cost when no such handlers exist.
     */
    private static volatile boolean luaEventInterest;

    /**
     * Internally register given method for specified event handler.
     *
     * @param method {@code Method} to register with event handler.
     * @param handler event handler to register along with {@code Method}. It can be either an
     *     instance of an object or a {@code Class} that represents the handler.
     * @throws IllegalArgumentException if the handler parameter is {@code null} and given method is
     *     <i>not</i> declared as {@code static}, handler is <i>not</i> {@code null} and given
     *     method is declared as {@code static}, if the given {@code Method} does not have exactly
     *     one argument or the argument is not an instance of {@link ZomboidEvent}.
     */
    @SuppressWarnings("unchecked")
    private static void registerEventHandlerMethod(Method method, @Nullable Object handler) {
        if (method.isAnnotationPresent(SubscribeEvent.class)) {
            LOGGER.debug(
                    "Registering event handler: {}:{}",
                    method.getDeclaringClass().getCanonicalName(),
                    method.getName());
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1) {
                Class<?> cEventClass = parameters[0];
                if (ZomboidEvent.class.isAssignableFrom(cEventClass)) {
                    if (handler == null) {
                        if (!Modifier.isStatic(method.getModifiers())) {
                            throw new IllegalArgumentException(
                                    "Tried to register INSTANCE event handler method "
                                            + "by passing null event handler. Either make the method STATIC or use a "
                                            + "different context to register the handler. See StormEventDispatcher class "
                                            + "documentation for more information. Method: "
                                            + method.getName());
                        }
                    } else if (Modifier.isStatic(method.getModifiers())) {
                        throw new IllegalArgumentException(
                                "Tried to register STATIC event handler method "
                                        + "by passing an instance of handler class. Either remove the STATIC modifier "
                                        + "or use a different context to register the handler. See StormEventDispatcher "
                                        + "class documentation for more information. Method: "
                                        + method.getName());
                    }
                    Class<? extends ZomboidEvent> eventClass =
                            (Class<? extends ZomboidEvent>) cEventClass;
                    EventHandlerMethod eventHandlerMethod = new EventHandlerMethod(method, handler);

                    Set<EventHandlerMethod> handlerMethods = DISPATCH_REGISTRY.get(eventClass);
                    if (handlerMethods == null) {
                        DISPATCH_REGISTRY.put(eventClass, Sets.newHashSet(eventHandlerMethod));
                    } else handlerMethods.add(eventHandlerMethod);
                    recomputeLuaEventInterest();
                } else {
                    String className =
                            handler instanceof Class
                                    ? ((Class<?>) handler).getName()
                                    : (handler != null ? handler.getClass().getName() : "null");

                    String text =
                            "Invalid arguments for method %s(%s). Expected ZomboidEvent but found %s";
                    throw new IllegalArgumentException(
                            String.format(
                                    text, method.getName(), className, cEventClass.getName()));
                }
            } else {
                String className =
                        handler instanceof Class
                                ? ((Class<?>) handler).getName()
                                : (handler != null ? handler.getClass().getName() : "null");

                String text =
                        "Invalid arguments for method %s(%s). Expected exactly one argument but found %d";
                throw new IllegalArgumentException(
                        String.format(text, method.getName(), className, parameters.length));
            }
        }

        if (method.isAnnotationPresent(OnClientCommand.class)) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1
                    || !ClientCommandEvent.class.isAssignableFrom(parameters[0])) {
                throw new IllegalArgumentException(
                        "@OnClientCommand method "
                                + method.getName()
                                + " must have exactly one parameter extending ClientCommandEvent");
            }
            ClientCommandDispatcher.registerHandler(method, handler);
        }

        if (method.isAnnotationPresent(OnPacketReceived.class)) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1
                    || !OnPacketReceivedEvent.class.isAssignableFrom(parameters[0])) {
                throw new IllegalArgumentException(
                        "@OnPacketReceived method "
                                + method.getName()
                                + " must have exactly one OnPacketReceivedEvent parameter");
            }
            PacketEventDispatcher.registerHandler(method, handler);
        }

        if (method.isAnnotationPresent(HttpEndpoint.class)) {
            validateHttpHandlerSignature(method, handler, "@HttpEndpoint");
            HttpEndpointDispatcher.registerHandler(method, handler);
        }

        if (method.isAnnotationPresent(GameHttpEndpoint.class)) {
            validateHttpHandlerSignature(method, handler, "@GameHttpEndpoint");
            GameHttpEndpointDispatcher.registerHandler(method, handler);
        }
    }

    /**
     * Shared signature validation for {@link HttpEndpoint} and {@link GameHttpEndpoint} handler
     * methods; both annotations accept the same shapes.
     */
    private static void validateHttpHandlerSignature(
            Method method, @Nullable Object handler, String annotationName) {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length < 1
                || parameters.length > 2
                || !HttpRequestEvent.class.isAssignableFrom(parameters[0])) {
            throw new IllegalArgumentException(
                    annotationName
                            + " method "
                            + method.getName()
                            + " must have signature (HttpRequestEvent) or (HttpRequestEvent,"
                            + " BodyType)");
        }
        if (parameters.length == 2 && HttpRequestEvent.class.isAssignableFrom(parameters[1])) {
            throw new IllegalArgumentException(
                    annotationName
                            + " method "
                            + method.getName()
                            + " second parameter must be the JSON body type, not"
                            + " HttpRequestEvent");
        }
        if (method.getReturnType() != void.class) {
            throw new IllegalArgumentException(
                    annotationName + " method " + method.getName() + " must return void");
        }
        if (handler == null && !Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    annotationName
                            + " method "
                            + method.getName()
                            + " must be STATIC when registered via a Class");
        }
        if (handler != null && Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    annotationName
                            + " method "
                            + method.getName()
                            + " must NOT be STATIC when registered via an instance");
        }
    }

    /**
     * Register all <b>static</b> methods subscribed with {@link SubscribeEvent} annotation in the
     * given {@code Class} to dispatch registry. The registered methods will then be called by
     * dispatched whenever an event they are subscribed to fires. Note that the methods have to be
     * properly defined see {@link StormEventDispatcher} class documentation for more information.
     *
     * @param handlerClass {@code Class} of the event handler to register.
     * @throws IllegalArgumentException if any subscribing method declared in handler is <i>not</i>
     *     declared as {@code static}, if the any subscribing method does not have exactly one
     *     argument or the argument is not an instance of {@link ZomboidEvent}.
     * @see #registerEventHandler(Object)
     */
    public static void registerEventHandler(Class<?> handlerClass) {
        LOGGER.debug("Registering event handler for class {}", handlerClass.getName());
        for (Method method : handlerClass.getMethods()) {
            registerEventHandlerMethod(method, null);
        }
    }

    /**
     * Register all <b>instance</b> methods subscribed with {@link SubscribeEvent} annotation in the
     * given object instance to dispatch registry. The registered methods will then be called by
     * dispatched whenever an event they are subscribed to fires. Note that the methods have to be
     * properly defined see {@link StormEventDispatcher} class documentation for more information.
     *
     * @param handler instance of the event handler to register.
     * @throws IllegalArgumentException if any subscribing method declared in handler is declared as
     *     {@code static}, if the any subscribing method does not have exactly one argument or the
     *     argument is not an instance of {@link ZomboidEvent}.
     * @see #registerEventHandler(Class)
     */
    public static void registerEventHandler(Object handler) {
        LOGGER.debug(
                "Registering event handler for instance of class {}", handler.getClass().getName());
        for (Method method : handler.getClass().getMethods()) {
            registerEventHandlerMethod(method, handler);
        }
    }

    /**
     * Dispatch the given event to all methods registered in dispatch registry. This is an internal
     * method <b>only</b> called by {@code StormHook} implementations installed in game code.
     *
     * @param event {@link ZomboidEvent} to dispatch.
     */
    /** Returns whether at least one handler is registered for the given event class. */
    public static boolean hasHandlers(Class<? extends ZomboidEvent> eventClass) {
        Set<EventHandlerMethod> handlerMethods = DISPATCH_REGISTRY.get(eventClass);
        return handlerMethods != null && !handlerMethods.isEmpty();
    }

    /**
     * Returns whether the per-trigger Lua event bridge ({@code TriggerEventAdvice} → {@link
     * OnTriggerLuaEvent} → {@code StormEventHandler.handleLuaEventTrigger}) has any possible
     * consumer. When false, {@code TriggerEventAdvice} skips the bridge entirely — no event lookup,
     * no argument copy, no allocation, no reflective dispatch — which matters because the bridge
     * otherwise runs for every {@code triggerEvent} call including per-frame and per-zombie events.
     * {@code @OnClientCommand} handlers live outside {@code DISPATCH_REGISTRY} but are fed through
     * the same bridge (via {@code OnClientCommandEvent}), so they count as interest.
     */
    public static boolean isLuaEventBridgeNeeded() {
        return luaEventInterest || ClientCommandDispatcher.hasHandlers();
    }

    private static void recomputeLuaEventInterest() {
        for (Map.Entry<Class<? extends ZomboidEvent>, Set<EventHandlerMethod>> entry :
                DISPATCH_REGISTRY.entrySet()) {
            Class<? extends ZomboidEvent> eventClass = entry.getKey();
            if (eventClass == OnTriggerLuaEvent.class) {
                for (EventHandlerMethod handlerMethod : entry.getValue()) {
                    if (handlerMethod.method.getDeclaringClass() != StormEventHandler.class) {
                        luaEventInterest = true;
                        return;
                    }
                }
            } else if (LuaEvent.class.isAssignableFrom(eventClass) && !entry.getValue().isEmpty()) {
                luaEventInterest = true;
                return;
            }
        }
        luaEventInterest = false;
    }

    public static void dispatchEvent(ZomboidEvent event) {
        String eventName = event.getClass().getSimpleName();
        Set<EventHandlerMethod> handlerMethods = DISPATCH_REGISTRY.get(event.getClass());
        if (handlerMethods != null) {
            EventDispatchMetrics.recordDispatch(eventName);
            long t0 = System.nanoTime();
            for (EventHandlerMethod method : handlerMethods) {
                LOGGER.trace("Dispatching event {}", event.getClass().getName());
                try {
                    method.invoke(event);
                } catch (RuntimeException e) {
                    EventDispatchMetrics.recordError(eventName);
                    LOGGER.error(
                            "Event handler {}.{} threw on {}",
                            method.method.getDeclaringClass().getName(),
                            method.method.getName(),
                            event.getClass().getName(),
                            e);
                }
            }
            EventDispatchMetrics.recordHandlerNanos(eventName, System.nanoTime() - t0);
        }

        if (event instanceof OnClientCommandEvent clientCommandEvent) {
            ClientCommandDispatcher.dispatch(clientCommandEvent);
        }

        if (event instanceof OnPacketReceivedEvent packetEvent) {
            PacketEventDispatcher.dispatch(packetEvent);
        }
    }

    private static class EventHandlerMethod {
        private final Method method;
        private final @Nullable Object handler;

        private EventHandlerMethod(Method method, @Nullable Object handler) {
            this.method = method;
            this.handler = handler;
        }

        private void invoke(ZomboidEvent event) {
            try {
                method.invoke(handler, event);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
