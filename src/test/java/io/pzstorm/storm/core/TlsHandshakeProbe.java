package io.pzstorm.storm.core;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Performs a full loopback TLS handshake and encrypted echo round-trip. Loaded through {@link
 * StormClassLoader} by the TLS regression test, so every {@code javax.net.ssl} reference in this
 * class resolves via Storm's loader — the same situation the game's Discord bot (OkHttp) is in.
 * Before the JDK-delegation fix this class could not even initialize its SSL context: {@code
 * SSLContext.getInstance} threw {@code IllegalAccessError} because {@code javax.net.ssl} had been
 * duplicated into the loader's unnamed module.
 */
public final class TlsHandshakeProbe {

    public static final String MESSAGE = "storm-tls-ok";

    private static final char[] PASSWORD = "storm-test".toCharArray();

    private TlsHandshakeProbe() {}

    /**
     * Starts a TLS server on an ephemeral loopback port, connects a TLS client trusting the
     * server's self-signed certificate, and echoes {@link #MESSAGE} over the encrypted channel.
     *
     * @return the negotiated protocol and echoed message, e.g. {@code "TLSv1.3:storm-tls-ok"}.
     */
    public static String handshakeAndEcho() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in =
                TlsHandshakeProbe.class.getResourceAsStream("/tls/storm-test-keystore.p12")) {
            keyStore.load(in, PASSWORD);
        }
        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, PASSWORD);
        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keyStore);

        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trustManagers.getTrustManagers(), null);

        byte[] payload = MESSAGE.getBytes(StandardCharsets.UTF_8);
        try (SSLServerSocket serverSocket =
                (SSLServerSocket)
                        serverContext
                                .getServerSocketFactory()
                                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {

            CompletableFuture<Void> serverDone = new CompletableFuture<>();
            Thread server =
                    new Thread(
                            () -> {
                                try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                                    byte[] request =
                                            socket.getInputStream().readNBytes(payload.length);
                                    socket.getOutputStream().write(request);
                                    socket.getOutputStream().flush();
                                    serverDone.complete(null);
                                } catch (Throwable t) {
                                    serverDone.completeExceptionally(t);
                                }
                            },
                            "tls-probe-server");
            server.start();

            try (SSLSocket client =
                    (SSLSocket)
                            clientContext
                                    .getSocketFactory()
                                    .createSocket(
                                            InetAddress.getLoopbackAddress(),
                                            serverSocket.getLocalPort())) {
                client.startHandshake();
                client.getOutputStream().write(payload);
                client.getOutputStream().flush();
                byte[] echo = client.getInputStream().readNBytes(payload.length);

                serverDone.get(10, TimeUnit.SECONDS);
                return client.getSession().getProtocol()
                        + ":"
                        + new String(echo, StandardCharsets.UTF_8);
            } finally {
                server.join(10_000);
            }
        }
    }
}
