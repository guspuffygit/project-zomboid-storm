package io.pzstorm.storm.patch;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnZomboidGlobalsLoadEvent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

public class ZomboidGlobalsPatch extends StormClassTransformer {

    public ZomboidGlobalsPatch() {
        super("zombie.ZomboidGlobals");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {

        return builder.visit(
                Advice.to(ZomboidGlobalsPatch.class).on(ElementMatchers.named("Load")));
    }

    @Advice.OnMethodExit
    public static void afterLoad() {
        LOGGER.debug("ZomboidGlobals.load()");
        StormEventDispatcher.dispatchEvent(new OnZomboidGlobalsLoadEvent());
    }
}
