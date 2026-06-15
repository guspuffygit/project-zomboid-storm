package io.pzstorm.storm.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder for dedicated, size-bounded log files that live alongside Storm's own {@code main.log} /
 * {@code debug.log}.
 *
 * <p>Each logger gets its own {@link RollingFileAppender} backed by a {@link
 * FixedWindowRollingPolicy} so the on-disk footprint is hard-capped: one active file up to {@code
 * maxFileSizeMb} MB, plus up to {@code maxIndex} rolled archives. Append happens through an {@link
 * AsyncAppender} so the calling thread is never blocked on I/O — important for hot-path callers
 * like the dedicated server's main loop.
 *
 * <p>Log files land at {@code <STORM_LOG_DIR>/<subDir>/<name>.<extension>}. {@code STORM_LOG_DIR}
 * follows Storm's convention: the {@code -DSTORM_LOG_DIR=…} system property if set, otherwise
 * {@code ~/Zomboid/Logs}.
 *
 * <p>The returned logger is non-additive: messages written to it do <b>not</b> propagate to the
 * root appenders, so callers can spam this file without polluting Storm's main log.
 */
public final class StormFileLoggerFactory {

    private static final String LOG_HOME;

    static {
        String home = System.getProperty("STORM_LOG_DIR");
        if (home == null || home.isEmpty()) {
            home = System.getProperty("user.home") + "/Zomboid/Logs";
        }
        LOG_HOME = home;
    }

    private StormFileLoggerFactory() {}

    /**
     * Create a non-additive logger that writes to a private rolling file.
     *
     * @param loggerName SLF4J logger name (the key used inside the logback context).
     * @param fileName base file name without extension (e.g. {@code "timings"}).
     * @param subDir subdirectory under {@code STORM_LOG_DIR} (e.g. {@code "storm"}).
     * @param extension file extension without the dot (e.g. {@code "log"} or {@code "json"}).
     * @param maxFileSizeMb size at which the active file rolls over.
     * @param maxIndex highest archive index to keep ({@code 1} = at most one rolled file).
     * @param pattern Logback pattern layout, or {@code null} for the default timestamp+message
     *     pattern.
     */
    public static Logger create(
            String loggerName,
            String fileName,
            String subDir,
            String extension,
            int maxFileSizeMb,
            int maxIndex,
            String pattern) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        String dir = LOG_HOME + "/" + subDir;
        String activeFile = dir + "/" + fileName + "." + extension;
        String archivePattern = dir + "/" + fileName + ".%i." + extension;

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(pattern != null ? pattern : "%date{yyyy-MM-dd HH:mm:ss.SSS} %msg%n");
        encoder.start();

        RollingFileAppender<ILoggingEvent> rollingAppender = new RollingFileAppender<>();
        rollingAppender.setContext(context);
        rollingAppender.setName("STORM_FILE_" + loggerName.toUpperCase());
        rollingAppender.setFile(activeFile);
        rollingAppender.setAppend(true);
        rollingAppender.setEncoder(encoder);

        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(rollingAppender);
        rollingPolicy.setFileNamePattern(archivePattern);
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(maxIndex);
        rollingPolicy.start();

        SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy =
                new SizeBasedTriggeringPolicy<>();
        triggeringPolicy.setContext(context);
        triggeringPolicy.setMaxFileSize(FileSize.valueOf(maxFileSizeMb + "MB"));
        triggeringPolicy.start();

        rollingAppender.setRollingPolicy(rollingPolicy);
        rollingAppender.setTriggeringPolicy(triggeringPolicy);
        rollingAppender.start();

        AsyncAppender asyncAppender = new AsyncAppender();
        asyncAppender.setContext(context);
        asyncAppender.setName("STORM_FILE_ASYNC_" + loggerName.toUpperCase());
        asyncAppender.addAppender(rollingAppender);
        asyncAppender.setQueueSize(512);
        asyncAppender.setDiscardingThreshold(0);
        asyncAppender.setIncludeCallerData(false);
        asyncAppender.start();

        ch.qos.logback.classic.Logger logger = context.getLogger(loggerName);
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(asyncAppender);

        StormLogger.LOGGER.info("Storm file logger [{}] writing to: {}", loggerName, activeFile);
        return logger;
    }
}
