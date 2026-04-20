package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes a vanilla bug where {@code Translator.getText()} is called with an already-translated
 * string (e.g. a craft recipe display name), causing a spurious "Missing translation" error.
 *
 * <p>The root cause is in {@code ISInventoryPaneContextMenu.lua} line 3351:
 *
 * <pre>local recipeName = getText(recipe:getTranslationName())</pre>
 *
 * <p>{@code getTranslationName()} already returns the translated name (e.g. "Craft Vehicle
 * Ownership Title"), but it gets passed to {@code getText()} again as if it were a translation key.
 * Since it doesn't match any known prefix, {@code getTextInternal} logs a "Missing translation"
 * error.
 *
 * <p>This patch intercepts {@code getText(String)} and short-circuits the call when the input
 * string doesn't match any known translation key prefix, returning the string as-is without
 * triggering the error log.
 */
public class TranslatorPatch extends StormClassTransformer {

    public TranslatorPatch() {
        super("zombie.core.Translator");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(GetTextAdvice.class)
                        .on(
                                ElementMatchers.named("getText")
                                        .and(ElementMatchers.takesArguments(String.class))
                                        .and(ElementMatchers.isStatic())));
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static class GetTextAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static String onEnter(@Advice.Argument(0) String desc) {
            if (desc == null || desc.isEmpty()) {
                return desc;
            }
            // If the string matches a known translation key prefix, let the original method handle
            // it. Otherwise, it's not a valid key (likely an already-translated string), so return
            // it directly to avoid the "Missing translation" error log.
            if (desc.startsWith("UI_")
                    || desc.startsWith("Moodles_")
                    || desc.startsWith("SurvivalGuide_")
                    || desc.startsWith("Farming_")
                    || desc.startsWith("IGUI_")
                    || desc.startsWith("ContextMenu_")
                    || desc.startsWith("credits_")
                    || desc.startsWith("GameSound_")
                    || desc.startsWith("Sandbox_")
                    || desc.startsWith("Tooltip_")
                    || desc.startsWith("Challenge_")
                    || desc.startsWith("MakeUp")
                    || desc.startsWith("Stash_")
                    || desc.startsWith("RM_")
                    || desc.startsWith("SurvivorName_")
                    || desc.startsWith("SurvivorSurname_")
                    || desc.startsWith("Attributes_")
                    || desc.startsWith("Fluid_")
                    || desc.startsWith("Print_Media_")
                    || desc.startsWith("Print_Text_")
                    || desc.startsWith("EC_")
                    || desc.startsWith("RD_")
                    || desc.startsWith("BODYPART_")
                    || desc.startsWith("MapLabel_")) {
                // Known prefix — let the original getText handle it
                return null;
            }
            // No known prefix — not a valid translation key, return as-is
            return desc;
        }

        @Advice.OnMethodExit
        public static void onExit(
                @Advice.Enter String earlyReturn, @Advice.Return(readOnly = false) String result) {
            if (earlyReturn != null) {
                result = earlyReturn;
            }
        }
    }
}
