package io.pzstorm.storm.event.zomboid;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.ZomboidEvent;
import io.pzstorm.storm.wrappers.ui.UIWorldMapWrapper;
import lombok.Getter;
import zombie.core.math.PZMath;
import zombie.core.textures.Texture;
import zombie.ui.TextManager;
import zombie.ui.UIFont;
import zombie.util.StringUtils;
import zombie.worldMap.WorldMapRenderer;

@Getter
public class OnWorldMapRenderEvent implements ZomboidEvent {

    private final UIWorldMapWrapper uiWorldMap;
    private final WorldMapRenderer renderer;

    public OnWorldMapRenderEvent(UIWorldMapWrapper uiWorldMap, WorldMapRenderer renderer) {
        this.uiWorldMap = uiWorldMap;
        this.renderer = renderer;
    }

    @Override
    public String getName() {
        return this.getClass().getName();
    }

    public void renderPoint(float x, float y) {
        try {
            float var3 = renderer.getDisplayZoomF();
            float var4 = renderer.getCenterWorldX();
            float var5 = renderer.getCenterWorldY();
            float var6 =
                    renderer.worldToUIX(
                            x,
                            y,
                            var3,
                            var4,
                            var5,
                            renderer.getProjectionMatrix(),
                            renderer.getModelViewMatrix());
            float var7 =
                    renderer.worldToUIY(
                            x,
                            y,
                            var3,
                            var4,
                            var5,
                            renderer.getProjectionMatrix(),
                            renderer.getModelViewMatrix());
            var6 = PZMath.floor(var6);
            var7 = PZMath.floor(var7);
            uiWorldMap.DrawTextureScaledColor(
                    (Texture) null,
                    (double) var6 - 3.0D,
                    (double) var7 - 3.0D,
                    6.0D,
                    6.0D,
                    1.0D,
                    0.0D,
                    0.0D,
                    1.0D);
        } catch (Exception e) {
            LOGGER.error("Error rendering point", e);
        }
    }

    public void renderName(float x, float y, String name) {
        try {
            if (!StringUtils.isNullOrWhitespace(name)) {
                float var4 = renderer.getDisplayZoomF();
                float var5 = renderer.getCenterWorldX();
                float var6 = renderer.getCenterWorldY();
                float var7 =
                        renderer.worldToUIX(
                                x,
                                y,
                                var4,
                                var5,
                                var6,
                                renderer.getProjectionMatrix(),
                                renderer.getModelViewMatrix());
                float var8 =
                        renderer.worldToUIY(
                                x,
                                y,
                                var4,
                                var5,
                                var6,
                                renderer.getProjectionMatrix(),
                                renderer.getModelViewMatrix());
                var7 = PZMath.floor(var7);
                var8 = PZMath.floor(var8);
                int var9 = TextManager.instance.MeasureStringX(UIFont.Small, name) + 16;
                int var10 = TextManager.instance.font.getLineHeight();
                int var11 = (int) Math.ceil((double) var10 * 1.25D);
                uiWorldMap.DrawTextureScaledColor(
                        (Texture) null,
                        (double) var7 - (double) var9 / 2.0D,
                        (double) var8 + 4.0D,
                        (double) var9,
                        (double) var11,
                        0.5D,
                        0.5D,
                        0.5D,
                        0.5D);
                uiWorldMap.DrawTextCentre(
                        name,
                        (double) var7,
                        (double) (var8 + 4.0F) + (double) (var11 - var10) / 2.0D,
                        0.0D,
                        0.0D,
                        0.0D,
                        1.0D);
            }
        } catch (Exception e) {
            LOGGER.error("Unable to render name {}", name, e);
        }
    }

    public void renderPointWithName(float x, float y, String name) {
        try {
            renderPoint(x, y);
            renderName(x, y, name);
        } catch (Exception e) {
            LOGGER.error("Unable to render name", e);
        }
    }
}
