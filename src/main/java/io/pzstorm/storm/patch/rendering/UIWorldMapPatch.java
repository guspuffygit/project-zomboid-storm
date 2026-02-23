package io.pzstorm.storm.patch.rendering;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnWorldMapRenderEvent;
import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.wrappers.ui.UIWorldMapWrapper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.worldMap.WorldMapRenderer;

/** Patches {@link zombie.worldMap.UIWorldMap} */
public class UIWorldMapPatch extends StormClassTransformer {

    public UIWorldMapPatch() {
        super("zombie.worldMap.UIWorldMap");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(RenderRemotePlayersAdvice.class)
                        .on(ElementMatchers.named("renderRemotePlayers")));
    }

    public static class RenderRemotePlayersAdvice {
        /**
         * @param uiWorldMap {@link zombie.worldMap.UIWorldMap#renderer}
         */
        @Advice.OnMethodExit
        public static void onExit(
                @Advice.This Object uiWorldMap,
                @Advice.FieldValue("renderer") WorldMapRenderer renderer) {

            try {
                UIWorldMapWrapper uiWorldMapWrapper = new UIWorldMapWrapper(uiWorldMap);
                OnWorldMapRenderEvent event =
                        new OnWorldMapRenderEvent(uiWorldMapWrapper, renderer);

                StormEventDispatcher.dispatchEvent(event);
            } catch (Exception e) {
                StormLogger.LOGGER.error("Error in UIWorldMapRenderPatch: ", e);
            }
        }
    }
}
