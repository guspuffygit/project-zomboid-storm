package io.pzstorm.storm.liveserver;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Parent-side handle to a child JVM running {@link LiveServerClientMain}. Each process holds one
 * RakNet peer and therefore one real connection to the test server. Used from tests that need more
 * than one simultaneous logical client against the same server.
 */
public final class LiveServerClientProcess implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();
    private final Thread stdoutReader;
    private final String tag;

    private LiveServerClientProcess(Process process, String tag) {
        this.process = process;
        this.stdin =
                new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.tag = tag;
        this.stdoutReader =
                new Thread(
                        () -> {
                            try (BufferedReader r =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(),
                                                    StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = r.readLine()) != null) {
                                    System.out.println("[" + tag + "] " + line);
                                    if (line.startsWith("CONNECTED")
                                            || line.startsWith("SENT")
                                            || line.startsWith("ERROR")
                                            || line.startsWith("BYE")) {
                                        responses.offer(line);
                                    }
                                }
                            } catch (IOException ignored) {
                            }
                        },
                        "live-client-proc-" + tag);
        this.stdoutReader.setDaemon(true);
        this.stdoutReader.start();
    }

    public static LiveServerClientProcess spawn(String tag) throws IOException {
        String javaBin =
                System.getProperty("java.home")
                        + java.io.File.separator
                        + "bin"
                        + java.io.File.separator
                        + "java";
        String classpath = System.getProperty("java.class.path");
        String serverPath = System.getProperty(ServerExtension.SERVER_PATH_PROPERTY);
        String ldLib = System.getenv("LD_LIBRARY_PATH");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("-Djava.library.path=" + buildNativePath(serverPath));
        cmd.add("-Dstorm.server.path=" + serverPath);
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add(LiveServerClientMain.class.getName());

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (ldLib != null) {
            pb.environment().put("LD_LIBRARY_PATH", buildLdPath(serverPath, ldLib));
        } else {
            pb.environment().put("LD_LIBRARY_PATH", buildLdPath(serverPath, ""));
        }
        Process p = pb.start();
        return new LiveServerClientProcess(p, tag);
    }

    private static String buildNativePath(String serverPath) {
        if (serverPath == null) {
            return "";
        }
        return String.join(
                java.io.File.pathSeparator,
                serverPath + "/natives",
                serverPath + "/linux64",
                serverPath + "/jre64/lib/amd64");
    }

    private static String buildLdPath(String serverPath, String existing) {
        List<String> parts = new ArrayList<>();
        if (serverPath != null) {
            Collections.addAll(
                    parts,
                    serverPath + "/natives",
                    serverPath + "/linux64",
                    serverPath + "/jre64/lib/amd64");
        }
        if (existing != null && !existing.isEmpty()) {
            parts.add(existing);
        }
        return String.join(":", parts);
    }

    public long connect(
            String host,
            int port,
            String serverPassword,
            String username,
            String password,
            Duration timeout)
            throws Exception {
        String pwToken = serverPassword.isEmpty() ? "__EMPTY__" : serverPassword;
        send(
                "connect "
                        + host
                        + " "
                        + port
                        + " "
                        + pwToken
                        + " "
                        + username
                        + " "
                        + password
                        + " "
                        + timeout.toSeconds());
        String line = awaitResponse(timeout.plusSeconds(5));
        if (line == null || !line.startsWith("CONNECTED")) {
            throw new IllegalStateException("[" + tag + "] connect failed: " + line);
        }
        return Long.parseLong(line.split("\\s+")[1]);
    }

    public void sendRawNetTimedActionBytes(byte actionByteId, long duration) throws Exception {
        send("send-action " + (actionByteId & 0xFF) + " " + duration);
        String line = awaitResponse(Duration.ofSeconds(10));
        if (line == null || !line.startsWith("SENT")) {
            throw new IllegalStateException("[" + tag + "] send-action failed: " + line);
        }
    }

    private void send(String line) throws IOException {
        stdin.write(line);
        stdin.newLine();
        stdin.flush();
    }

    private String awaitResponse(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            String line = responses.poll(1, TimeUnit.SECONDS);
            if (line != null) {
                return line;
            }
            if (!process.isAlive()) {
                return "ERROR process-died exit=" + safeExit(process);
            }
        }
        return null;
    }

    private static String safeExit(Process p) {
        try {
            return Integer.toString(p.exitValue());
        } catch (IllegalThreadStateException e) {
            return "running";
        }
    }

    @Override
    public void close() {
        try {
            if (process.isAlive()) {
                send("quit");
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception ignored) {
        }
        if (stdoutReader != null) {
            stdoutReader.interrupt();
        }
    }
}
