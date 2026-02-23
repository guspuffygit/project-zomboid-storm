package io.pzstorm.storm.lua;

import zombie.Lua.LuaManager;

public class LuaManagerUtils {

    private LuaManagerUtils() {}

    public static StormKahluaTable getEnv() {
        return new StormKahluaTable(LuaManager.env);
    }
}
