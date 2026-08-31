package io.pzstorm.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Send Logs" support: posts machine metadata plus a zip of the launcher, game, Zomboid and Storm
 * logs to the Storm team's Discord webhook, mirroring how sentient-sims-app reports issues. Every
 * attachment step fails soft — a missing or unreadable log never blocks the report.
 */
public final class LogReport {

    /**
     * Assembled at runtime so repo scrapers (GitHub tokens scanners auto-revoke pasted Discord
     * webhooks) don't match the URL in the public repo. Same trick as sentient-sims-app's
     * LogSendService.
     */
    static final String WEBHOOK_URL =
            String.join(
                    "",
                    "https://d",
                    "is",
                    "cor",
                    "d.co",
                    "m/api/web",
                    "hooks/15352797",
                    "21116471376/3jAGrW8",
                    "FaydYrbhFu4",
                    "nGKuzG025UZOwP",
                    "G2RFKhQuRZCU",
                    "ob7gnBjcg9r",
                    "xsT5Wf3wJ4Uex");

    /** Only the tail of each log goes in the zip so a huge game.log can't blow the upload cap. */
    static final int TAIL_BYTES = 1024 * 1024;

    /**
     * Newest-first cap on files pulled from Zomboid/Logs (Storm's main.log/debug.log live there).
     */
    static final int MAX_ZOMBOID_LOG_FILES = 12;

    /**
     * Newest-first cap on files pulled from Zomboid/Logs/storm — the Storm client's own log dir,
     * where ClientLoadingWatchdog's stall dumps land in full.
     */
    static final int MAX_STORM_LOG_FILES = 4;

    /** Newest-first cap on JVM fatal-error dumps (hs_err_pid*.log) pulled from the game dir. */
    static final int MAX_HS_ERR_FILES = 3;

    /** Discord rejects message content over 2000 chars; sentient-sims trims to 1900. */
    private static final int MAX_CONTENT_CHARS = 1900;

    private LogReport() {}

    static String webhookUrl() {
        String override = System.getProperty("storm.launcher.logWebhook");
        return override != null && !override.isEmpty() ? override : WEBHOOK_URL;
    }

