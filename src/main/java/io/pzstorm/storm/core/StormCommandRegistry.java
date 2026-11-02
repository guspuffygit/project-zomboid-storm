package io.pzstorm.storm.core;

import io.pzstorm.storm.commands.CreateTestCharCommand;
import io.pzstorm.storm.commands.PingCommand;
import io.pzstorm.storm.commands.PrintDebugCommand;
import io.pzstorm.storm.commands.TestCountActionCommand;
import io.pzstorm.storm.commands.TestFactionChatStateCommand;
import io.pzstorm.storm.commands.TestStatsGetCommand;
import io.pzstorm.storm.commands.TestStormKahluaTableCommand;
import io.pzstorm.storm.commands.TestStormLuaCallFrameCommand;
import io.pzstorm.storm.commands.TestVehicleSoundRelevanceCommand;
import io.pzstorm.storm.commands.TestWhisperChatStateCommand;
import io.pzstorm.storm.commands.TestWorldMapVisibilityParityCommand;
import io.pzstorm.storm.mod.ZomboidMod;
import io.pzstorm.storm.util.StormEnv;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StormCommandRegistry {

    private static final List<Class<?>> MOD_COMMANDS = new ArrayList<>();

    /** Called by {@link io.pzstorm.storm.core.StormBootstrap#loadAndRegisterMods()} */
    public static void collectCommands() {
        MOD_COMMANDS.clear();
        for (Map.Entry<String, ZomboidMod> entry :
                StormModRegistry.getRegisteredModsById().entrySet()) {
            List<Class<?>> commands;
            try {
                commands = entry.getValue().getCommandClasses();
            } catch (RuntimeException | LinkageError e) {
                StormModRegistry.disableMod(entry.getKey(), "getCommandClasses", e);
                continue;
            }
            if (commands != null) {
                MOD_COMMANDS.addAll(commands);
            }
        }

        MOD_COMMANDS.add(PingCommand.class);
        MOD_COMMANDS.add(PrintDebugCommand.class);

        if (StormEnv.isStormTesting()) {
            MOD_COMMANDS.add(CreateTestCharCommand.class);
            MOD_COMMANDS.add(TestCountActionCommand.class);
            MOD_COMMANDS.add(TestStatsGetCommand.class);
            MOD_COMMANDS.add(TestStormLuaCallFrameCommand.class);
            MOD_COMMANDS.add(TestStormKahluaTableCommand.class);
            MOD_COMMANDS.add(TestWhisperChatStateCommand.class);
            MOD_COMMANDS.add(TestFactionChatStateCommand.class);
            MOD_COMMANDS.add(TestWorldMapVisibilityParityCommand.class);
            MOD_COMMANDS.add(TestVehicleSoundRelevanceCommand.class);
        }
    }

    /** Removes the commands a disabled mod contributed. */
    static void dropCommands(Set<Class<?>> modClasses) {
        MOD_COMMANDS.removeIf(modClasses::contains);
    }

    public static List<Class<?>> getModCommands() {
        return MOD_COMMANDS;
    }
}
