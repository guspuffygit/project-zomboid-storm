package io.pzstorm.storm.logging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zombie.debug.LogSeverity;

public class ZomboidLogger {
    public static final Logger LOGGER = LoggerFactory.getLogger("Zomboid");

    private static final List<BiConsumer<LogSeverity, String>> LISTENERS =
            new CopyOnWriteArrayList<>();

    /**
     * Observe every line vanilla's DebugLog echoes to its log files, on the thread that logged it.
     * Listeners must return fast and never log through DebugLog themselves.
     */
    public static void addListener(BiConsumer<LogSeverity, String> listener) {
        LISTENERS.add(listener);
    }

    public static void log(LogSeverity logSeverity, String logMessage) {
        switch (logSeverity) {
            case LogSeverity.Error -> LOGGER.error(logMessage);
            case LogSeverity.Warning -> LOGGER.warn(logMessage);
            case LogSeverity.General -> LOGGER.info(logMessage);
            case LogSeverity.Debug, LogSeverity.Trace, LogSeverity.Noise ->
                    LOGGER.debug(logMessage);
            case null, default -> LOGGER.trace(logMessage);
        }
        for (BiConsumer<LogSeverity, String> listener : LISTENERS) {
            try {
                listener.accept(logSeverity, logMessage);
            } catch (Throwable ignored) {
                // a diagnostic listener must never break the game's own logging
            }
        }
    }
}
