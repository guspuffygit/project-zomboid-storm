package io.pzstorm.storm.lua.functions.storm;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaCallFrame;

public class StormIsEnabledFunction implements JavaFunction {

    @Override
    public int call(LuaCallFrame luaCallFrame, int i) {
        luaCallFrame.push(true);

        return 1;
    }
}
