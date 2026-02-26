package io.pzstorm.storm.lua.functions.storm;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaCallFrame;

public class StormDebugFunction implements JavaFunction {

    @Override
    public int call(LuaCallFrame luaCallFrame, int nArguments) {
        Object arg = luaCallFrame.get(0);
        String text = arg != null ? arg.toString() : "null";
        LOGGER.debug(text);

        return 0;
    }
}
