package io.pzstorm.storm.event;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.core.ZomboidEvent;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests what happens when an event is constructed or dispatched with parameters that don't match
 * the expected event type. This simulates the scenario where the game fires a Lua event with
 * arguments that don't match Storm's event constructor (e.g. after a game update changes event
 * signatures).
 *
 * <p>The construction logic here mirrors {@code LuaEventFactory.constructLuaEvent} but uses test
 * event classes to avoid Project Zomboid class dependencies.
 */
class EventParameterMismatchTest implements UnitTest {

    // ---- Client-side scenario tests ----

    /**
     * Simulates a client-side event (like OnCharacterDeathEvent) being constructed with the wrong
     * number of arguments. For example, the game sends 0 args but the event expects 1.
     *
     * <p>Expected: throws IllegalArgumentException (would crash the client).
     */
    @Test
    void clientSide_shouldThrowWhenConstructedWithWrongArgCount() {
        // TestEventWithStringParam expects 1 arg, but we pass 0
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> constructEvent(TestEventWithStringParam.class));
    }

    /**
     * Simulates a client-side event being constructed with too many arguments. For example, the
     * game sends 2 args but the event expects 1.
     *
     * <p>Expected: throws IllegalArgumentException (would crash the client).
     */
    @Test
    void clientSide_shouldThrowWhenConstructedWithTooManyArgs() {
        // TestEventWithStringParam expects 1 arg, but we pass 2
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> constructEvent(TestEventWithStringParam.class, "hello", 42));
    }

    /**
     * Simulates a client-side event being constructed with the wrong argument type. For example,
     * the game sends an Integer but the event expects a String.
     *
     * <p>Expected: throws IllegalArgumentException (would crash the client).
     */
    @Test
    void clientSide_shouldThrowWhenConstructedWithWrongArgType() {
        // TestEventWithStringParam expects String, but we pass Integer
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> constructEvent(TestEventWithStringParam.class, 12345));
    }

    /**
     * Simulates a client-side event being constructed with null where a primitive-wrapper is
     * expected. Null args bypass the type check in LuaEventFactory so the constructor is selected,
     * but newInstance may still succeed (null is valid for reference types).
     *
     * <p>Expected: does NOT throw - null is assignable to String.
     */
    @Test
    void clientSide_shouldNotThrowWhenConstructedWithNullArg() {
        Assertions.assertDoesNotThrow(
                () -> {
                    ZomboidEvent event =
                            constructEvent(TestEventWithStringParam.class, (Object) null);
                    Assertions.assertNotNull(event);
                });
    }

    /**
     * Verifies that constructing a client-side event with correct args works fine.
     *
     * <p>Expected: succeeds, event is constructed.
     */
    @Test
    void clientSide_shouldSucceedWithCorrectArgs() {
        ZomboidEvent event = constructEvent(TestEventWithStringParam.class, "hello");
        Assertions.assertNotNull(event);
        Assertions.assertEquals("hello", ((TestEventWithStringParam) event).value);
    }

    // ---- Server-side scenario tests ----

    /**
     * Simulates a server-side event (like OnClientCommandEvent) being constructed with the wrong
     * number of arguments. For example, the game sends 1 arg but the event expects 2.
     *
     * <p>Expected: throws IllegalArgumentException (would crash the server).
     */
    @Test
    void serverSide_shouldThrowWhenConstructedWithWrongArgCount() {
        // TestEventWithTwoParams expects 2 args (String, Integer), but we pass 1
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> constructEvent(TestEventWithTwoParams.class, "only-one"));
    }

    /**
     * Simulates a server-side event being constructed with wrong argument types. For example, the
     * game sends (Integer, String) but the event expects (String, Integer).
     *
     * <p>Expected: throws IllegalArgumentException (would crash the server).
     */
    @Test
    void serverSide_shouldThrowWhenConstructedWithSwappedArgTypes() {
        // TestEventWithTwoParams expects (String, Integer), but we pass (Integer, String)
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> constructEvent(TestEventWithTwoParams.class, 42, "backwards"));
    }

    /**
     * Simulates a server-side event being constructed with extra arguments. For example, a game
     * update adds a third parameter to an event but Storm's event class only has a 2-arg
     * constructor.
     *
     * <p>Expected: throws IllegalArgumentException (would crash the server).
     */
    @Test
    void serverSide_shouldThrowWhenConstructedWithExtraArgs() {
        // TestEventWithTwoParams expects 2 args, but we pass 3
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> constructEvent(TestEventWithTwoParams.class, "name", 42, "extra"));
    }

    /**
     * Verifies that constructing a server-side event with correct args works fine.
     *
     * <p>Expected: succeeds, event is constructed.
     */
    @Test
    void serverSide_shouldSucceedWithCorrectArgs() {
        ZomboidEvent event = constructEvent(TestEventWithTwoParams.class, "test", 99);
        Assertions.assertNotNull(event);
        TestEventWithTwoParams typed = (TestEventWithTwoParams) event;
        Assertions.assertEquals("test", typed.name);
        Assertions.assertEquals(99, typed.count);
    }

    // ---- Dispatch-level tests ----

    /**
     * Tests that dispatching an event to a handler subscribed to a DIFFERENT event type does NOT
     * crash. The dispatcher uses exact class matching, so the handler simply isn't called.
     *
     * <p>This is the "safe" scenario - mismatched event types at the dispatch level are silently
     * ignored.
     */
    @Test
    void dispatch_shouldNotCrashWhenEventTypeDoesNotMatchHandler() {
        MismatchHandler handler = new MismatchHandler();
        StormEventDispatcher.registerEventHandler(handler);

        // Handler is registered for TestEventWithStringParam, but we dispatch TestZomboidEventA
        Assertions.assertDoesNotThrow(
                () -> StormEventDispatcher.dispatchEvent(new TestZomboidEventA()));

        // Handler was never called
        Assertions.assertFalse(handler.wasCalled);
    }

    /**
     * Tests that dispatching a correctly constructed event to a matching handler works and the
     * handler receives the event with its parameters intact.
     */
    @Test
    void dispatch_shouldSucceedWhenEventTypeMatchesHandler() {
        MismatchHandler handler = new MismatchHandler();
        StormEventDispatcher.registerEventHandler(handler);

        TestEventWithStringParam event = new TestEventWithStringParam("dispatched");
        StormEventDispatcher.dispatchEvent(event);

        Assertions.assertTrue(handler.wasCalled);
        Assertions.assertEquals("dispatched", handler.receivedValue);
    }

    // ---- Helper: replicates LuaEventFactory.constructLuaEvent logic ----

    /**
     * Mirrors the construction logic in {@code LuaEventFactory.constructLuaEvent}. Finds a
     * constructor that matches the given args by count and type assignability, then invokes it.
     *
     * @throws IllegalArgumentException if no matching constructor is found (same as
     *     LuaEventFactory)
     */
    @SuppressWarnings("unchecked")
    private static <T extends ZomboidEvent> T constructEvent(Class<T> eventClass, Object... args) {
        Constructor<?>[] constructors = eventClass.getConstructors();

        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i] != null ? args[i].getClass() : null;
        }

        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() == args.length) {
                if (doesConstructorMatchArgTypes(constructor, argTypes)) {
                    try {
                        return (T) constructor.newInstance(args);
                    } catch (ReflectiveOperationException | IllegalArgumentException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }

        throw new IllegalArgumentException(
                "Unable to find constructor for class '"
                        + eventClass.getName()
                        + "' that matches arguments "
                        + java.util.Arrays.toString(args));
    }

    private static boolean doesConstructorMatchArgTypes(
            Constructor<?> constructor, Class<?>[] argTypes) {
        Class<?>[] paramTypes = constructor.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> argType = argTypes[i];
            if (argType != null && !paramTypes[i].isAssignableFrom(argType)) {
                return false;
            }
        }
        return true;
    }

    // ---- Test handler for dispatch tests ----

    public static class MismatchHandler {
        boolean wasCalled = false;
        String receivedValue = null;

        @SubscribeEvent
        public void handleStringParamEvent(TestEventWithStringParam event) {
            wasCalled = true;
            receivedValue = event.value;
        }
    }
}
