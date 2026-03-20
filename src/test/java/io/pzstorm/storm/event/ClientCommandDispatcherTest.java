package io.pzstorm.storm.event;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.event.core.*;
import io.pzstorm.storm.event.lua.OnClientCommandEvent;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.krka.kahlua.vm.KahluaTable;

class ClientCommandDispatcherTest implements UnitTest {

    @BeforeEach
    void setUp() {
        ClientCommandDispatcher.reset();
    }

    @Test
    void shouldDispatchMatchingClientCommandToTypedHandler() {
        TypedHandler handler = new TypedHandler();
        StormEventDispatcher.registerEventHandler(handler);

        KahluaTable args = new StubKahluaTable(Map.of("name", "testValue", "count", 42.0));
        OnClientCommandEvent event = new OnClientCommandEvent("test", "doSomething", null, args);
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertEquals("testValue", handler.receivedEvent.getString("name"));
        Assertions.assertEquals(42.0, handler.receivedEvent.getDouble("count"));
    }

    @Test
    void shouldNotDispatchWhenModuleDoesNotMatch() {
        TypedHandler handler = new TypedHandler();
        StormEventDispatcher.registerEventHandler(handler);

        OnClientCommandEvent event =
                new OnClientCommandEvent("wrongModule", "doSomething", null, new StubKahluaTable());
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertFalse(handler.wasCalled);
    }

    @Test
    void shouldNotDispatchWhenCommandDoesNotMatch() {
        TypedHandler handler = new TypedHandler();
        StormEventDispatcher.registerEventHandler(handler);

        OnClientCommandEvent event =
                new OnClientCommandEvent("test", "wrongCommand", null, new StubKahluaTable());
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertFalse(handler.wasCalled);
    }

    @Test
    void shouldNotDispatchWhenNoHandlerRegistered() {
        // Dispatch without registering any handler - should not throw
        OnClientCommandEvent event =
                new OnClientCommandEvent("test", "doSomething", null, new StubKahluaTable());
        Assertions.assertDoesNotThrow(() -> StormEventDispatcher.dispatchEvent(event));
    }

    @Test
    void shouldRouteToCorrectHandlerWhenMultipleEventsRegistered() {
        TypedHandler handlerA = new TypedHandler();
        TypedHandlerB handlerB = new TypedHandlerB();
        StormEventDispatcher.registerEventHandler(handlerA);
        StormEventDispatcher.registerEventHandler(handlerB);

        OnClientCommandEvent eventA =
                new OnClientCommandEvent("test", "doSomething", null, new StubKahluaTable());
        StormEventDispatcher.dispatchEvent(eventA);

        Assertions.assertTrue(handlerA.wasCalled);
        Assertions.assertFalse(handlerB.wasCalled);

        handlerA.wasCalled = false;

        OnClientCommandEvent eventB =
                new OnClientCommandEvent("test", "otherAction", null, new StubKahluaTable());
        StormEventDispatcher.dispatchEvent(eventB);

        Assertions.assertFalse(handlerA.wasCalled);
        Assertions.assertTrue(handlerB.wasCalled);
    }

    @Test
    void shouldPassArgsToTypedEvent() {
        TypedHandler handler = new TypedHandler();
        StormEventDispatcher.registerEventHandler(handler);

        KahluaTable args = new StubKahluaTable(Map.of("x", 10.0, "y", 20.0, "label", "myLabel"));
        OnClientCommandEvent event = new OnClientCommandEvent("test", "doSomething", null, args);
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertEquals(10.0, handler.receivedEvent.getDouble("x"));
        Assertions.assertEquals(20.0, handler.receivedEvent.getDouble("y"));
        Assertions.assertEquals("myLabel", handler.receivedEvent.getString("label"));
    }

    @Test
    void shouldHandleNullArgs() {
        TypedHandler handler = new TypedHandler();
        StormEventDispatcher.registerEventHandler(handler);

        // null args - ClientCommandEvent wraps with LuaManager.platform.newTable(),
        // but since LuaManager isn't initialized in unit tests, this will throw.
        // The dispatch should handle the construction error gracefully.
        OnClientCommandEvent event = new OnClientCommandEvent("test", "doSomething", null, null);
        Assertions.assertDoesNotThrow(() -> StormEventDispatcher.dispatchEvent(event));
    }

    @Test
    void shouldThrowWhenRegistering_OnClientCommand_WithWrongParamType() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        StormEventDispatcher.registerEventHandler(
                                new Object() {
                                    @OnClientCommand
                                    public void handle(String notAnEvent) {}
                                }));
    }

    @Test
    void shouldThrowWhenRegistering_OnClientCommand_WithNoParams() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        StormEventDispatcher.registerEventHandler(
                                new Object() {
                                    @OnClientCommand
                                    public void handle() {}
                                }));
    }

    @Test
    void shouldThrowWhenRegistering_OnClientCommand_WithTooManyParams() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        StormEventDispatcher.registerEventHandler(
                                new Object() {
                                    @OnClientCommand
                                    public void handle(
                                            StubClientCommandEvent a, StubClientCommandEvent b) {}
                                }));
    }

    @Test
    void shouldThrowWhenEventClassMissingClientCommandAnnotation() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () ->
                        StormEventDispatcher.registerEventHandler(
                                new Object() {
                                    @OnClientCommand
                                    public void handle(StubClientCommandEventNoAnnotation event) {}
                                }));
    }

    @Test
    void shouldStillDispatchRawOnClientCommandEventToSubscribeEventHandlers() {
        RawHandler rawHandler = new RawHandler();
        TypedHandler typedHandler = new TypedHandler();
        StormEventDispatcher.registerEventHandler(rawHandler);
        StormEventDispatcher.registerEventHandler(typedHandler);

        KahluaTable args = new StubKahluaTable(Map.of("key", "value"));
        OnClientCommandEvent event = new OnClientCommandEvent("test", "doSomething", null, args);
        StormEventDispatcher.dispatchEvent(event);

        // Both the raw @SubscribeEvent handler and the typed @OnClientCommand handler fire
        Assertions.assertTrue(rawHandler.wasCalled);
        Assertions.assertTrue(typedHandler.wasCalled);
    }

    // ---- Test handlers ----

    public static class TypedHandler {
        boolean wasCalled = false;
        StubClientCommandEvent receivedEvent;

        @OnClientCommand
        public void handle(StubClientCommandEvent event) {
            wasCalled = true;
            receivedEvent = event;
        }
    }

    public static class TypedHandlerB {
        boolean wasCalled = false;

        @OnClientCommand
        public void handle(StubClientCommandEventB event) {
            wasCalled = true;
        }
    }

    public static class RawHandler {
        boolean wasCalled = false;

        @SubscribeEvent
        public void handle(OnClientCommandEvent event) {
            wasCalled = true;
        }
    }
}
