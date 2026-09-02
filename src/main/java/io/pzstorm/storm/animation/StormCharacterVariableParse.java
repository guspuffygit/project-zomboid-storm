package io.pzstorm.storm.animation;

/**
 * Verbatim port of the non-lookup branch of {@code CharacterVariableCondition.parseValue(String,
 * false)} — the parser vanilla applies to every animation-variable value string at condition
 * evaluation time. Reproduced (including its quirks: a leading {@code '-'} is dropped on the
 * integer path but honoured on the float path, commas are ignored, {@code "1."} is the integer 1,
 * fractions accumulate in float arithmetic rather than {@code Float.parseFloat}) so {@code
 * CharacterVariableResolveAdvice} can skip the vanilla private method while returning exactly the
 * same boxed value. {@code StormCharacterVariableParseTest} diffs this against the vanilla method
 * reflectively over a corpus.
 */
public final class StormCharacterVariableParse {

    private StormCharacterVariableParse() {}

    public static Object parseValue(String value) {
        if (value.length() <= 0) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '-' || first == '+' || (first >= '0' && first <= '9')) {
            int intVal = 0;
            if (first >= '0' && first <= '9') {
                intVal = first - '0';
            }
            int readPos;
            for (readPos = 1; readPos < value.length(); readPos++) {
                char chr = value.charAt(readPos);
                if (chr >= '0' && chr <= '9') {
                    intVal = intVal * 10 + chr - 48;
                } else if (chr != ',') {
                    if (chr != '.') {
                        return value;
                    }
                    readPos++;
                    break;
                }
            }
            if (readPos == value.length()) {
                return intVal;
            }
            float floatVal = intVal;
            for (float divisor = 10.0F; readPos < value.length(); readPos++) {
                char chr = value.charAt(readPos);
                if (chr >= '0' && chr <= '9') {
                    floatVal += (chr - '0') / divisor;
                    divisor *= 10.0F;
                } else if (chr != ',') {
                    return value;
                }
            }
            if (first == '-') {
                floatVal *= -1.0F;
            }
            return floatVal;
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes")) {
            return true;
        }
        if (!value.equalsIgnoreCase("false") && !value.equalsIgnoreCase("no")) {
            return value;
        }
        return false;
    }
}
