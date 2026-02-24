package io.pzstorm.storm.patch.lua;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;

public class LuaPatchUtils {
    public static boolean doesLuaFunctionExist(String tableName, String functionName) {
        KahluaTable env = LuaManager.env;
        if (env == null) {
            return false;
        }

        Object tableObj = env.rawget(tableName);

        if (!(tableObj instanceof KahluaTable table)) {
            return false;
        }

        Object functionObj = table.rawget(functionName);

        // 5. Check if the function exists (is not null)
        return functionObj != null;
    }

    public static void injectLuaCode(String luaCode, String fileName) {
        try {
            LOGGER.debug("Injecting luacode: {}", fileName);

            LuaClosure closure = LuaCompiler.loadstring(luaCode, fileName, LuaManager.env);
            LuaManager.caller.pcall(LuaManager.thread, closure);
        } catch (Exception e) {
            LOGGER.error("Unable to inject lua code: {}", fileName);
            LOGGER.error("Code to inject:\n{}", luaCode, e);
        }
    }
}
