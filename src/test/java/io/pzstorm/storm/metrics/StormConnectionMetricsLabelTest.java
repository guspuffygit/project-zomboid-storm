package io.pzstorm.storm.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import zombie.core.raknet.UdpConnection;

/**
 * Pins {@code StormConnectionMetrics.labelFor}: a connection without a username contributes no
 * per-peer label, so nameless connection attempts cannot grow the metric registry with uptime.
 *
 * <p>{@code UdpConnection} is allocated without running its constructor, which builds a {@code
 * PacketsCache} that dies on a null randomizer in a bare test JVM. Only the two plain fields read
 * by {@code labelFor} are set.
 */
class StormConnectionMetricsLabelTest implements UnitTest {

    @Test
    void aNamedPeerIsLabelledByItsUsername() throws Exception {
        assertEquals("Grim", labelFor(connection(1234L, "Grim")));
    }

    @Test
    void aPeerWithNoUsernameYetHasNoLabel() throws Exception {
        assertNull(labelFor(connection(1234L, null)), "a null username must not become a label");
    }

    @Test
    void aPeerWithAnEmptyUsernameHasNoLabel() throws Exception {
        assertNull(labelFor(connection(1234L, "")), "an empty username must not become a label");
    }

    /** The regression this change exists to prevent: the guid must never reach a metric label. */
    @Test
    void theGuidNeverAppearsInALabel() throws Exception {
        String label = labelFor(connection(9876543210L, null));
        assertNull(label);
        // and the named case must not smuggle it in either
        String named = labelFor(connection(9876543210L, "Grim"));
        assertNotNull(named);
        assertTrue(
                named.indexOf("guid:") < 0 && named.indexOf("9876543210") < 0,
                "label carried the guid: " + named);
    }

    /**
     * The cardinality property, stated directly. Three different nameless connections, three
     * different guids, and the set of labels they contribute is empty.
     */
    @Test
    void namelessPeersCannotMintDistinctLabels() throws Exception {
        Set<String> labels = new HashSet<>();
        long[] guids = {1L, 2L, 3L};
        for (long g : guids) {
            String label = labelFor(connection(g, null));
            if (label != null) {
                labels.add(label);
            }
        }
        assertEquals(0, labels.size(), "nameless peers contributed labels: " + labels);
    }

    @Test
    void namedPeersStillGetOneLabelEach() throws Exception {
        Set<String> labels = new HashSet<>();
        labels.add(labelFor(connection(1L, "alice")));
        labels.add(labelFor(connection(2L, "bob")));
        labels.add(labelFor(connection(3L, "bob")));
        assertEquals(2, labels.size(), "expected one label per distinct username, got " + labels);
    }

    // ------------------------------------------------------------------ helpers

    private static String labelFor(UdpConnection c) throws Exception {
        Method m = StormConnectionMetrics.class.getDeclaredMethod("labelFor", UdpConnection.class);
        m.setAccessible(true);
        return (String) m.invoke(null, c);
    }

    private static UdpConnection connection(long guid, String userName) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        UdpConnection c = (UdpConnection) allocate.invoke(theUnsafe.get(null), UdpConnection.class);

        Field guidField = UdpConnection.class.getDeclaredField("connectedGuid");
        guidField.setAccessible(true);
        guidField.setLong(c, guid);

        Field nameField = UdpConnection.class.getDeclaredField("userName");
        nameField.setAccessible(true);
        nameField.set(c, userName);
        return c;
    }
}
