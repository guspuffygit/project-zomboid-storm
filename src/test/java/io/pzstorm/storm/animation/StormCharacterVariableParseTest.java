package io.pzstorm.storm.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The port must return the same boxed value <em>and</em> the same box class as vanilla {@code
 * CharacterVariableCondition.parseValue(value, false)} — {@code passes} branches on the class.
 * Invokes the vanilla private static reflectively; the class has no static initialiser touching
 * game state.
 */
class StormCharacterVariableParseTest implements UnitTest {

    private static final String[] CORPUS = {
        "",
        "0",
        "1",
        "42",
        "-5",
        "+5",
        "-0",
        "12,345",
        "3.14",
        "-3.14",
        "+2.5",
        "1.",
        ".5",
        "1e5",
        "1.2.3",
        "true",
        "TRUE",
        "True",
        "yes",
        "YES",
        "false",
        "FALSE",
        "no",
        "No",
        "abc",
        "'q'",
        "\"quoted\"",
        "1,",
        ",",
        "-",
        "+",
        "12a",
        "1.5a",
        "1,5",
        "1.,5",
        "999999999999",
        "-0.0",
        "0.000001",
        "2147483648",
        "  7",
        "7 ",
        "Idle",
        "bob_walk",
        "-abc",
        "+",
        "1.0E10",
        "NaN",
        "Infinity",
        "12.34.",
        "1..2"
    };

    @Test
    void portMatchesVanillaOverCorpus() throws Exception {
        Class<?> vanilla =
                Class.forName("zombie.characters.action.conditions.CharacterVariableCondition");
        Method parse = vanilla.getDeclaredMethod("parseValue", String.class, boolean.class);
        parse.setAccessible(true);
        for (String input : CORPUS) {
            Object expected = parse.invoke(null, input, false);
            Object actual = StormCharacterVariableParse.parseValue(input);
            assertNotNull(expected, input);
            assertEquals(expected.getClass(), actual.getClass(), "box class for '" + input + "'");
            assertEquals(expected, actual, "value for '" + input + "'");
        }
    }
}
