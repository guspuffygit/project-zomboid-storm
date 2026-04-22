package io.pzstorm.storm.liveserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Subprocess entry point that hosts one {@link LiveServerClient} in its own JVM so tests can drive
 * multiple RakNet connections to the same server — a single peer cannot hold two outbound
 * connections to the same {@code (ip, port)}, so each logical client needs its own JVM/peer.
 *
 * <p>Protocol over stdin/stdout (one line per command/event):
 *
 * <pre>
 *   CLI: connect &lt;host&gt; &lt;port&gt; &lt;serverPassword&gt; &lt;username&gt; &lt;password&gt; &lt;timeoutSeconds&gt;
 *   RSP: CONNECTED &lt;guid&gt;      — on success
 *   RSP: ERROR &lt;message&gt;       — on failure
 *
 *   CLI: send-action &lt;actionByteId&gt; &lt;durationMillis&gt;
 *   RSP: SENT
 *   RSP: ERROR &lt;message&gt;
 *
 *   CLI: quit
 *   RSP: BYE                    — before exiting
 * </pre>
 *
 * The parent test matches lines starting with {@code CONNECTED}, {@code SENT}, {@code ERROR},
 * {@code BYE}. Any other stdout/stderr (including PZ native output) is logged for diagnostics.
 */
public final class LiveServerClientMain {

    private LiveServerClientMain() {}

    public static void main(String[] args) {
        LiveServerClient client = null;
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            LiveServerClient.initClientNativesOnce();
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                String cmd = parts[0];
                try {
                    switch (cmd) {
                        case "connect":
                            {
                                String host = parts[1];
                                int port = Integer.parseInt(parts[2]);
                                String serverPassword =
                                        "__EMPTY__".equals(parts[3]) ? "" : parts[3];
                                String username = parts[4];
                                String password = parts[5];
                                long timeoutSeconds = Long.parseLong(parts[6]);
                                client = new LiveServerClient(username, password);
                                client.connect(
                                        host,
                                        port,
                                        serverPassword,
                                        Duration.ofSeconds(timeoutSeconds));
                                long guid = client.getConnection().getConnectedGUID();
                                System.out.println("CONNECTED " + guid);
                                System.out.flush();
                                break;
                            }
                        case "send-action":
                            {
                                if (client == null) {
                                    System.out.println("ERROR not-connected");
                                    System.out.flush();
                                    break;
                                }
                                byte id = (byte) Integer.parseInt(parts[1]);
                                long duration = Long.parseLong(parts[2]);
                                client.sendRawNetTimedActionBytes(id, duration);
                                System.out.println("SENT");
                                System.out.flush();
                                break;
                            }
                        case "quit":
                            System.out.println("BYE");
                            System.out.flush();
                            return;
                        default:
                            System.out.println("ERROR unknown-command " + cmd);
                            System.out.flush();
                    }
                } catch (Throwable t) {
                    System.out.println(
                            "ERROR " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    System.out.flush();
                }
            }
        } catch (Throwable t) {
            System.out.println("ERROR fatal: " + t);
            System.out.flush();
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Throwable ignored) {
                }
            }
            LiveServerClient.shutdownSharedEngine();
        }
    }
}
