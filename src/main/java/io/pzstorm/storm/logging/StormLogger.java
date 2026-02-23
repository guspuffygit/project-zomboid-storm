package io.pzstorm.storm.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StormLogger {

    public static final Logger LOGGER = LoggerFactory.getLogger(StormLogger.class);

    public static class Log4JUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            LOGGER.error("Uncaught exception was thrown", e);
        }
    }
}
