package io.pzstorm.storm.event;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.event.core.LuaEvent;
import io.pzstorm.storm.event.core.LuaEventFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;

/**
 * Guards every {@link LuaEvent} class against a Project Zomboid event rename.
 *
 * <p>{@link LuaEventFactory} keys its classes on {@code getSimpleName()} minus the {@code Event}
 * suffix, and dispatch is a map lookup on the string {@code LuaEventManager.triggerEvent} was
 * called with. Nothing anywhere asserts the two agree — so when a game update renames or drops an
 * event, the corresponding Storm event class simply stops firing. No exception, no log line, no
 * failing test; a {@code @SubscribeEvent} handler just goes quiet, which is the same silent-death
 * failure mode the packet-event surface has hit on past bumps.
 *
 * <p>This compares Storm's registered names against {@code LuaEventManager.EventMap}, which is PZ's
 * own authoritative list, populated by its private {@code AddEvents()}. Both are reached
 * reflectively: a future PZ refactor that renames either should fail this test loudly rather than
 * silently skip the check.
 */
class LuaEventNameRegistryTest implements UnitTest {

    /**
     * Events that legitimately have no entry in PZ's {@code AddEvents()}, because nothing looks
     * them up by name. Either Storm triggers them itself — {@code LuaEventManager.triggerEvent}
     * adds an unknown event on demand — or a patch constructs the event class and dispatches it
     * directly, never touching the name map. Anything not on this list must exist in PZ or its
     * handlers are dead.
     */
    private static final Set<String> NOT_ROUTED_BY_LUA_NAME =
            Set.of(
                    // triggered by Storm's own OnDeathAdvice
                    "OnDeath",
                    "OnAnimalDeath",
                    "OnZombieDeath",
                    // constructed and dispatched directly by ChatManagerPatch
                    "OnSendMessageToChat",
                    // constructed and dispatched directly by consumer-mod patches
                    "OnPlayerDisconnected",
                    "OnPlayerFullyConnected",
                    "OnAuthAttempt",
                    // no dispatcher anywhere: kept as public API for consumer mods
                    "OnGetDBSchema",
                    "OnGetTableResult");

    @Test
    void everyRegisteredLuaEventNameExistsInProjectZomboid() throws Exception {
        Map<String, ?> vanillaEvents = vanillaEventMap();
        Assertions.assertFalse(
                vanillaEvents.isEmpty(), "LuaEventManager.EventMap was empty after AddEvents()");

        List<String> missing = new ArrayList<>();
        for (String eventName : stormEventNames()) {
            if (!vanillaEvents.containsKey(eventName)
                    && !NOT_ROUTED_BY_LUA_NAME.contains(eventName)) {
                missing.add(eventName);
            }
        }
        Assertions.assertTrue(
                missing.isEmpty(),
                "Storm registers Lua events that Project Zomboid never fires, so their"
                        + " @SubscribeEvent handlers are dead: "
                        + missing);
    }

    /**
     * The exemption list above is only safe while it stays minimal, so a name that PZ has since
     * started firing has to be taken back off it rather than left as a permanent hole in the check.
     */
    @Test
    void exemptionListHoldsOnlyEventsProjectZomboidStillDoesNotFire() throws Exception {
        Map<String, ?> vanillaEvents = vanillaEventMap();

        List<String> stale = new ArrayList<>();
        for (String eventName : NOT_ROUTED_BY_LUA_NAME) {
            if (vanillaEvents.containsKey(eventName)) {
                stale.add(eventName);
            }
        }
        Assertions.assertTrue(
                stale.isEmpty(),
                "Project Zomboid now fires these by name, so they no longer belong in"
                        + " NOT_ROUTED_BY_LUA_NAME: "
                        + stale);
    }

    /**
     * {@code getEventName} strips a trailing {@code Event} from the class name, which is wrong for
     * the handful of PZ events whose own name ends in {@code Event}. Those need an explicit entry
     * in {@code LuaEventFactory.EXPLICIT_EVENT_NAMES}; this pins the three that do, so a fourth
     * arriving in a game update is caught by the test above rather than by a handler quietly going
     * silent.
     */
    @Test
    void eventsWhosePzNameEndsInEventKeepTheSuffix() {
        for (String eventName :
                List.of("OnThunderEvent", "OnTriggerNPCEvent", "OnMultiTriggerNPCEvent")) {
            Assertions.assertNotNull(
                    LuaEventFactory.getEventClass(eventName),
                    eventName + " is not registered under its full Project Zomboid name");
        }
    }

    @Test
    void loadChunkIsWiredForClientChunkMetrics() throws Exception {
        Assertions.assertTrue(
                vanillaEventMap().containsKey("LoadChunk"),
                "IsoChunk.doLoadGridsquare no longer fires LoadChunk; the client chunk arrival"
                        + " counter in ClientChunkStreamMetrics is dead");

        Class<? extends LuaEvent> eventClass = LuaEventFactory.getEventClass("LoadChunk");
        Assertions.assertNotNull(eventClass, "LoadChunk is not registered in LuaEventFactory");
        Assertions.assertEquals(
                1,
                eventClass.getDeclaredConstructors()[0].getParameterCount(),
                "LoadChunkEvent must take the single IsoChunk that triggerEvent passes");
    }

    private static Iterable<String> stormEventNames() throws Exception {
        Field field = LuaEventFactory.class.getDeclaredField("EVENT_CLASSES");
        field.setAccessible(true);
        return ((Map<String, ?>) field.get(null)).keySet();
    }

    /**
     * {@code AddEvent} publishes each event into the {@code Events} Lua table as a side effect, so
     * the environment has to exist before {@code AddEvents()} can run. The previous environment is
     * restored because tests share a JVM.
     */
    private static Map<String, ?> vanillaEventMap() throws Exception {
        KahluaTable previousEnv = LuaManager.env;
        try {
            // a bare table, not newEnvironment(): AddEvent only needs somewhere to publish the
            // Events entry, while newEnvironment() would try to load stdlib.lua from the CWD
            LuaManager.env = LuaManager.platform.newTable();
            LuaManager.env.rawset("Events", LuaManager.platform.newTable());

            Method addEvents = LuaEventManager.class.getDeclaredMethod("AddEvents");
            addEvents.setAccessible(true);
            addEvents.invoke(null);
        } finally {
            LuaManager.env = previousEnv;
        }
        Field field = LuaEventManager.class.getDeclaredField("EventMap");
        field.setAccessible(true);
        return new HashMap<>((Map<String, ?>) field.get(null));
    }
}
