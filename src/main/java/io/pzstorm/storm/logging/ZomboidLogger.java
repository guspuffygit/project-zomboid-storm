package io.pzstorm.storm.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zombie.debug.LogSeverity;

public class ZomboidLogger {
    public static final Logger LOGGER = LoggerFactory.getLogger("Zomboid");

    public static void log(LogSeverity logSeverity, String logMessage) {
        switch (logSeverity) {
            case LogSeverity.Error -> LOGGER.error(logMessage);
            case LogSeverity.Warning -> LOGGER.warn(logMessage);
            case LogSeverity.General -> LOGGER.info(logMessage);
            case LogSeverity.Debug, LogSeverity.Trace, LogSeverity.Noise ->
                    LOGGER.debug(logMessage);
            case null, default -> LOGGER.trace(logMessage);
        }
    }
}
