package io.pzstorm.storm.popman;

/** Java replacement for {@code zombie.popman.DebugCommands.n_debugCommand}. */
public final class StormDebugCommands {

    private StormDebugCommands() {}

    public static void n_debugCommand(int type, int cellX, int cellY) {
        StormPopMan.core().debugCommand(type, cellX, cellY);
    }
}
