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
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.environment().putAll(environment);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(gameLog.toFile()));
            return pb.start();
        }
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
        Path gameDir = config.resolveGameDir();
        if (gameDir == null) {
            throw new IOException(
                    "Project Zomboid install not found — set the game directory"
                            + " in Settings (needs ProjectZomboid64.json inside).");
        }
        PzGameJson gameJson = PzGameJson.read(gameDir);
        Path jvm = config.resolveJvm(gameDir);
        boolean windows = isWindowsJvm(jvm);

        List<String> warnings = new ArrayList<>();
        List<String> command = new ArrayList<>();
        command.add(jvm.toString());
        // Overlay args follow the TARGET JVM's platform: spawning the Windows game
        // JVM from WSL should still get the modern-Windows overlay (e.g. ZGC).
        String osName = System.getProperty("os.name", "");
        String osVersion = System.getProperty("os.version", "");
        if (windows && !osName.toLowerCase().contains("win")) {
            osName = "Windows";
            osVersion = "10.0.99999";
        }
        command.addAll(gameJson.effectiveVmArgs(osName, osVersion));

        // placed after the game json's stock -Xmx because the later -Xmx wins in HotSpot
        int memoryGb = config.resolveMemoryGb();
        if (memoryGb > 0 && !specifiesXmx(config, profile)) {
            command.add("-Xmx" + memoryGb + "g");
        }

        Path bootstrapDir = config.resolveBootstrapDir(gameDir);
        if (bootstrapDir != null) {
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

        // user-supplied args go last so they win over anything above
        command.addAll(config.globalVmArgs);
        if (profile != null) {
            command.addAll(profile.extraVmArgs);
        }

        command.add("-cp");
        command.add(String.join(windows ? ";" : ":", gameJson.classpath));
        command.add(gameJson.mainClass);

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

    static boolean isWindowsJvm(Path jvm) {
        String path = jvm.toString().toLowerCase();
        if (path.endsWith(".exe")) {
            return true;
        }
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
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
