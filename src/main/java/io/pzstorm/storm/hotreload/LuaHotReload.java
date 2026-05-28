package io.pzstorm.storm.hotreload;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;

/**
 * Compiles and runs a Lua source string inside the game's {@link LuaManager#env} environment. Backs
 * the {@code POST /reload} endpoint of {@link HotReloadEndpoints}: the request body is the Lua
 * source, so no file on disk is involved.
 */
public final class LuaHotReload {

    private LuaHotReload() {}

    static String run(String luaSource) {
        try {
            LuaClosure closure =
                    LuaCompiler.loadstring(luaSource, "storm-hotreload", LuaManager.env);

            Object[] results =
                    LuaManager.caller.pcall(LuaManager.thread, closure, new Object[] {""});

            if (results[0] == Boolean.TRUE) {
                if (results.length > 1) {
                    String response = String.valueOf(results[1]);
                    LOGGER.debug("Lua hot-reload returned: {}", response);
                    return "OK: " + response;
                }
                return "OK";
            }
            String errorMessage = String.valueOf(results[1]);
            LOGGER.error("Lua hot-reload execution failed: {}", errorMessage);
            return "ERROR: " + errorMessage;
        } catch (Exception e) {
            LOGGER.error("Failed to run Lua hot-reload source", e);
            return "ERROR: " + e.getMessage();
        }
    }
}
