package io.pzstorm.storm.event;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.zomboid.OnBanIpEvent;
import io.pzstorm.storm.event.zomboid.OnBanSteamIDEvent;
import io.pzstorm.storm.event.zomboid.OnBanUserEvent;
import io.pzstorm.storm.patch.networking.ServerWorldDatabasePatch;
import java.io.InputStream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.network.StubServerWorldDatabase;

/**
 * Integration test for {@link ServerWorldDatabasePatch}. Applies the real patch's {@code
 * dynamicType()} logic to {@link StubServerWorldDatabase} (a test stub whose {@code banUser},
 * {@code banIp}, and {@code banSteamID} methods mirror the signatures on {@code
 * zombie.network.ServerWorldDatabase}), then invokes each method on the patched class and asserts
 * that the corresponding {@code OnBan*Event} was dispatched with the expected field values.
 */
class ServerWorldDatabasePatchIntegrationTest implements IntegrationTest {

    // Captured state populated by the handlers when events fire.
    private static volatile OnBanUserEvent lastBanUserEvent;
    private static volatile OnBanIpEvent lastBanIpEvent;
    private static volatile OnBanSteamIDEvent lastBanSteamIDEvent;

    private static Class<?> patchedStubClass;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void applyPatchAndRegisterHandlers() throws Exception {
        StormEventDispatcher.registerEventHandler(CapturingHandler.class);

        ClassLoader parent = StubServerWorldDatabase.class.getClassLoader();
        String stubName = StubServerWorldDatabase.class.getName();
        String stubResource = stubName.replace('.', '/') + ".class";

        // Read the raw bytes of the stub class.
        byte[] rawClass;
        try (InputStream is = parent.getResourceAsStream(stubResource)) {
            Assertions.assertNotNull(is, stubResource + " must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        // Run the real patch's dynamicType() on a builder pointed at the stub. This tests the
        // production matchers and advice wiring 1:1 against a class we can actually instantiate.
        ServerWorldDatabasePatch patch = new ServerWorldDatabasePatch();
        ClassFileLocator locator =
                new ClassFileLocator.Compound(
                        ClassFileLocator.Simple.of(stubName, rawClass),
                        ClassFileLocator.ForClassLoader.of(parent),
                        ClassFileLocator.ForClassLoader.ofSystemLoader());
        TypePool typePool = TypePool.Default.of(locator);
        DynamicType.Builder<Object> builder =
                new ByteBuddy().redefine(typePool.describe(stubName).resolve(), locator);

        byte[] transformed = patch.dynamicType(locator, typePool, builder).make().getBytes();

        // Define the transformed class in a fresh child classloader so it has a distinct identity
        // from the parent-loaded StubServerWorldDatabase.
        patchedStubClass = defineClassFromBytes(parent, stubName, transformed);
    }

    private static Class<?> defineClassFromBytes(ClassLoader parent, String name, byte[] bytes) {
        return new ClassLoader(parent) {
            Class<?> define() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.define();
    }

    @BeforeEach
    void resetCapturedEvents() {
        lastBanUserEvent = null;
        lastBanIpEvent = null;
        lastBanSteamIDEvent = null;
    }

    @Test
    void shouldDispatchOnBanUserEventAfterBanUserCompletes() throws Exception {
        Object stub = patchedStubClass.getDeclaredConstructor().newInstance();

        String result =
                (String)
                        patchedStubClass
                                .getMethod("banUser", String.class, boolean.class)
                                .invoke(stub, "alice", true);

        Assertions.assertEquals("User \"alice\" is now banned", result);
        Assertions.assertNotNull(
                lastBanUserEvent, "OnBanUserEvent should have been dispatched on method exit");
        Assertions.assertEquals("alice", lastBanUserEvent.getUsername());
        Assertions.assertTrue(lastBanUserEvent.isBan());
        Assertions.assertEquals(result, lastBanUserEvent.getResult());

        // Unrelated events must not fire.
        Assertions.assertNull(lastBanIpEvent);
        Assertions.assertNull(lastBanSteamIDEvent);
    }

    @Test
    void shouldDispatchOnBanIpEventAfterBanIpCompletes() throws Exception {
        Object stub = patchedStubClass.getDeclaredConstructor().newInstance();

        String result =
                (String)
                        patchedStubClass
                                .getMethod(
                                        "banIp",
                                        String.class,
                                        String.class,
                                        String.class,
                                        boolean.class)
                                .invoke(stub, "10.0.0.1", "bob", "griefing", true);

        Assertions.assertEquals("IP 10.0.0.1(bob)  is now banned", result);
        Assertions.assertNotNull(
                lastBanIpEvent, "OnBanIpEvent should have been dispatched on method exit");
        Assertions.assertEquals("10.0.0.1", lastBanIpEvent.getIp());
        Assertions.assertEquals("bob", lastBanIpEvent.getUsername());
        Assertions.assertEquals("griefing", lastBanIpEvent.getReason());
        Assertions.assertTrue(lastBanIpEvent.isBan());
        Assertions.assertEquals(result, lastBanIpEvent.getResult());

        Assertions.assertNull(lastBanUserEvent);
        Assertions.assertNull(lastBanSteamIDEvent);
    }

    @Test
    void shouldDispatchOnBanSteamIDEventAfterBanSteamIDCompletes() throws Exception {
        Object stub = patchedStubClass.getDeclaredConstructor().newInstance();

        String result =
                (String)
                        patchedStubClass
                                .getMethod("banSteamID", String.class, String.class, boolean.class)
                                .invoke(stub, "76561198000000000", "cheating", true);

        Assertions.assertEquals("SteamID 76561198000000000 is now banned", result);
        Assertions.assertNotNull(
                lastBanSteamIDEvent,
                "OnBanSteamIDEvent should have been dispatched on method exit");
        Assertions.assertEquals("76561198000000000", lastBanSteamIDEvent.getSteamID());
        Assertions.assertEquals("cheating", lastBanSteamIDEvent.getReason());
        Assertions.assertTrue(lastBanSteamIDEvent.isBan());
        Assertions.assertEquals(result, lastBanSteamIDEvent.getResult());

        Assertions.assertNull(lastBanUserEvent);
        Assertions.assertNull(lastBanIpEvent);
    }

    @Test
    void shouldDispatchUnbanResultWhenBanFalse() throws Exception {
        Object stub = patchedStubClass.getDeclaredConstructor().newInstance();

        patchedStubClass
                .getMethod("banUser", String.class, boolean.class)
                .invoke(stub, "carol", false);

        Assertions.assertNotNull(lastBanUserEvent);
        Assertions.assertEquals("carol", lastBanUserEvent.getUsername());
        Assertions.assertFalse(lastBanUserEvent.isBan());
        Assertions.assertEquals("User \"carol\" is now un-banned", lastBanUserEvent.getResult());
    }

    /** Static handler capturing every ban event the dispatcher fires. */
    public static class CapturingHandler {

        @SubscribeEvent
        public static void onBanUser(OnBanUserEvent event) {
            lastBanUserEvent = event;
        }

        @SubscribeEvent
        public static void onBanIp(OnBanIpEvent event) {
            lastBanIpEvent = event;
        }

        @SubscribeEvent
        public static void onBanSteamID(OnBanSteamIDEvent event) {
            lastBanSteamIDEvent = event;
        }
    }
}
