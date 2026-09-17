package io.pzstorm.storm.advice.debuglogformat;

import java.util.IllegalFormatException;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.debug.DebugLogStream.getFormattedOutputStr(Object, Object[])}, the one
 * place every {@code DebugType.error/warn/debugln(format, params...)} call runs {@code
 * String.format}. A vanilla call site with a malformed format string ({@code
 * GameEntityManager.checkEntityIDChange} logs with {@code %ld}, which the Java formatter rejects)
 * otherwise throws {@code UnknownFormatConversionException} out of the log call and into whatever
 * game code was trying to log — on the server that is {@code ServerCell.Unload}, which then leaves
 * the cell half torn down.
 *
 * <p>When the formatter throws, the exception is swallowed and the line is emitted as the raw
 * format string followed by its arguments, so the message still lands in the log and the caller
 * keeps running.
 */
public class DebugLogStreamFormatAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) Object format,
            @Advice.Argument(1) Object[] params,
            @Advice.Return(readOnly = false) String formatted,
            @Advice.Thrown(readOnly = false) Throwable thrown) {
        if (!(thrown instanceof IllegalFormatException)) {
            return;
        }
        formatted = fallback(format, params, thrown);
        thrown = null;
    }

    public static String fallback(Object format, Object[] params, Throwable cause) {
        StringBuilder sb = new StringBuilder(String.valueOf(format));
        sb.append(" [");
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(params[i]);
            }
        }
        sb.append("] (bad format string: ").append(cause).append(')');
        return sb.toString();
    }
}
