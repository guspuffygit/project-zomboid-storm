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
 *   RSP: READY                  — emitted once, after client natives are initialized
 *
 *   CLI: connect &lt;host&gt; &lt;port&gt; &lt;serverPassword&gt; &lt;username&gt; &lt;password&gt; &lt;timeoutSeconds&gt;
 *        [startAtEpochMillis]
 *   RSP: CONNECTED &lt;guid&gt;      — on success
 *   RSP: ERROR &lt;message&gt;       — on failure
 *
 *   CLI: tcp-load &lt;host&gt; &lt;gamePort&gt; &lt;steamId&gt; &lt;centerWx&gt; &lt;centerWy&gt; &lt;gridWidth&gt;
 *        &lt;startAtEpochMillis&gt;
 *   RSP: LOADED &lt;json&gt;         — StormTcpLoadingClient.Result
 *   RSP: ERROR &lt;message&gt;
 *
 *   CLI: send-action &lt;actionByteId&gt; &lt;durationMillis&gt;
 *   RSP: SENT
 *   RSP: ERROR &lt;message&gt;
 *
 *   CLI: send-general-action-reject &lt;actionByteId&gt;
 *   RSP: SENT
 *   RSP: ERROR &lt;message&gt;
 *
 *   CLI: send-whisper-start &lt;authorName&gt; &lt;destName&gt;
 *   RSP: SENT
 *   RSP: ERROR &lt;message&gt;
 *
 *   CLI: quit
 *   RSP: BYE                    — before exiting
 * </pre>
 *
 * The parent test matches lines starting with {@code READY}, {@code CONNECTED}, {@code SENT},
 * {@code LOADED}, {@code ERROR}, {@code BYE}. Any other stdout/stderr (including PZ native output)
 * is logged for diagnostics.
 *
 * <p>{@code startAtEpochMillis} is an absolute wall-clock instant the child sleeps until before
 * acting. The parent cannot write to twenty children at the same moment, so it hands each one the
 * same instant instead — that is what makes a simultaneous twenty-client join simultaneous.
 */
public final class LiveServerClientMain {

    private LiveServerClientMain() {}

    public static void main(String[] args) {
        LiveServerClient client = null;
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            LiveServerClient.initClientNativesOnce();
            System.out.println("READY");
            System.out.flush();
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
                                if (parts.length > 7) {
                                    sleepUntil(Long.parseLong(parts[7]));
                                }
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
                        case "send-general-action-reject":
                            {
                                if (client == null) {
                                    System.out.println("ERROR not-connected");
                                    System.out.flush();
                                    break;
                                }
                                byte id = (byte) Integer.parseInt(parts[1]);
                                client.sendGeneralActionReject(id);
                                System.out.println("SENT");
                                System.out.flush();
                                break;
                            }
                        case "send-whisper-start":
                            {
                                if (client == null) {
                                    System.out.println("ERROR not-connected");
                                    System.out.flush();
                                    break;
                                }
                                String authorName = parts[1];
                                String destName = parts[2];
                                client.sendPlayerStartPMChat(authorName, destName);
                                System.out.println("SENT");
                                System.out.flush();
                                break;
                            }
                        case "tcp-load":
                            {
                                String host = parts[1];
                                int gamePort = Integer.parseInt(parts[2]);
                                long steamId = Long.parseLong(parts[3]);
                                int centerWx = Integer.parseInt(parts[4]);
                                int centerWy = Integer.parseInt(parts[5]);
                                int gridWidth = Integer.parseInt(parts[6]);
                                sleepUntil(Long.parseLong(parts[7]));
                                StormTcpLoadingClient.Result result =
                                        new StormTcpLoadingClient(host, gamePort, steamId)
                                                .run(centerWx, centerWy, gridWidth);
                                System.out.println("LOADED " + result.toJson());
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

    private static void sleepUntil(long epochMillis) throws InterruptedException {
        long remaining = epochMillis - System.currentTimeMillis();
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }
}
