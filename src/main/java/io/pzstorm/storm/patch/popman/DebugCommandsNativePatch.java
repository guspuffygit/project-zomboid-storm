package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.popman.StormDebugCommands;

/** Binds {@code zombie.popman.DebugCommands.n_debugCommand} to {@link StormDebugCommands}. */
public class DebugCommandsNativePatch extends NativeFacadePatch {

    static final String[] NATIVES = {"n_debugCommand"};

    public DebugCommandsNativePatch() {
        super("zombie.popman.DebugCommands", StormDebugCommands.class, NATIVES);
    }
}
