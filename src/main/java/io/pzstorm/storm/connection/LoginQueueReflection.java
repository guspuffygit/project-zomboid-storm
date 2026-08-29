package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.jetbrains.annotations.Nullable;
import zombie.core.raknet.UdpConnection;
import zombie.network.LoginQueue;

/**
 * Cached reflective access to {@code zombie.network.LoginQueue}'s private static surface: the
 * {@code LoginQueue} list (whose monitor guards every vanilla queue mutation), the {@code
 * currentLoginQueue} slot field, and {@code loadNextPlayer()}.
 *
 * <p>Initialization is lazy and idempotent, never a static initializer: a game update that renames
 * any of these members must degrade to "early release disables itself, vanilla queue behavior runs"
 * — not an {@link ExceptionInInitializerError} that poisons the class for the rest of the process.
 */
final class LoginQueueReflection {

    private static volatile boolean initAttempted;
    private static volatile boolean ready;

    private static Field queueListField;
    private static Field currentLoginQueueField;
    private static Method loadNextPlayerMethod;

    private LoginQueueReflection() {}

    static boolean init() {
        if (initAttempted) {
            return ready;
        }
        synchronized (LoginQueueReflection.class) {
            if (initAttempted) {
                return ready;
            }
            try {
                Class<?> queue = LoginQueue.class;

                queueListField = queue.getDeclaredField("LoginQueue");
                queueListField.setAccessible(true);
                currentLoginQueueField = queue.getDeclaredField("currentLoginQueue");
                currentLoginQueueField.setAccessible(true);
                loadNextPlayerMethod = queue.getDeclaredMethod("loadNextPlayer");
                loadNextPlayerMethod.setAccessible(true);

                ready = true;
            } catch (Throwable t) {
                LOGGER.error(
                        "LoginQueueEarlyRelease: reflection against LoginQueue failed —"
                                + " early slot release is disabled, vanilla queue behavior runs",
                        t);
            }
            initAttempted = true;
            return ready;
        }
    }

    /**
     * The private static {@code LoginQueue} list — the monitor every vanilla queue mutation
     * synchronizes on. Callers hold it around {@link #currentLoginQueue()}, {@link
     * #clearCurrentLoginQueue()} and {@link #loadNextPlayer()} exactly like vanilla's own {@code
     * receiveLoginQueueDone}.
     */
    static Object queueMonitor() throws IllegalAccessException {
        return queueListField.get(null);
    }

    static @Nullable UdpConnection currentLoginQueue() throws IllegalAccessException {
        return (UdpConnection) currentLoginQueueField.get(null);
    }

    static void clearCurrentLoginQueue() throws IllegalAccessException {
        currentLoginQueueField.set(null, null);
    }

    static void loadNextPlayer() throws Exception {
        loadNextPlayerMethod.invoke(null);
    }

    // test hook: lets a test simulate a reflection-failure fallback without a franken classpath
    static void resetForTests() {
        synchronized (LoginQueueReflection.class) {
            initAttempted = false;
            ready = false;
        }
    }
}
