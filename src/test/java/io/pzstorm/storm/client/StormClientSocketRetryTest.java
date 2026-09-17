package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Rebinding the socket needs RakNet natives, so tests cover the guards plus the shape the advice
 * weaves against. GameClient is loaded without initializing it: its static initializer draws from
 * the game RNG, which is not set up in a unit test.
 */
class StormClientSocketRetryTest {

    private static Class<?> gameClient() throws Exception {
        return Class.forName(
                "zombie.network.GameClient",
                false,
                StormClientSocketRetryTest.class.getClassLoader());
    }

    @Test
    void doesNothingWhenVanillaStartedTheClient() {
        assertTrue(StormClientSocketRetry.afterStartClient(new Object(), true));
    }

    @Test
    void failsSoftWhenTheInstanceIsNotAGameClient() {
        assertFalse(StormClientSocketRetry.afterStartClient(new Object(), false));
    }

    /** The advice writes this field by name; a rename would silently disable the retry. */
    @Test
    void gameClientStillCarriesAPrivateClientStartedFlag() throws Exception {
        Field field = gameClient().getDeclaredField("clientStarted");
        assertEquals(boolean.class, field.getType());
        assertTrue(Modifier.isPrivate(field.getModifiers()));
    }

    /** The helper assigns this field directly rather than through reflection. */
    @Test
    void gameClientStillExposesUdpEngine() throws Exception {
        Field field = gameClient().getDeclaredField("udpEngine");
        assertEquals("zombie.core.raknet.UdpEngine", field.getType().getName());
        assertTrue(Modifier.isPublic(field.getModifiers()));
    }

    /** The transformer matches on name and arity. */
    @Test
    void startClientIsStillANoArgMethod() throws Exception {
        assertEquals(0, gameClient().getDeclaredMethod("startClient").getParameterCount());
    }
}
