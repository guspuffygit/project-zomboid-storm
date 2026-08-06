package io.pzstorm.launcher;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * All launcher state lives under {@code <user home>/Zomboid/storm/launcher} — the same {@code
 * Zomboid} directory the game itself uses, so wiping the game's data dir also resets the launcher.
 * Overridable for tests via {@code -Dstorm.launcher.zomboidDir}.
 */
public final class LauncherPaths {

    private LauncherPaths() {}

    public static Path zomboidDir() {
        String override = System.getProperty("storm.launcher.zomboidDir");
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), "Zomboid");
    }

    public static Path launcherDir() {
        return zomboidDir().resolve("storm").resolve("launcher");
    }

    public static Path configFile() {
        return launcherDir().resolve("launcher.json");
    }

    public static Path modsDir(String serverKey) {
        return launcherDir().resolve("mods").resolve(serverKey);
    }

    /**
     * Staged launcher copies ({@code stage/<hash>/storm-launcher.jar}) — the launcher runs from
     * here, never from inside the workshop item, so Steam can always update the item. See {@link
     * io.pzstorm.launcher.LauncherStage}.
     */
    public static Path stageDir() {
        return launcherDir().resolve("stage");
    }

    public static Path logFile() {
        return launcherDir().resolve("logs").resolve("launcher.log");
    }

    public static Path gameLogFile() {
        return launcherDir().resolve("logs").resolve("game.log");
    }

    /**
     * One-shot credential handoff, read and immediately deleted by Storm's client Java ({@code
     * io.pzstorm.storm.client.LauncherAutoJoin}) at the first main menu. The game JVM learns this
     * path via {@code -Dstorm.autojoin.file}; without that property the file is inert, so a stale
     * handoff can never fire on a manually started game.
     */
    public static Path autoJoinFile() {
        return launcherDir().resolve("autojoin.properties");
    }
}
