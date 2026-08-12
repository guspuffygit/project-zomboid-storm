package io.pzstorm.launcher;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Attempts to stop and restart the Steam client on the user's behalf when a workshop update stalls.
 * Windows only — the launcher targets Windows players, and every OS-native step here (taskkill,
 * {@code reg query}) is Windows-specific. On other platforms {@link #isSupported()} returns false
 * and the calling UI falls back to manual instructions.
 *
 * <p>Every step reports to a {@link Consumer} so the calling UI can stream progress; nothing is
 * thrown for expected failures — the caller reads {@link Result#ok} and falls back on failure.
 */
public final class SteamRestart {

    private static final long STOP_WAIT_SECONDS = 15;
    private static final long HARD_STOP_WAIT_SECONDS = 10;
    private static final long POLL_MILLIS = 500;

    private SteamRestart() {}

    public static final class Result {
        public final boolean ok;
        public final String failureReason;

        Result(boolean ok, String failureReason) {
            this.ok = ok;
            this.failureReason = failureReason;
        }

        public static Result success() {
            return new Result(true, null);
        }

        public static Result failed(String reason) {
            return new Result(false, reason);
        }
    }

    /** Auto-restart is only wired for the OS this launcher's players actually run on. */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Runs synchronously on the calling thread — the caller is expected to spawn a worker so the
     * UI stays responsive.
     */
    public static Result attemptRestart(Consumer<String> log) {
        if (!isSupported()) {
            return Result.failed(
                    "Automatic Steam restart is only supported on Windows on this launcher.");
        }
        try {
            String runningExe = runningSteamExe();
            if (runningExe != null) {
                log.accept("Asking Steam to shut down (steam://exit) …");
                boolean sent = sendSteamUri("steam://exit", log);
                if (!sent) {
                    log.accept("Steam URL handler was unavailable — trying command line …");
                    if (!run(log, "cmd", "/c", "start", "", "steam://exit")) {
                        return Result.failed(
                                "Could not tell Steam to exit — no Steam URL handler.");
                    }
                }
                log.accept("Waiting up to " + STOP_WAIT_SECONDS + "s for Steam to close …");
                if (!awaitSteamGone(STOP_WAIT_SECONDS)) {
                    log.accept("Steam did not close on its own — forcing it …");
                    if (!run(log, "taskkill", "/F", "/IM", "steam.exe")) {
                        return Result.failed("taskkill could not stop steam.exe.");
                    }
                    if (!awaitSteamGone(HARD_STOP_WAIT_SECONDS)) {
                        return Result.failed("Steam is still running after taskkill.");
                    }
                }
                log.accept("Steam has closed.");
                Thread.sleep(2000);
            } else {
                log.accept("Steam is not running — skipping shutdown.");
            }
            log.accept("Starting Steam …");
            if (!startSteam(log, runningExe)) {
                return Result.failed(
                        "Could not launch Steam automatically — please start it yourself.");
            }
            log.accept("Steam launch requested. Give it a few seconds to come back up, then");
            log.accept("click Join Server again.");
            return Result.success();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Interrupted while restarting Steam.");
        } catch (IOException e) {
            return Result.failed("Restart failed: " + e.getMessage());
        }
    }

    /** Full path to the running steam.exe, or {@code null} if Steam isn't running. */
    static String runningSteamExe() {
        return ProcessHandle.allProcesses()
                .map(ph -> ph.info().command().orElse(""))
                .filter(SteamRestart::looksLikeSteam)
                .findFirst()
                .orElse(null);
    }

    static boolean steamRunning() {
        return runningSteamExe() != null;
    }

    static boolean looksLikeSteam(String command) {
        if (command.isEmpty()) {
            return false;
        }
        String normalized = command.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = (slash < 0 ? normalized : normalized.substring(slash + 1)).toLowerCase();
        // steamwebhelper / steamservice / SteamVR are auxiliaries — only the real client counts
        return name.equals("steam.exe");
    }

    private static boolean awaitSteamGone(long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (!steamRunning()) {
                return true;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return !steamRunning();
    }

    static boolean sendSteamUri(String uri, Consumer<String> log) {
        if (!Desktop.isDesktopSupported()) {
            return false;
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            return false;
        }
        try {
            desktop.browse(new URI(uri));
            return true;
        } catch (Exception e) {
            log.accept("  (Desktop.browse failed: " + e.getMessage() + ")");
            return false;
        }
    }

    private static boolean startSteam(Consumer<String> log, String knownSteamExe) {
        try {
            String steamExe = knownSteamExe != null ? knownSteamExe : findWindowsSteamExe(log);
            if (steamExe != null) {
                log.accept("  launching " + steamExe);
                new ProcessBuilder(steamExe).start();
                return true;
            }
            return run(log, "cmd", "/c", "start", "", "steam://open/main");
        } catch (IOException | InterruptedException e) {
            log.accept("  (start failed: " + e.getMessage() + ")");
            return false;
        }
    }

    private static String findWindowsSteamExe(Consumer<String> log) {
        try {
            Process p =
                    new ProcessBuilder(
                                    "reg",
                                    "query",
                                    "HKCU\\Software\\Valve\\Steam",
                                    "/v",
                                    "SteamExe")
                            .redirectErrorStream(true)
                            .start();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int idx = line.indexOf("REG_SZ");
                    if (idx >= 0) {
                        return line.substring(idx + "REG_SZ".length()).trim();
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.accept("  (reg query for Steam path failed: " + e.getMessage() + ")");
        }
        return null;
    }

    private static boolean run(Consumer<String> log, String... command)
            throws IOException, InterruptedException {
        log.accept("  $ " + String.join(" ", command));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.accept("    " + line);
                }
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            log.accept("  (exit " + exit + ")");
            return false;
        }
        return true;
    }
}
