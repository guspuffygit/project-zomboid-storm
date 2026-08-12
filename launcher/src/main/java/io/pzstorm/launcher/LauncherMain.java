package io.pzstorm.launcher;

import io.pzstorm.launcher.ui.LauncherWindow;
import io.pzstorm.launcher.ui.StageSplash;
import io.pzstorm.launcher.ui.SteamRestartDialog;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Storm Launcher entry point. Runs before — and completely outside — the game: no Project Zomboid
 * classes are on this JVM's classpath, by design, so mod jars and Storm itself can be replaced
 * freely and the game only ever boots against the already-updated files.
 *
 * <p>Headless modes (mainly for scripting/CI): {@code --list}, {@code --print-launch <server>},
 * {@code --join <server>} where {@code <server>} is a profile name or {@code host:port}.
 */
public final class LauncherMain {

    private LauncherMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--steam-update")) {
            // child mode: no config, no log file — stdout goes to the parent
            String[] ids = new String[args.length - 1];
            System.arraycopy(args, 1, ids, 0, ids.length);
            System.exit(SteamUpdateChild.run(ids));
        }
        if (args.length > 0 && args[0].equals("--demo-steam-restart-popup")) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default L&F is fine
            }
            runSteamRestartDemo(args.length > 1 ? args[1] : "real");
            System.exit(0);
        }
        LauncherStage.Context stage = LauncherStage.parse(args);
        Log.init(LauncherPaths.logFile());
        if (!stage.staged() && stage.parentPid > 0) {
            // spawned by the bootstrap agent: the exiting game JVM still maps agentlib.dll,
            // and any Steam update of the item fails while it does
            LauncherStage.awaitParentExit(stage.parentPid);
        }
        LauncherStage.handOffIfInsideWorkshopItem(stage);
        LauncherConfig config = LauncherConfig.load(LauncherPaths.configFile());
        config.setStagedOrigin(stage.stagedFrom);
        clearStaleAutoJoin();
        syncServersWithGameDb(config);

        args = effectiveArgs(stage.args);
        if (args.length > 0) {
            if (args[0].equals("--join")) {
                LauncherStage.runStartupUpdate(config, stage);
            }
            runHeadless(config, args);
            return;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default L&F is fine
        }
        StageSplash splash = stage.staged() ? StageSplash.open() : null;
        try {
            LauncherStage.runStartupUpdate(config, stage);
        } finally {
            if (splash != null) {
                splash.close();
            }
        }
        // Steam launches the game JVM directly (via -agentpath) and the bootstrap agent then
        // exits so the launcher can update the workshop; Steam nevertheless usually keeps the
        // launcher javaw in the game's job object and directs Stop at it. Kill the tracked game
        // process when the launcher JVM is torn down so Stop takes the game with it. The launcher
        // window's close handler releases the reference first, so a user-initiated close still
        // leaves the game running independently.
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(GameProcessTracker::destroyCurrent, "storm-launcher-shutdown"));
        SwingUtilities.invokeLater(() -> new LauncherWindow(config).setVisible(true));
    }

    private static void runHeadless(LauncherConfig config, String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "--version":
                System.out.println(LauncherInfo.version());
                return;
            case "--list":
                for (ServerProfile profile : config.servers) {
                    System.out.println(profile);
                }
                return;
            case "--print-launch":
            case "--join":
                break;
            default:
                System.err.println("Unknown option: " + mode);
                System.err.println(
                        "Usage: storm-launcher"
                                + " [--list | --version | --print-launch <server>"
                                + " | --join <server>]");
                System.exit(2);
                return;
        }
        if (args.length < 2) {
            System.err.println(mode + " needs a server (profile name or host:port)");
            System.exit(2);
            return;
        }
        ServerProfile profile = findProfile(config, args[1]);
        if (profile == null) {
            System.err.println("No saved server matches '" + args[1] + "'");
            System.exit(2);
            return;
        }
        switch (mode) {
            case "--print-launch":
                {
                    GameLaunch.LaunchPlan plan = GameLaunch.plan(config, profile);
                    plan.warnings.forEach(w -> System.err.println("WARNING: " + w));
                    System.out.println(GameLaunch.describe(plan));
                    break;
                }
            case "--join":
                JoinFlow.join(config, profile);
                break;
            default:
                throw new AssertionError(mode);
        }
    }

    /**
     * Dev-only: render the Steam-restart popup with a stubbed backend so every variant of the
     * dialog can be seen on any OS. Variants: {@code manual} (no auto-restart offer), {@code
     * success} / {@code fail} (mock progress log), {@code real} (default, calls the real Windows
     * code — on non-Windows the dialog surfaces its "not supported" fallback).
     */
    private static void runSteamRestartDemo(String variant) {
        String summary =
                "Steam refused to update all 3 workshop item(s) this server needs, so the join"
                        + " was cancelled — the game would only get stuck at its workshop screen.";
        Runnable sendLogs = () -> System.out.println("[demo] Send Logs clicked");
        switch (variant) {
            case "manual":
                SteamRestartDialog.show(null, summary, log -> null, false, sendLogs);
                return;
            case "success":
                SteamRestartDialog.show(null, summary, fakeRestart(true), true, sendLogs);
                return;
            case "fail":
                SteamRestartDialog.show(null, summary, fakeRestart(false), true, sendLogs);
                return;
            case "progress-success":
                SteamRestartDialog.runAutoRestart(null, summary, fakeRestart(true), sendLogs);
                return;
            case "progress-fail":
                SteamRestartDialog.runAutoRestart(null, summary, fakeRestart(false), sendLogs);
                return;
            case "real":
            default:
                SteamRestartDialog.show(null, summary, sendLogs);
        }
    }

    private static java.util.function.Function<
                    java.util.function.Consumer<String>, SteamRestart.Result>
            fakeRestart(boolean succeed) {
        return log -> {
            demoStep(log, "Asking Steam to shut down (steam://exit) …", 400);
            demoStep(log, "Waiting up to 15s for Steam to close …", 800);
            demoStep(log, "Steam has closed.", 400);
            demoStep(log, "Starting Steam …", 600);
            if (!succeed) {
                demoStep(log, "  (start failed: Steam is not installed on this machine)", 300);
                return SteamRestart.Result.failed(
                        "Could not launch Steam automatically — please start it yourself.");
            }
            demoStep(
                    log,
                    "Steam launch requested. Give it a few seconds to come back up, then",
                    200);
            demoStep(log, "click Join Server again.", 100);
            return SteamRestart.Result.success();
        };
    }

    private static void demoStep(
            java.util.function.Consumer<String> log, String line, long pauseMs) {
        log.accept(line);
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Steam Launch Options interposition ({@code "…\StormLauncher.bat" %command%} on the game)
     * invokes the launcher with the vanilla game command appended as arguments. The launcher builds
     * its own command from the game's ProjectZomboid64.json, so that appended command is ignored
     * and the normal UI opens — Steam's Play button becomes the launcher's front door. All real
     * headless modes start with a {@code --} flag, which is how the two cases are told apart.
     */
    static String[] effectiveArgs(String[] args) {
        if (args.length > 0 && !args[0].startsWith("--")) {
            Log.info(
                    "Ignoring interposed game command ("
                            + args.length
                            + " args, first: "
                            + args[0]
                            + ") — opening the launcher UI.");
            return new String[0];
        }
        return args;
    }

    /**
     * Every start rebuilds the server list from the game's own saved-server database (the source of
     * truth for connection info and credentials — see {@link ServerStore}), joined with the
     * launcher-only extras kept in launcher.json. The json is rewritten only when reconciliation
     * actually changed it (first-run credential migration, servers added or removed in-game).
     */
    private static void syncServersWithGameDb(LauncherConfig config) {
        try {
            String before = Json.write(config.toMap());
            ServerStore.load(config);
            if (!Json.write(config.toMap()).equals(before)) {
                config.save(LauncherPaths.configFile());
            }
        } catch (Exception e) {
            Log.warn("Could not sync with the game's saved servers: " + e.getMessage());
        }
    }

    /**
     * A handoff that was never consumed (game crashed, join abandoned) must not auto-connect some
     * unrelated later session. Ten minutes comfortably covers launcher-to-popup latency.
     */
    private static void clearStaleAutoJoin() {
        try {
            Path file = LauncherPaths.autoJoinFile();
            if (java.nio.file.Files.isRegularFile(file)
                    && System.currentTimeMillis()
                                    - java.nio.file.Files.getLastModifiedTime(file).toMillis()
                            > 10 * 60 * 1000L) {
                java.nio.file.Files.delete(file);
                Log.info("Removed stale auto-connect handoff.");
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    static ServerProfile findProfile(LauncherConfig config, String selector) {
        for (ServerProfile profile : config.servers) {
            if (profile.name.equalsIgnoreCase(selector)
                    || profile.connectAddress().equalsIgnoreCase(selector)) {
                return profile;
            }
        }
        int colon = selector.lastIndexOf(':');
        if (colon > 0) {
            ServerProfile adHoc = new ServerProfile();
            adHoc.name = selector;
            adHoc.host = selector.substring(0, colon);
            try {
                adHoc.port = Integer.parseInt(selector.substring(colon + 1));
                return adHoc;
            } catch (NumberFormatException ignored) {
                // not host:port after all
            }
        }
        return null;
    }
}
