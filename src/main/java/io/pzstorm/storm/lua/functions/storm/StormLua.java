package io.pzstorm.storm.lua.functions.storm;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

/** Methods available in Lua */
public class StormLua {

    public static void setupStormLuaFunctions() {
        KahluaTable stormTable = LuaManager.platform.newTable();

        stormTable.rawset("isEnabled", new StormIsEnabledFunction());
        stormTable.rawset("getVersion", new StormVersionFunction());

        LuaManager.env.rawset("Storm", stormTable);
    }
}
