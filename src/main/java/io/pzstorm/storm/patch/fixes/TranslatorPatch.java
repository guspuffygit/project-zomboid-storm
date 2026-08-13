package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes a vanilla bug where call sites like {@code LoadingQueueUI.render()} line 327 wrap a no-arg
 * {@code getText} in an outer {@code String.format} ({@code
 * String.format(Translator.getText("UI_GameLoad_windSpeed"), windName, knots, kph)}). Vanilla's
 * {@code reportMissingArgumentsFromPastAbuse} then formats a text containing {@code %1$s} with zero
 * arguments, which is guaranteed to throw {@code MissingFormatArgumentException}, log a warning,
 * and return the raw text — every rendered frame. On the loading-queue screen that floods the log
 * at hundreds of warnings per second, drowning real diagnostics out of the 1&nbsp;MB launcher
 * Send-Logs tail. {@code ZeroArgFormatAdvice} detects the guaranteed-throw shape (zero args, text
 * contains {@code %1$s}) and returns the raw text directly: outcome-identical to vanilla's catch
 * path minus the per-frame exception and log line. All other calls, including zero-arg texts
 * without positional specifiers (which need {@code %%} collapsing), run the original method
 * unchanged.
 *
 * <p>Two earlier advices were retired as vanilla absorbed or defused their bugs:
 *
 * <ul>
 *   <li>Until 42.20.1: rescued {@code reportMissingArgumentsFromPastAbuse} from malformed format
 *       strings (an unescaped {@code %} threw {@code UnknownFormatConversionException} into the
 *       calling Lua chunk). 42.20.2 catches {@code IllegalFormatException} and returns the
 *       unformatted text itself.
 *   <li>Until 42.20.2: {@code GetTextAdvice} short-circuited {@code getText} for strings matching
 *       no known translation-key prefix, suppressing the "Missing translation" error spam caused by
 *       Lua re-translating already-translated strings (e.g. {@code
 *       ISInventoryPaneContextMenu.lua}'s {@code getText(recipe:getTranslationName())}). The Lua
 *       bug is still there, but 42.20.2 dedups the error once per unique string and gates it behind
 *       {@code -debug}/{@code -debugtranslation}, so production JVMs no longer log it at all.
 * </ul>
 */
public class TranslatorPatch extends StormClassTransformer {

    public TranslatorPatch() {
        super("zombie.core.Translator");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(ZeroArgFormatAdvice.class)
                        .on(
                                ElementMatchers.named("reportMissingArgumentsFromPastAbuse")
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        String.class, String.class, Object[].class))
                                        .and(ElementMatchers.isStatic())));
    }

    public static class ZeroArgFormatAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static String onEnter(
                @Advice.Argument(1) String text, @Advice.Argument(2) Object[] args) {
            // With zero args, a text holding a positional specifier makes text.formatted() throw
            // MissingFormatArgumentException, warn-log, and return the raw text — on every call.
            // Short-circuit to the identical outcome without the exception and log spam. Zero-arg
            // texts without "%1$s" still need the original path for %% collapsing.
            if (args != null && args.length == 0 && text != null && text.contains("%1$s")) {
                return text;
            }
            return null;
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
