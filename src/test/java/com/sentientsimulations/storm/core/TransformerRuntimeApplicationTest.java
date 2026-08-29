package com.sentientsimulations.storm.core;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormBootstrap;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies through the <em>real</em> bootstrap pipeline that {@code NetTimedActionPacket} is
 * actually transformed when {@code StormClassLoader} defines it — i.e. that the fix for the
 * registration-time load bug holds at runtime, not just offline.
 *
 * <p>Before the fix, linking {@code NetTimedActionPacketPatch} inside the registration block
 * verifier-loaded {@code zombie.network.packets.NetTimedActionPacket} through {@code
 * StormClassLoader} while {@code StormBootstrap.hasLoaded()} was still false, so the class was
 * defined raw and both of its transformers ({@code NetTimedActionPacketPatch} and {@code
 * PacketReceivedPatch}) silently never applied. In this test that same history would surface as a
 * non-empty {@code getLoadedUntransformedTargets()} right after bootstrap.
 */
class TransformerRuntimeApplicationTest implements IntegrationTest {

    private static final String TARGET = "zombie.network.packets.NetTimedActionPacket";

    @Test
    @SuppressWarnings("unchecked")
    void netTimedActionPacketIsTransformedWhenLoadedThroughStormClassLoader() throws Exception {
        Class<?> transformersClass =
                Class.forName(
                        "io.pzstorm.storm.core.StormClassTransformers",
                        true,
                        StormBootstrap.CLASS_LOADER);

        // 1. Bootstrap must not have loaded any registered target before its transformers.
        Method getLoadedUntransformed =
                transformersClass.getDeclaredMethod("getLoadedUntransformedTargets");
        assertEquals(
                List.of(),
                getLoadedUntransformed.invoke(null),
                "registered transformer targets were loaded during registration and can never be"
                        + " transformed — their patches are silently dead");

        // 2. Both transformers must be registered, bug fix wrapped inside packet-event dispatch.
        Method getRegistered = transformersClass.getDeclaredMethod("getRegistered", String.class);
        List<Object> registered = (List<Object>) getRegistered.invoke(null, TARGET);
        List<String> names = registered.stream().map(t -> t.getClass().getSimpleName()).toList();
        assertEquals(
                List.of("NetTimedActionPacketPatch", "PacketReceivedPatch"),
                names,
                "NetTimedActionPacketPatch must be registered before PacketReceivedPatch so the"
                        + " event dispatch wraps the fixed logic");

        // 3. Loading the class through the real pipeline must actually weave it.
        Class<?> packetClass = Class.forName(TARGET, false, StormBootstrap.CLASS_LOADER);
        assertSame(
                StormBootstrap.CLASS_LOADER,
                packetClass.getClassLoader(),
                "sanity: NetTimedActionPacket should be defined by StormClassLoader");

        Method getTransformed = transformersClass.getDeclaredMethod("getTransformedClasses");
        Set<String> transformed = (Set<String>) getTransformed.invoke(null);
        assertTrue(
                transformed.contains(TARGET),
                "NetTimedActionPacket was defined without its transformers being applied");

        // 4. And the load left no registered target in the loaded-but-untransformed state.
        assertEquals(List.of(), getLoadedUntransformed.invoke(null));
    }
}
