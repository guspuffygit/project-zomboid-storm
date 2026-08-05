package io.pzstorm.storm.core;

import com.google.common.collect.ImmutableSet;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression test for JDK runtime class duplication (GitHub issue: Discord bot TLS failure).
 *
 * <p>{@code javax.net.ssl.*} is not covered by the prefix blacklist, so before the fix {@link
 * StormClassLoader} would read those class bytes from the {@code jrt:} image and define duplicate
 * copies in its own unnamed module. The JDK's TLS provider implementations extend the real {@code
 * java.base} types, so SPI lookups failed ({@code IllegalAccessError} on {@code
 * sun.security.jca.GetInstance}, or {@code NoSuchAlgorithmException: ... not a TrustManagerFactory}
 * with {@code --add-exports} workarounds).
 */
class StormClassLoaderJdkDelegationTest {

    private static final ImmutableSet<String> JDK_RUNTIME_CLASSES =
            ImmutableSet.of(
                    "javax.net.ssl.TrustManagerFactory",
                    "javax.net.ssl.SSLContext",
                    "javax.crypto.Cipher",
                    "javax.security.auth.Subject",
                    "jdk.net.ExtendedSocketOptions");

    @Test
    void shouldRecognizeJdkRuntimeClasses() {
        for (String name : JDK_RUNTIME_CLASSES) {
            Assertions.assertTrue(StormClassLoader.isJdkRuntimeClass(name), name);
        }
        // game-shipped and game classes must keep loading through StormClassLoader
        Assertions.assertFalse(StormClassLoader.isJdkRuntimeClass("javax.vecmath.Vector3f"));
        Assertions.assertFalse(StormClassLoader.isJdkRuntimeClass("zombie.network.GameServer"));
        Assertions.assertFalse(
                StormClassLoader.isJdkRuntimeClass("io.pzstorm.storm.core.StormLauncher"));
    }

    @Test
    void shouldDelegateJdkRuntimeClassesToParentPreservingClassIdentity() throws Exception {
        StormClassLoader classLoader = new StormClassLoader();
        for (String name : JDK_RUNTIME_CLASSES) {
            Class<?> loaded = classLoader.loadClass(name);
            Assertions.assertSame(Class.forName(name), loaded, name);
            Assertions.assertTrue(loaded.getModule().isNamed(), name);
        }
    }

    @Test
    void shouldCompleteTlsHandshakeThroughStormClassLoader() throws Exception {
        // the probe class itself must live inside StormClassLoader (like OkHttp under Storm),
        // so its javax.net.ssl references resolve through the loader under test
        StormClassLoader classLoader = new StormClassLoader();
        Class<?> probe = classLoader.loadClass("io.pzstorm.storm.core.TlsHandshakeProbe");
        Assertions.assertSame(classLoader, probe.getClassLoader());

        String result = (String) probe.getMethod("handshakeAndEcho").invoke(null);
        Assertions.assertTrue(result.startsWith("TLS"), result);
        Assertions.assertTrue(result.endsWith(":" + TlsHandshakeProbe.MESSAGE), result);
    }

    @Test
    void shouldResolveTrustManagerFactorySpiThroughStormClassLoader() throws Exception {
        // replicates DiscordBot.connect's failure path: an SPI lookup made from a class
        // living inside StormClassLoader must accept the JDK's own provider implementation
        Class<?> tmf = new StormClassLoader().loadClass("javax.net.ssl.TrustManagerFactory");
        Object instance =
                tmf.getMethod("getInstance", String.class)
                        .invoke(null, TrustManagerFactory.getDefaultAlgorithm());
        Assertions.assertNotNull(instance);
    }
}