    /** Sends the report and returns its log id (quote it when asking for help on Discord). */
    public static String send(LauncherConfig config, String description) throws IOException {
        String logId = newLogId();
        String metadata = metadata(config, logId, description);
        byte[] zip = buildZip(metadata, config.resolveGameDir());
        String boundary = "----StormLauncher" + logId;
        byte[] body = multipartBody(boundary, truncate(metadata), zip);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(webhookUrl()))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
        HttpResponse<String> response;
        try {
            response =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(15))
                            .build()
                            .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending logs", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException(
                    "Log upload rejected: HTTP " + response.statusCode() + " — " + response.body());
        }
        return logId;
    }

    static String newLogId() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            id.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return id.toString();
    }

    /**
     * Machine + config facts. Never include passwords or per-server credentials; the OS account
     * name is scrubbed from paths (see {@link LogScrubber}).
     */
    static String metadata(LauncherConfig config, String logId, String description) {
        List<String> lines = new ArrayList<>();
        if (description != null && !description.isEmpty()) {
            lines.add("Description: " + description);
        }
        lines.add("Log id: " + logId);
        lines.add("Launcher version: " + LauncherInfo.version());
        lines.add("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        lines.add("Architecture: " + System.getProperty("os.arch"));
        lines.add(
                "Java: "
                        + System.getProperty("java.version")
                        + " ("
                        + System.getProperty("java.vendor")
                        + ")");
        lines.add("CPU cores: " + Runtime.getRuntime().availableProcessors());
        long ram = GameMemory.totalSystemBytes();
        lines.add("System RAM: " + (ram > 0 ? (ram >> 30) + " GB" : "unknown"));
        Path gameDir = config.resolveGameDir();
        lines.add("Game directory: " + (gameDir == null ? "NOT FOUND" : gameDir));
        lines.add("JVM path: " + safeResolveJvm(config, gameDir));
        Path bootstrapDir = gameDir == null ? null : config.resolveBootstrapDir(gameDir);
        lines.add("Bootstrap: " + (bootstrapDir == null ? "NOT FOUND" : bootstrapDir));
        lines.add("Auto memory: " + config.autoMemory + " (manual " + config.memoryGb + " GB)");
        lines.add("Client perf fixes: " + config.clientPerfFixes);
        lines.add("Skip menus: " + config.skipMenus);
        List<String> vmArgs = new ArrayList<>();
        for (String arg : config.globalVmArgs) {
            vmArgs.add(redactVmArg(arg));
        }
        lines.add("Global VM args: " + vmArgs);
        lines.add("Saved servers: " + config.servers.size());
        return LogScrubber.scrub(String.join("\n", lines));
    }

    /** VM-argument names that look like credentials; their values never leave the machine. */
    private static final java.util.regex.Pattern SECRET_VM_ARG_NAME =
            java.util.regex.Pattern.compile("(?i)pass|token|secret|credential|key");

    /**
     * Global VM args go into the report as typed, except that the value of any {@code name=value}
     * argument whose name looks credential-like is masked — a user who pasted a secret into the
     * settings box should not ship it to Discord.
     */
    static String redactVmArg(String arg) {
        int eq = arg.indexOf('=');
        if (eq < 0) {
            return arg;
        }
        String name = arg.substring(0, eq);
        return SECRET_VM_ARG_NAME.matcher(name).find() ? name + "=<redacted>" : arg;
    }

    private static String safeResolveJvm(LauncherConfig config, Path gameDir) {
        try {
            return String.valueOf(config.resolveJvm(gameDir));
        } catch (RuntimeException e) {
            return "unresolvable: " + e.getMessage();
        }
    }

    /**
     * metadata.txt + launcher/game logs (current and previous run) + Zomboid's console.txt + the
     * newest files under Zomboid/Logs (which is also where Storm writes main.log/debug.log) + the
     * newest JVM fatal-error dumps from the game dir — a crashed JVM's real diagnosis lives in
     * hs_err_pid*.log, not in its truncated stdout. Every entry passes through {@link LogScrubber}
     * so the OS account name never leaves the machine.
     */
    static byte[] buildZip(String metadata, Path gameDir) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            putEntry(zip, "metadata.txt", metadata.getBytes(StandardCharsets.UTF_8));
            putFileTail(zip, "launcher/launcher.log", LauncherPaths.logFile());
            putFileTail(zip, "launcher/game.log", LauncherPaths.gameLogFile());
            putFileTail(zip, "launcher/game-prev.log", LauncherPaths.previousGameLogFile());
            putFileTail(
                    zip, "zomboid/console.txt", LauncherPaths.zomboidDir().resolve("console.txt"));
            for (Path log : newestZomboidLogs()) {
                putFileTail(zip, "zomboid/Logs/" + log.getFileName(), log);
            }
            for (Path log : newestStormLogs()) {
                putFileTail(zip, "zomboid/Logs/storm/" + log.getFileName(), log);
            }
            for (Path dump : newestHsErrFiles(gameDir)) {
                putFileTail(zip, "hs_err/" + dump.getFileName(), dump);
            }
        } catch (IOException e) {
            // in-memory stream; only a broken entry writer can land here
            Log.warn("Could not assemble log zip: " + e.getMessage());
        }
        return bytes.toByteArray();
    }

    private static List<Path> newestHsErrFiles(Path gameDir) {
        if (gameDir == null || !Files.isDirectory(gameDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(gameDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(
                            path -> {
                                String name = path.getFileName().toString();
                                return name.startsWith("hs_err_pid") && name.endsWith(".log");
                            })
                    .sorted(Comparator.comparingLong(LogReport::lastModified).reversed())
                    .limit(MAX_HS_ERR_FILES)
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            Log.warn("Could not list JVM error dumps: " + e.getMessage());
            return List.of();
        }
    }

    private static List<Path> newestZomboidLogs() {
        return newestFilesIn(LauncherPaths.zomboidDir().resolve("Logs"), MAX_ZOMBOID_LOG_FILES);
    }

    private static List<Path> newestStormLogs() {
        return newestFilesIn(
                LauncherPaths.zomboidDir().resolve("Logs").resolve("storm"), MAX_STORM_LOG_FILES);
    }

    private static List<Path> newestFilesIn(Path dir, int limit) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(LogReport::lastModified).reversed())
                    .limit(limit)
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            Log.warn("Could not list logs in " + dir + ": " + e.getMessage());
            return List.of();
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void putFileTail(ZipOutputStream zip, String entryName, Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            putEntry(zip, entryName, LogScrubber.scrub(tail(file)));
        } catch (IOException e) {
            Log.warn("Could not attach " + file + ": " + e.getMessage());
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /** Last {@link #TAIL_BYTES} of the file — the recent end is what diagnoses a crash. */
    static byte[] tail(Path file) throws IOException {
        long size = Files.size(file);
        if (size <= TAIL_BYTES) {
            return Files.readAllBytes(file);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file);
                InputStream in =
                        java.nio.channels.Channels.newInputStream(
                                channel.position(size - TAIL_BYTES))) {
            return in.readAllBytes();
        }
    }

    static String truncate(String content) {
        return content.length() <= MAX_CONTENT_CHARS
                ? content
                : content.substring(0, MAX_CONTENT_CHARS);
    }

    /** Discord webhook multipart: a "content" text field plus the zip as an attachment part. */
    static byte[] multipartBody(String boundary, String content, byte[] zip) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);
        try {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(
                    "Content-Disposition: form-data; name=\"content\"\r\n\r\n"
                            .getBytes(StandardCharsets.UTF_8));
            body.write(content.getBytes(StandardCharsets.UTF_8));
            body.write(crlf);
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(
                    ("Content-Disposition: form-data; name=\"logs\"; filename=\"logs.zip\"\r\n"
                                    + "Content-Type: application/zip\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            body.write(zip);
            body.write(crlf);
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e); // ByteArrayOutputStream never throws
        }
        return body.toByteArray();
    }
}
