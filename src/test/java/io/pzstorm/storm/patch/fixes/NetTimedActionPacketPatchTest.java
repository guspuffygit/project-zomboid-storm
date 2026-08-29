package io.pzstorm.storm.patch.fixes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Real-weave test: applies the patch to the vanilla {@code NetTimedActionPacket} bytes and checks
 * that the advice call sites landed and delegate to {@link NetTimedActionPacketFix}.
 *
 * <p>The delegation target matters: the fix logic must stay out of {@code
 * NetTimedActionPacketPatch} itself, because the patch class is linked during transformer
 * registration and any game type it references gets loaded — and defined untransformed — before
 * registration completes. Someone folding {@code processServerFixed} back into the patch class
 * would reintroduce that bug; {@link
 * com.sentientsimulations.storm.core.TransformerRegistrationClassLoadTest} catches the load, this
 * test pins the woven call site.
 */
class NetTimedActionPacketPatchTest {

    @Test
    void adviceIsWovenAndDelegatesToFixClass() throws Exception {
        byte[] rawClass;
        try (InputStream is =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("zombie/network/packets/NetTimedActionPacket.class")) {
            assertNotNull(is, "NetTimedActionPacket should be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new NetTimedActionPacketPatch().transform(rawClass);
        String woven = new String(transformed, StandardCharsets.ISO_8859_1);
        String vanilla = new String(rawClass, StandardCharsets.ISO_8859_1);

        assertTrue(
                woven.contains("io/pzstorm/storm/patch/fixes/NetTimedActionPacketFix"),
                "woven processServer should delegate to NetTimedActionPacketFix");
        assertTrue(
                woven.contains("processServerFixed"),
                "woven bytecode should call processServerFixed");
        assertFalse(
                vanilla.contains("processServerFixed"),
                "sanity: vanilla bytecode should not already contain the advice call site");
    }
}
