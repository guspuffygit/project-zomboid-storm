package io.pzstorm.storm.lua.functions.storm;

import io.pzstorm.storm.core.StormVersion;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaCallFrame;

public class StormVersionFunction implements JavaFunction {

    @Override
    public int call(LuaCallFrame luaCallFrame, int i) {
        luaCallFrame.push(StormVersion.getVersion());

        return 1;
    }
}
