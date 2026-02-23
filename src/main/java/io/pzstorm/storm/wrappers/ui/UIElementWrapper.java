package io.pzstorm.storm.wrappers.ui;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Method;
import zombie.core.textures.Texture;

/** Wrapper for object of {@link zombie.ui.UIElement} */
public class UIElementWrapper extends ObjectWrapper {

    private final Method drawTextureScaledColorMethod;
    private final Method drawTextureScaledColMethod;
    private final Method drawTextCentreMethod;

    public UIElementWrapper(Object object) {
        super(object);

        try {
            drawTextureScaledColorMethod =
                    clazz.getMethod(
                            "DrawTextureScaledColor",
                            Texture.class,
                            Double.class,
                            Double.class,
                            Double.class,
                            Double.class,
                            Double.class,
                            Double.class,
                            Double.class,
                            Double.class);
            drawTextureScaledColMethod =
                    clazz.getMethod(
                            "DrawTextureScaledCol",
                            Texture.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class);
            drawTextCentreMethod =
                    clazz.getMethod(
                            "DrawTextCentre",
                            String.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class,
                            double.class);
        } catch (NoSuchMethodException e) {
            LOGGER.error("Unable to get declared method", e);
            throw new RuntimeException(e);
        }
    }

    public void DrawTextureScaledColor(
            Texture var1,
            Double var2,
            Double var3,
            Double var4,
            Double var5,
            Double var6,
            Double var7,
            Double var8,
            Double var9) {
        try {
            drawTextureScaledColorMethod.invoke(
                    object, var1, var2, var3, var4, var5, var6, var7, var8, var9);
        } catch (Exception e) {
            LOGGER.error("Error invoking UIElement.DrawTextureScaledColor", e);
        }
    }

    public void DrawTextureScaledCol(
            Texture var1,
            double var2,
            double var4,
            double var6,
            double var8,
            double var10,
            double var12,
            double var14,
            double var16) {
        try {
            drawTextureScaledColMethod.invoke(
                    object, var1, var2, var4, var6, var8, var10, var12, var14, var16);
        } catch (Exception e) {
            LOGGER.error("Error invoking UIElement.DrawTextureScaledCol", e);
        }
    }

    public void DrawTextCentre(
            String var1,
            double var2,
            double var4,
            double var6,
            double var8,
            double var10,
            double var12) {
        try {
            drawTextCentreMethod.invoke(object, var1, var2, var4, var6, var8, var10, var12);
        } catch (Exception e) {
            LOGGER.error("Error invoking UIElement.DrawTextCentre", e);
        }
    }
}
