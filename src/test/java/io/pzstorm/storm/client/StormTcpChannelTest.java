package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import zombie.core.random.RandAbstract;
import zombie.core.random.RandStandard;

/**
 * The channel's live loop needs a running client, so tests cover the boot gate: the watcher must
 * not touch GameClient before the game main thread initializes its RNG, because GameClient's static
 * initializer draws from it (losing that race poisons the class and crashes the client).
 */
class StormTcpChannelTest {

    /** The gate locates the RNG by field type; this is the drift alarm for a game update. */
    @Test
    void randAbstractStillCarriesARandomTypedField() throws Exception {
        assertNotNull(randField());
    }

    @Test
    void gameRngGateOpensOnceRngIsInitialized() throws Exception {
        Field rand = randField();
        Object previous = rand.get(RandStandard.INSTANCE);
        try {
            rand.set(RandStandard.INSTANCE, new Random());
            assertTrue(StormTcpChannel.awaitGameRngInit());
        } finally {
            rand.set(RandStandard.INSTANCE, previous);
        }
    }

    @Test
    void gameRngGateBlocksWhileRngIsNullAndStopsOnInterrupt() throws Exception {
        Field rand = randField();
        Object previous = rand.get(RandStandard.INSTANCE);
        try {
            rand.set(RandStandard.INSTANCE, null);
            AtomicBoolean gateOpened = new AtomicBoolean(true);
            Thread watcher = new Thread(() -> gateOpened.set(StormTcpChannel.awaitGameRngInit()));
            watcher.start();
            watcher.join(100);
            assertTrue(watcher.isAlive(), "gate must block while the RNG is uninitialized");
            watcher.interrupt();
            watcher.join(5_000);
            assertFalse(watcher.isAlive());
            assertFalse(gateOpened.get());
        } finally {
            rand.set(RandStandard.INSTANCE, previous);
        }
    }

    private static Field randField() {
        for (Field field : RandAbstract.class.getDeclaredFields()) {
            if (field.getType() == Random.class) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}
