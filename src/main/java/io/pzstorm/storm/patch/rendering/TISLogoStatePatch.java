package io.pzstorm.storm.patch.rendering;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.util.StormUtils;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.IndieGL;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.gameStates.GameStateMachine;
import zombie.ui.UIManager;

public class TISLogoStatePatch extends StormClassTransformer {

    public TISLogoStatePatch() {
        super("zombie.gameStates.TISLogoState");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(Advice.to(UpdateAdvice.class).on(ElementMatchers.named("update")))
                .visit(Advice.to(RenderAdvice.class).on(ElementMatchers.named("render")));
    }

    public static class UpdateAdvice {
        @Advice.OnMethodExit
        public static void onExit(
                @Advice.FieldValue(value = "screenNumber", readOnly = false) int screenNumber,
                @Advice.FieldValue(value = "stage", readOnly = false) int stage,
                @Advice.FieldValue(value = "alpha", readOnly = false) float alpha,
                @Advice.FieldValue(value = "targetAlpha", readOnly = false) float targetAlpha,
                @Advice.FieldValue(value = "noRender", readOnly = false) boolean noRender,
                @Advice.Return(readOnly = false) GameStateMachine.StateAction returnValue) {

            if (screenNumber == 2 && stage == 3) {
                // Switch to Custom Screen
                screenNumber = 3;
                stage = 0;
                alpha = 0.0F;
                targetAlpha = 1.0F;

                // Ensure the engine keeps running this state
                noRender = false;
                returnValue = GameStateMachine.StateAction.Remain;
            }
        }
    }

    public static class RenderAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(
                @Advice.FieldValue("screenNumber") int screenNumber,
                @Advice.FieldValue("alpha") float alpha) {

            if (screenNumber == 3) {
                Core.getInstance().StartFrame();
                Core.getInstance().EndFrame();

                boolean useUIFBO = UIManager.useUiFbo;
                UIManager.useUiFbo = false;

                Core.getInstance().StartFrameUI();

                SpriteRenderer.instance.renderi(
                        null,
                        0,
                        0,
                        Core.getInstance().getOffscreenWidth(0),
                        Core.getInstance().getOffscreenHeight(0),
                        0.0F,
                        0.0F,
                        0.0F,
                        1.0F,
                        null);

                CustomLogoHandler.render(alpha);

                Core.getInstance().EndFrameUI();

                UIManager.useUiFbo = useUIFBO;

                return true; // Return TRUE to SKIP original render() method
            }
            return false; // Return FALSE to run original render() method
        }
    }

    public static class CustomLogoHandler {
        private static Texture myLogo;

        public static void render(float alpha) {
            try {
                if (myLogo == null) {
                    myLogo =
                            StormUtils.getTextureResourceFromStream(
                                    "storm-logo.png", CustomLogoHandler.class.getClassLoader());
                }

                if (myLogo != null && myLogo.isReady()) {
                    int screenWidth = Core.getInstance().getScreenWidth();
                    int screenHeight = Core.getInstance().getScreenHeight();
                    int texWidth = myLogo.getWidth();
                    int texHeight = myLogo.getHeight();

                    int x = (screenWidth - texWidth) / 2;
                    int y = (screenHeight - texHeight) / 2;

                    IndieGL.glEnable(3042);
                    IndieGL.glBlendFunc(770, 771);

                    SpriteRenderer.instance.render(
                            myLogo, x, y, texWidth, texHeight, 1.0F, 1.0F, 1.0F, alpha, null);
                }
            } catch (Exception e) {
                LOGGER.error("Error rendering custom logo: ", e);
            }
        }
    }
}
