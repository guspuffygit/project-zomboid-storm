package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and spawns the actual game-client JVM. The launcher process loads zero Project Zomboid
 * classes — it only assembles a command line (from the game's own ProjectZomboid64.json plus
 * Storm's agent flags and the {@code +connect} args) and hands off to a fresh JVM with the game
 * directory as working directory.
 */
public final class GameLaunch {

    /**
     * System property the game JVM receives; Storm's mod loader scans this directory as an
     * additional mods root. Keep in sync with {@code
     * io.pzstorm.storm.core.StormPaths#LAUNCHER_MODS_PROPERTY}.
     */
    public static final String MODS_DIR_PROPERTY = "storm.launcher.mods";

    /**
     * Points the game JVM at the one-shot credential handoff; Storm's client Java reads and deletes
     * it at the first main menu, then drives the connect popup. Keep in sync with {@code
     * io.pzstorm.storm.client.LauncherAutoJoin#AUTOJOIN_FILE_PROPERTY}.
     */
    public static final String AUTOJOIN_FILE_PROPERTY = "storm.autojoin.file";

    public static final class LaunchPlan {
        public final List<String> command;
        public final Path workingDir;
        public final List<String> warnings;

        LaunchPlan(List<String> command, Path workingDir, List<String> warnings) {
            this.command = command;
            this.workingDir = workingDir;
            this.warnings = warnings;
        }

        public Process start(Path gameLog) throws IOException {
            Files.createDirectories(gameLog.getParent());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(gameLog.toFile()));
            return pb.start();
        }
    }

    public static LaunchPlan plan(LauncherConfig config, ServerProfile profile, Path modsDir)
            throws IOException {
        return plan(config, profile, modsDir, null);
    }

    /**
     * A non-null {@code autoJoinFile} means the auto-join handoff is armed: the game JVM gets its
     * path as {@code -Dstorm.autojoin.file} and vanilla's {@code +connect} args are suppressed —
     * Storm's client Java drives the whole connect, and the vanilla path firing in parallel would
     * race it with a second popup flow.
     */
    public static LaunchPlan plan(
            LauncherConfig config, ServerProfile profile, Path modsDir, Path autoJoinFile)
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

        Path bootstrapDir = config.resolveBootstrapDir(gameDir);
        if (bootstrapDir != null) {
            command.add(agentArg(bootstrapDir, jvm));
            if (LauncherConfig.isLocalDevBootstrap(bootstrapDir)) {
                command.add("-DstormType=local");
            }
        } else {
            warnings.add(
                    "Storm bootstrap not found — launching WITHOUT Storm."
                            + " Set the bootstrap directory in Settings to enable java mods.");
        }

        if (modsDir != null) {
            command.add("-D" + MODS_DIR_PROPERTY + "=" + pathArgFor(jvm, modsDir.toAbsolutePath()));
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

        if (profile != null) {
            if (autoJoinFile == null) {
                command.add("+connect");
                command.add(profile.connectAddress());
                if (!profile.serverPassword.isEmpty()) {
                    command.add("+password");
                    command.add(profile.serverPassword);
                }
            }
            if (profile.noSteam) {
                command.add("-nosteam");
            }
        }
        return new LaunchPlan(command, gameDir, warnings);
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
