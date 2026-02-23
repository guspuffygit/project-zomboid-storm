package io.pzstorm.storm.patch.mod;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnLoadModsEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Patches {@link zombie.ZomboidFileSystem} */
public class ZomboidFileSystemPatch extends StormClassTransformer {

    public ZomboidFileSystemPatch() {
        super("zombie.ZomboidFileSystem");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(LoadModsAdvice.class)
                        .on(
                                ElementMatchers.named("loadMods")
                                        .and(ElementMatchers.takesArgument(0, ArrayList.class))));
    }

    public static class LoadModsAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) ArrayList<String> mods) {
            List<String> modsList = Objects.requireNonNullElse(mods, Collections.emptyList());
            LOGGER.debug("OnLoadMods: {}", String.join(" ", modsList));
            StormEventDispatcher.dispatchEvent(new OnLoadModsEvent(modsList));
        }
    }
}
