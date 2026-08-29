package com.sentientsimulations.storm.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Guards the transformer-registration invariant: building the {@code TRANSFORMERS} map must not
 * load any game class.
 *
 * <p>Registration runs inside {@code StormClassTransformers.<clinit>}, before {@code
 * StormBootstrap.hasLoaded()} is true, so any game class loaded during registration is defined
 * <em>untransformed</em> — and a class gets exactly one shot at transformation, at define time. The
 * failure is completely silent: {@code applyAll} sees an empty transformer list and the patch never
 * applies for the lifetime of the JVM.
 *
 * <p>This actually happened: {@code NetTimedActionPacketPatch} used to hold {@code
 * processServerFixed(NetTimedActionPacket, UdpConnection)}, and linking the patch class made the
 * bytecode verifier load {@code zombie.network.packets.NetTimedActionPacket} (to prove {@code
 * NetTimedActionPacket <: NetTimedAction} for {@code act.copyFrom(packet)}) — killing both that
 * patch and the packet's {@code PacketReceivedPatch}, which silenced every {@code
 * NetTimedActionPacketEvent} consumer. Game-type logic must live in a class that is only loaded
 * when the woven advice runs (see {@code NetTimedActionPacketFix}).
 *
 * <p>The test initializes a fresh copy of {@code StormClassTransformers} inside a recording
 * classloader (child-first for Storm, game and prometheus classes, so verifier-triggered loads from
 * patch classes are observable) with every registration branch enabled, then asserts that no game
 * class was requested while the registration block ran.
 */
class TransformerRegistrationClassLoadTest {

    private static final String TRANSFORMERS_CLASS = "io.pzstorm.storm.core.StormClassTransformers";

    /**
     * Child-first classloader for Storm and game classes that records every requested {@code
     * zombie.} class name. Defining Storm's patch classes here (instead of delegating to the app
     * loader) makes their verifier-triggered game-class loads flow through {@link #loadClass} where
     * they can be recorded — mirroring how {@code StormClassLoader} is the defining loader in
     * production.
     *
     * <p>{@code io.prometheus.} is also child-first: patch static initializers register metrics
     * into {@code PrometheusRegistry.defaultRegistry}, and production {@code StormClassLoader}
     * defines its own prometheus classes so each copy of the registration block has its own
     * registry. Delegating prometheus to the app loader instead would collide ("metric already
     * registered") whenever another test in the same JVM had initialized the app-loader copy of
     * {@code StormClassTransformers}.
     */
    private static final class RecordingClassLoader extends ClassLoader {

        private static final List<String> CHILD_FIRST_PREFIXES =
                List.of("io.pzstorm.", "zombie.", "io.prometheus.");

        private final Set<String> requestedGameClasses = ConcurrentHashMap.newKeySet();

        RecordingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith("zombie.")) {
                    requestedGameClasses.add(name);
                }
                Class<?> clazz = findLoadedClass(name);
                if (clazz == null && CHILD_FIRST_PREFIXES.stream().anyMatch(name::startsWith)) {
                    byte[] bytes = readClassBytes(name);
                    if (bytes != null) {
                        clazz = defineClass(name, bytes, 0, bytes.length);
                    }
                }
                if (clazz == null) {
                    clazz = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            }
        }

        private byte[] readClassBytes(String name) {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream is = getParent().getResourceAsStream(resource)) {
                return is == null ? null : is.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void registeringAllTransformersMustNotLoadAnyGameClass() throws Exception {
        String serverBefore = System.getProperty("storm.server");
        String clientPerfBefore = System.getProperty("storm.experimental.clientperf");
        // enable every registration branch so all patch classes get linked
        System.setProperty("storm.server", "true");
        System.setProperty("storm.experimental.clientperf", "true");

        RecordingClassLoader loader = new RecordingClassLoader(getClass().getClassLoader());
        try {
            Class<?> transformersClass = Class.forName(TRANSFORMERS_CLASS, true, loader);
            assertSame(
                    loader,
                    transformersClass.getClassLoader(),
                    "sanity: StormClassTransformers should be defined by the recording loader");

            Field transformersField = transformersClass.getDeclaredField("TRANSFORMERS");
            transformersField.setAccessible(true);
            Map<String, List<?>> transformers = (Map<String, List<?>>) transformersField.get(null);

            assertEquals(
                    2,
                    transformers
                            .getOrDefault("zombie.network.packets.NetTimedActionPacket", List.of())
                            .size(),
                    "NetTimedActionPacket should have NetTimedActionPacketPatch +"
                            + " PacketReceivedPatch registered");

            Set<String> loadedTargets = new TreeSet<>(loader.requestedGameClasses);
            loadedTargets.retainAll(transformers.keySet());
            assertEquals(
                    Set.of(),
                    loadedTargets,
                    "These transformer targets were loaded while the registration block ran, so"
                            + " in production they are defined before their transformers register and"
                            + " their patches silently never apply. A patch class linked during"
                            + " registration must not reference its target class — move game-type"
                            + " logic to a separate class that only loads when the advice runs (see"
                            + " NetTimedActionPacketFix)");

            assertEquals(
                    Set.of(),
                    new TreeSet<>(loader.requestedGameClasses),
                    "No game class at all should be loaded during transformer registration:"
                            + " anything loaded here is untransformable for the JVM's lifetime,"
                            + " including by mod-provided transformers collected later");
        } finally {
            restoreProperty("storm.server", serverBefore);
            restoreProperty("storm.experimental.clientperf", clientPerfBefore);
        }
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
