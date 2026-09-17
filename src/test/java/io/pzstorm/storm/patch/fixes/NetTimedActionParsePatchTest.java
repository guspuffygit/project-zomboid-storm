package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Real-weave test: applies the patch to the vanilla {@code NetTimedAction} bytes and checks that
 * the exit advice landed in {@code parse} and delegates to {@link NetTimedActionParseFix}.
 */
class NetTimedActionParsePatchTest {

    @Test
    void adviceIsWovenAndDelegatesToFixClass() throws Exception {
        byte[] rawClass;
        try (InputStream is =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("zombie/core/NetTimedAction.class")) {
            assertNotNull(is, "NetTimedAction should be on the test classpath");
            rawClass = is.readAllBytes();
        }
        byte[] transformed = new NetTimedActionParsePatch().transform(rawClass);
        String woven = new String(transformed, StandardCharsets.ISO_8859_1);
        String vanilla = new String(rawClass, StandardCharsets.ISO_8859_1);
        assertTrue(
                woven.contains("io/pzstorm/storm/patch/fixes/NetTimedActionParseFix"),
                "woven parse should delegate to NetTimedActionParseFix");
        assertTrue(woven.contains("onParseFailed"), "woven bytecode should call onParseFailed");
        assertFalse(
                vanilla.contains("onParseFailed"),
                "sanity: vanilla bytecode should not already contain the advice call site");
    }
}
