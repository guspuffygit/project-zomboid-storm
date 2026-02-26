package io.pzstorm.storm.lua.functions.storm;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

/** Methods available in Lua */
public class StormLua {

    public static void setupStormLuaFunctions() {
        LOGGER.debug("Setting up Storm Lua table and functions");
        KahluaTable stormTable = LuaManager.platform.newTable();

        stormTable.rawset("isEnabled", new StormIsEnabledFunction());
        stormTable.rawset("getVersion", new StormVersionFunction());
        stormTable.rawset("debug", new StormDebugFunction());

        LuaManager.env.rawset("Storm", stormTable);
    }
}
