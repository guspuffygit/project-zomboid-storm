package io.pzstorm.storm.core;

import io.pzstorm.storm.commands.CreateTestCharCommand;
import io.pzstorm.storm.commands.PingCommand;
import io.pzstorm.storm.commands.PrintDebugCommand;
import io.pzstorm.storm.commands.ScreenshotCommand;
import io.pzstorm.storm.commands.TestActionRemoveBugCommand;
import io.pzstorm.storm.commands.TestCountActionCommand;
import io.pzstorm.storm.commands.TestSeedActionCommand;
import io.pzstorm.storm.mod.ZomboidMod;
import io.pzstorm.storm.util.StormEnv;
import java.util.ArrayList;
import java.util.List;

public class StormCommandRegistry {

    private static final List<Class<?>> MOD_COMMANDS = new ArrayList<>();

    /** Called by {@link io.pzstorm.storm.core.StormBootstrap#loadAndRegisterMods()} */
    public static void collectCommands() {
        MOD_COMMANDS.clear();
        for (ZomboidMod mod : StormModRegistry.getRegisteredMods()) {
            List<Class<?>> commands = mod.getCommandClasses();
            if (commands != null) {
                MOD_COMMANDS.addAll(commands);
            }
        }

        MOD_COMMANDS.add(PingCommand.class);
        MOD_COMMANDS.add(PrintDebugCommand.class);
        MOD_COMMANDS.add(ScreenshotCommand.class);

        if (StormEnv.isStormTesting()) {
            MOD_COMMANDS.add(CreateTestCharCommand.class);
            MOD_COMMANDS.add(TestActionRemoveBugCommand.class);
            MOD_COMMANDS.add(TestSeedActionCommand.class);
            MOD_COMMANDS.add(TestCountActionCommand.class);
        }
    }

    public static List<Class<?>> getModCommands() {
        return MOD_COMMANDS;
    }
}
