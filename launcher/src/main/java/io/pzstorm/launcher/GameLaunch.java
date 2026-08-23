package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and spawns the actual game-client JVM. The launcher process loads zero Project Zomboid
 * classes — it only assembles a command line (from the game's own ProjectZomboid64.json plus
 * Storm's agent flags and the {@code +connect} args) and hands off to a fresh JVM with the game
 * directory as working directory.
 */
public final class GameLaunch {

    /**
     * Points the game JVM at the one-shot credential handoff; Storm's client Java reads and deletes
     * it at the first main menu, then drives the connect popup. Keep in sync with {@code
     * io.pzstorm.storm.client.LauncherAutoJoin#AUTOJOIN_FILE_PROPERTY}.
     */
    public static final String AUTOJOIN_FILE_PROPERTY = "storm.autojoin.file";

    /**
     * Enables Storm's experimental client-side performance patches; the launcher passes it by
     * default. Keep in sync with the gate in {@code io.pzstorm.storm.core.StormClassTransformers}.
     */
    public static final String CLIENT_PERF_PROPERTY = "storm.experimental.clientperf";

    /**
     * Skips the intro screens (photosensitivity warning, TIS/attribution/Storm logos, TOS) so the
     * game boots straight to the main menu; the launcher passes it by default. Keep in sync with
     * the gate in {@code io.pzstorm.storm.core.StormClassTransformers}.
     */
    public static final String SKIP_MENUS_PROPERTY = "storm.skipmenus";

    /**
     * The target server's PZ mod ids ({@code ;}-separated), queried before launch. Storm's client
     * only catalogs workshop-folder mods on this list; without the property it catalogs none. Keep
     * the name in sync with {@code io.pzstorm.storm.core.StormWorkshopModGate}.
     */
    public static final String WORKSHOP_MODS_PROPERTY = "storm.workshop.mods";

    /**
     * A game JVM started with the Storm agent but without this property set to false hands itself
     * off to the launcher and exits — that is how the Steam Launch Options paste opens the
     * launcher. The game the launcher spawns must boot the game, not another launcher, so this is
     * always passed alongside the agent flag. Doubles as the player opt-out for direct boots. Keep
     * in sync with {@code io.pzstorm.storm.StormBootstrapper}.
     */
    public static final String HANDOFF_PROPERTY = "storm.launcher.handoff";

    public static final class LaunchPlan {
        public final List<String> command;
        public final Path workingDir;
        public final Map<String, String> environment;
        public final List<String> warnings;

        LaunchPlan(
                List<String> command,
                Path workingDir,
                Map<String, String> environment,
                List<String> warnings) {
            this.command = command;
            this.workingDir = workingDir;
            this.environment = environment;
            this.warnings = warnings;
        }

        public Process start(Path gameLog) throws IOException {
            Files.createDirectories(gameLog.getParent());
            rotate(gameLog);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.environment().putAll(environment);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(gameLog.toFile()));
            return pb.start();
        }

        /**
         * The previous run's output usually holds the crash that prompted this relaunch — keep one
         * generation back so a log report sent after a restart still carries it.
         */
        private static void rotate(Path gameLog) {
            try {
                if (Files.isRegularFile(gameLog) && Files.size(gameLog) > 0) {
                    Files.move(
                            gameLog,
                            previousLogOf(gameLog),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
                // a locked or unreadable old log never blocks the launch
            }
        }
    }

    /** {@code game.log} → {@code game-prev.log}, next to the original. */
    public static Path previousLogOf(Path gameLog) {
        String name = gameLog.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String prev =
                dot > 0 ? name.substring(0, dot) + "-prev" + name.substring(dot) : name + "-prev";
        return gameLog.resolveSibling(prev);
    }

    public static LaunchPlan plan(LauncherConfig config, ServerProfile profile) throws IOException {
        return plan(config, profile, null);
    }

    /**
     * A non-null {@code autoJoinFile} means the auto-join handoff is armed: the game JVM gets its
     * path as {@code -Dstorm.autojoin.file} and vanilla's {@code +connect} args are suppressed —
     * Storm's client Java drives the whole connect, and the vanilla path firing in parallel would
     * race it with a second popup flow.
     */
    public static LaunchPlan plan(LauncherConfig config, ServerProfile profile, Path autoJoinFile)
            throws IOException {
        return plan(config, profile, autoJoinFile, null);
    }

    /**
     * A non-null {@code serverMods} is the joined server's mod-id list; it rides along as {@code
     * -Dstorm.workshop.mods} so the game's Storm only loads those workshop mods. Null (list never
     * obtained, or a plain launch to the main menu) sends nothing — Storm then loads no workshop
     * mods at all.
     */
    public static LaunchPlan plan(
            LauncherConfig config,
            ServerProfile profile,
            Path autoJoinFile,
            List<String> serverMods)
            throws IOException {
        Path gameDir = config.resolveGameDir();
        if (gameDir == null) {
            throw new IOException(
                    "Project Zomboid install not found — set the game directory"
                            + " in Settings (needs ProjectZomboid64.json or"
                            + " projectzomboid.jar inside).");
        }
        PzGameJson gameJson = PzGameJson.read(gameDir);
        Path jvm = config.resolveJvm(gameDir);
        boolean windows = isWindowsJvm(jvm);
        Path exeLauncher = exeLauncher(config, gameDir, windows);
        boolean useExe = exeLauncher != null;

        List<String> warnings = new ArrayList<>();
        List<String> command = new ArrayList<>();
        command.add(useExe ? exeLauncher.toString() : jvm.toString());
        if (!useExe) {
            // Overlay args follow the TARGET JVM's platform: spawning the Windows game
            // JVM from WSL should still get the modern-Windows overlay (e.g. ZGC).
            // In the exe path we skip these — ProjectZomboid64.exe reads its own
            // ProjectZomboid64.json for the base vmArgs and picks its own overlay.
            String osName = System.getProperty("os.name", "");
            String osVersion = System.getProperty("os.version", "");
            if (windows && !osName.toLowerCase().contains("win")) {
                osName = "Windows";
                osVersion = "10.0.99999";
            }
            command.addAll(gameJson.effectiveVmArgs(osName, osVersion));
        }

        // placed after the game json's stock -Xmx because the later -Xmx wins in HotSpot
        int memoryGb = config.resolveMemoryGb();
        if (memoryGb > 0 && !specifiesXmx(config, profile)) {
            command.add("-Xmx" + memoryGb + "g");
            if (!specifiesXms(config, profile)) {
                // commit and fault the whole heap at JVM init: a machine that cannot back
                // -Xmx fails at launch with a clear error instead of dying hours into a
                // session as a native OOM once the collector commits toward the max
                command.add("-Xms" + memoryGb + "g");
                command.add("-XX:+AlwaysPreTouch");
            }
        }

        Path bootstrapDir = config.resolveBootstrapDir(gameDir);
        if (bootstrapDir != null) {
            StaleStormJarCleanup.run(bootstrapDir);
            command.add(agentArg(bootstrapDir, jvm));
            command.add("-D" + HANDOFF_PROPERTY + "=false");
            if (LauncherConfig.isLocalDevBootstrap(bootstrapDir)) {
                command.add("-DstormType=local");
            }
            if (config.clientPerfFixes && !specifiesClientPerf(config, profile)) {
                command.add("-D" + CLIENT_PERF_PROPERTY + "=true");
            }
            if (config.skipMenus && !specifiesSkipMenus(config, profile)) {
                command.add("-D" + SKIP_MENUS_PROPERTY + "=true");
            }
        } else {
            warnings.add(
                    "Storm bootstrap not found — launching WITHOUT Storm."
                            + " Set the bootstrap directory in Settings to enable java mods.");
        }

        if (autoJoinFile != null) {
            command.add(
                    "-D"
                            + AUTOJOIN_FILE_PROPERTY
                            + "="
                            + pathArgFor(jvm, autoJoinFile.toAbsolutePath()));
        }

        if (serverMods != null && !serverMods.isEmpty()) {
            command.add("-D" + WORKSHOP_MODS_PROPERTY + "=" + String.join(";", serverMods));
        }

        // user-supplied args go last so they win over anything above
        command.addAll(config.globalVmArgs);
        if (profile != null) {
            command.addAll(profile.extraVmArgs);
        }

        if (!useExe) {
            command.add("-cp");
            command.add(String.join(windows ? ";" : ":", gameJson.classpath));
            command.add(gameJson.mainClass);
        } else {
            // ProjectZomboid64.exe splits CLI args on `--`: everything before is forwarded to
            // jvm.dll as JVM args, everything after goes to main(). WITHOUT `--`, the exe
            // sends every CLI arg to main() and the JVM sees only the JSON's vmArgs — so
            // -agentpath / -Xmx / -Dstorm.* silently no-op ("unknown option" in game.log).
            // The vanilla Steam Launch Options paste ends in `--` for the same reason.
            command.add("--");
        }

        if (profile != null && autoJoinFile == null) {
            command.add("+connect");
            command.add(profile.connectAddress());
            if (!profile.serverPassword.isEmpty()) {
                command.add("+password");
                command.add(profile.serverPassword);
            }
        }
        return new LaunchPlan(command, gameDir, nativeEnvironment(gameDir, windows), warnings);
    }

    /** User args win: an explicit -Dstorm.experimental.clientperf=… suppresses the default. */
    static boolean specifiesClientPerf(LauncherConfig config, ServerProfile profile) {
        return anyUserArgStartsWith(config, profile, "-D" + CLIENT_PERF_PROPERTY);
    }

    /** User args win: an explicit -Dstorm.skipmenus=… suppresses the default. */
    static boolean specifiesSkipMenus(LauncherConfig config, ServerProfile profile) {
        return anyUserArgStartsWith(config, profile, "-D" + SKIP_MENUS_PROPERTY);
    }

    /** User args win: an explicit -Xmx in either JVM-args field suppresses the managed heap. */
    static boolean specifiesXmx(LauncherConfig config, ServerProfile profile) {
        return anyUserArgStartsWith(config, profile, "-Xmx");
    }

    /** User args win: an explicit -Xms suppresses the managed commit-at-boot pair. */
    static boolean specifiesXms(LauncherConfig config, ServerProfile profile) {
        return anyUserArgStartsWith(config, profile, "-Xms");
    }

    private static boolean anyUserArgStartsWith(
            LauncherConfig config, ServerProfile profile, String prefix) {
        List<String> args = new ArrayList<>(config.globalVmArgs);
        if (profile != null) {
            args.addAll(profile.extraVmArgs);
        }
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vanilla's projectzomboid.sh exports these before starting the game: transitive native
     * dependencies (fmod, steam_api) resolve through the loader path, not java.library.path, so a
     * direct JVM spawn needs them too. Windows resolves DLLs from the working directory already.
     */
    static Map<String, String> nativeEnvironment(Path gameDir, boolean windowsJvm) {
        Map<String, String> env = new LinkedHashMap<>();
        if (windowsJvm) {
            return env;
        }
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        List<String> libDirs = new ArrayList<>();
        Path[] dirs = {
            gameDir.resolve("linux64"),
            gameDir,
            gameDir.resolve(Paths.get("jre64", "lib")),
            gameDir.resolve(Paths.get("jre64", "Contents", "Home", "lib")),
        };
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                libDirs.add(dir.toString());
            }
        }
        String var = mac ? "DYLD_LIBRARY_PATH" : "LD_LIBRARY_PATH";
        String existing = System.getenv(var);
        if (existing != null && !existing.isEmpty()) {
            libDirs.add(existing);
        }
        if (!libDirs.isEmpty()) {
            env.put(var, String.join(":", libDirs));
        }
        if (mac) {
            // The bootstrap agent strips Steam's overlay injection (DYLD_INSERT_LIBRARIES) from
            // the launcher process — the overlay's Metal hook crashes the Swing UI — and stashes
            // it here. Restore it for the game JVM, where the overlay belongs. Keep the stash
            // name in sync with io.pzstorm.storm.StormBootstrapper.
            String overlay = System.getenv("STORM_GAME_DYLD_INSERT_LIBRARIES");
            if (overlay != null
                    && !overlay.isEmpty()
                    && System.getenv("DYLD_INSERT_LIBRARIES") == null) {
                env.put("DYLD_INSERT_LIBRARIES", overlay);
            }
        }
        if (!mac) {
            List<String> preloads = new ArrayList<>();
            String preloadExisting = System.getenv("LD_PRELOAD");
            if (preloadExisting != null && !preloadExisting.isEmpty()) {
                preloads.add(preloadExisting);
            }
            if (Files.isRegularFile(gameDir.resolve(Paths.get("jre64", "lib", "libjsig.so")))) {
                preloads.add("libjsig.so");
            }
            if (Files.isRegularFile(gameDir.resolve("libPZXInitThreads64.so"))) {
                preloads.add("libPZXInitThreads64.so");
            }
            if (!preloads.isEmpty()) {
                env.put("LD_PRELOAD", String.join(":", preloads));
            }
        }
        return env;
    }

    static String agentArg(Path bootstrapDir, Path jvm) {
        if (isWindowsJvm(jvm) && Files.isRegularFile(bootstrapDir.resolve("agentlib.dll"))) {
            // the dll resolves the jar option relative to its own directory
            return "-agentpath:"
                    + pathArgFor(jvm, bootstrapDir.resolve("agentlib.dll"))
                    + "=storm-bootstrap.jar";
        }
        return "-javaagent:" + pathArgFor(jvm, bootstrapDir.resolve("storm-bootstrap.jar"));
    }

    /**
     * Argument strings are not translated by WSL interop (unlike cwd), so when a Windows JVM is
     * invoked from WSL, /mnt/<drive>/… must become <Drive>:\… .
     */
    static String pathArgFor(Path jvm, Path path) {
        String raw = path.toString();
        if (!isWindowsJvm(jvm) || !raw.startsWith("/mnt/")) {
            return raw;
        }
        String[] parts = raw.split("/", 4);
        if (parts.length >= 4 && parts[2].length() == 1) {
            return Character.toUpperCase(parts[2].charAt(0)) + ":\\" + parts[3].replace('/', '\\');
        }
        return raw;
    }

    /**
     * Every child's output is read back as UTF-8. Without this a child on Windows writes its
     * streams in the console codepage instead, and anything outside ASCII reaches the log mangled.
     */
    static void addChildEncodingArgs(List<String> command) {
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
    }

    /**
     * ProjectZomboid64.exe, when we should spawn it in place of java.exe. The exe forwards every
     * CLI JVM arg into its embedded {@code jvm.dll} (that is how the Steam {@code -agentpath} paste
     * has always loaded Storm) AND carries the "System DPI Aware" Windows manifest that vanilla
     * users' UI is calibrated for. {@code jre64/bin/java.exe} declares "Per-Monitor V2" instead, so
     * a direct spawn reports different pixel dims on any fractional-scale display — auto-detected
     * moodle/sidebar sizes balloon and the in-vehicle zoom stops matching what the player
     * configured.
     *
     * <p>Only when the launched process is a Windows JVM, the exe is present, and the user has not
     * pinned an explicit {@code jvmPath} — a custom JVM is a request to bypass the game's bundled
     * one, which bypasses the exe too.
     */
    static Path exeLauncher(LauncherConfig config, Path gameDir, boolean windowsJvm) {
        if (!windowsJvm || !config.jvmPath.isEmpty()) {
            return null;
        }
        Path exe = gameDir.resolve("ProjectZomboid64.exe");
        return Files.isRegularFile(exe) ? exe : null;
    }

    static boolean isWindowsJvm(Path jvm) {
        String path = jvm.toString().toLowerCase();
        if (path.endsWith(".exe")) {
            return true;
        }
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }

    /**
     * Just the JVM args (between the launched binary and the program's own args) — the full command
     * line buries them. Boundary is {@code -cp} in the direct-java path and {@code --} in the
     * ProjectZomboid64.exe path (the exe's JVM/program-arg separator); falls back to {@code
     * +connect} then end-of-command.
     */
    public static String describeJvmArgs(LaunchPlan plan) {
        int end = plan.command.indexOf("-cp");
        if (end < 0) {
            end = plan.command.indexOf("--");
        }
        if (end < 0) {
            end = plan.command.indexOf("+connect");
        }
        if (end < 0) {
            end = plan.command.size();
        }
        return String.join(" ", plan.command.subList(1, Math.max(1, end)));
    }

    /** Render for logs with the server password masked. */
    public static String describe(LaunchPlan plan) {
        StringBuilder sb = new StringBuilder();
        boolean maskNext = false;
        for (String arg : plan.command) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(maskNext ? "*****" : arg);
            maskNext = arg.equals("+password");
        }
        return sb.toString();
    }
}
