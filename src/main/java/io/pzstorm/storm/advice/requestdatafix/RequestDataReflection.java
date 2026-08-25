package io.pzstorm.storm.advice.requestdatafix;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import zombie.network.RequestDataManager;

/**
 * Cached reflective access to {@code RequestDataManager}'s private surface — the {@code requests}
 * list, the package-private {@code RequestData} entry class ({@code id}, {@code connectionGuid},
 * {@code creationTime}) and the private {@code sendData(RequestData)} method.
 *
 * <p>Initialization is lazy and idempotent, never a static initializer: a game update that renames
 * any of these members must degrade to "advice returns {@code false}, vanilla body runs" — not an
 * {@link ExceptionInInitializerError} that poisons the class for the rest of the process.
 */
final class RequestDataReflection {

    private static volatile boolean initAttempted;
    private static volatile boolean ready;

    private static Field requestsField;
    private static Field idField;
    private static Field connectionGuidField;
    private static Field creationTimeField;
    private static Method sendDataMethod;

    private RequestDataReflection() {}

    static boolean init() {
        if (initAttempted) {
            return ready;
        }
        synchronized (RequestDataReflection.class) {
            if (initAttempted) {
                return ready;
            }
            try {
                Class<?> manager = RequestDataManager.class;
                Class<?> entry =
                        Class.forName(
                                manager.getName() + "$RequestData",
                                false,
                                manager.getClassLoader());

                requestsField = manager.getDeclaredField("requests");
                requestsField.setAccessible(true);
                sendDataMethod = manager.getDeclaredMethod("sendData", entry);
                sendDataMethod.setAccessible(true);

                idField = entry.getDeclaredField("id");
                idField.setAccessible(true);
                connectionGuidField = entry.getDeclaredField("connectionGuid");
                connectionGuidField.setAccessible(true);
                creationTimeField = entry.getDeclaredField("creationTime");
                creationTimeField.setAccessible(true);

                ready = true;
            } catch (Throwable t) {
                LOGGER.error(
                        "RequestDataFix: reflection against RequestDataManager failed —"
                                + " falling back to the vanilla implementation",
                        t);
            }
            initAttempted = true;
            return ready;
        }
    }

    static List<?> requests(Object manager) throws IllegalAccessException {
        return (List<?>) requestsField.get(manager);
    }

    static @Nullable Object entryId(Object entry) throws IllegalAccessException {
        return idField.get(entry);
    }

    static long entryGuid(Object entry) throws IllegalAccessException {
        return connectionGuidField.getLong(entry);
    }

    static long entryCreationTime(Object entry) throws IllegalAccessException {
        return creationTimeField.getLong(entry);
    }

    static void sendData(Object manager, Object entry) throws Exception {
        sendDataMethod.invoke(manager, entry);
    }

    // test hook: lets a test simulate a reflection-failure fallback without a franken classpath
    static void resetForTests() {
        synchronized (RequestDataReflection.class) {
            initAttempted = false;
            ready = false;
        }
    }
}
