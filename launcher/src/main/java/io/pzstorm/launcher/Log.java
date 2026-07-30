package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Tiny logger: launcher.log + stdout + an optional UI sink (the status pane). */
public final class Log {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static Path file;
    private static volatile Consumer<String> sink;

    private Log() {}

    public static synchronized void init(Path logFile) {
        try {
            Files.createDirectories(logFile.getParent());
            file = logFile;
        } catch (IOException e) {
            System.err.println("[launcher] cannot create log dir: " + e.getMessage());
        }
    }

    public static void setSink(Consumer<String> uiSink) {
        sink = uiSink;
    }

    public static void info(String message) {
        write("INFO", message);
    }

    public static void warn(String message) {
        write("WARN", message);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message + (t == null ? "" : " — " + t));
    }

    private static synchronized void write(String level, String message) {
        String line = LocalDateTime.now().format(FORMAT) + " [" + level + "] " + message;
        System.out.println(line);
        if (file != null) {
            try {
                Files.write(
                        file,
                        (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // logging must never take the launcher down
            }
        }
        Consumer<String> s = sink;
        if (s != null) {
            s.accept(line);
        }
    }
}
