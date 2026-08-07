package io.pzstorm.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;

/**
 * Launcher self-update over the CDN, decoupled from the workshop item. The deploy task ({@code
 * ./gradlew :launcher:deployLauncher}) uploads {@code storm-launcher.jar} to S3 with its SHA-256
 * and version attached as object metadata, so a single cheap HEAD through CloudFront answers both
 * "is there something newer?" (version) and "is my own jar already that build?" (hash) without
 * downloading anything.
 *
 * <p>This runs <em>in addition to</em> the workshop staging loop, never instead of it: {@link
 * LauncherStage#runStartupUpdate} first converges with the workshop item per usual, then upgrades
 * over it when the CDN publishes a higher version. The downloaded jar lands in the same
 * content-addressed stage area ({@code stage/<hash>/}) the workshop loop uses, and is only run
 * after its SHA-256 matches the metadata it was published with — a truncated or tampered download
 * can never be executed.
 *
 * <p>Every failure here is soft: no network, a missing object, missing metadata, or a hash mismatch
 * all end as "no CDN update" with the workshop-updated launcher continuing as-is.
 */
public final class CdnUpdate {

    /** S3 object metadata {@code sha256=<hex>} surfaces as this response header. */
    static final String HASH_HEADER = "x-amz-meta-sha256";

    /** S3 object metadata {@code version=<v>} surfaces as this response header. */
    static final String VERSION_HEADER = "x-amz-meta-version";

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(2);

    private CdnUpdate() {}

    /** What the CDN currently publishes, read from the object's metadata headers. */
    record Remote(String version, String sha256) {}

    /**
     * HEADs the published jar and reads its version + hash metadata. Null (never throws) when the
     * URL is unset, unreachable, not a 200, or missing either header — all of which mean "no CDN
     * update available".
     */
    static Remote fetch(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .timeout(CHECK_TIMEOUT)
                            .build();
            HttpResponse<Void> response =
                    client().send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                Log.info("Launcher update check: HTTP " + response.statusCode() + " from " + url);
                return null;
            }
            String version = response.headers().firstValue(VERSION_HEADER).orElse(null);
            String sha256 = response.headers().firstValue(HASH_HEADER).orElse(null);
            if (version == null || sha256 == null) {
                Log.warn(
                        "Launcher update check: "
                                + url
                                + " has no version/sha256 metadata — was it uploaded with"
                                + " deployLauncher?");
                return null;
            }
            return new Remote(version.trim(), sha256.trim().toLowerCase(Locale.ROOT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            Log.info("Launcher update check failed (" + e + ") — continuing without it.");
            return null;
        }
    }

    /**
     * Downloads the published jar into the content-addressed stage area and returns the staged
     * path. Throws unless the downloaded bytes hash to exactly {@code expectedSha256}.
     */
    static Path download(String url, String expectedSha256)
            throws IOException, InterruptedException {
        Files.createDirectories(LauncherPaths.stageDir());
        Path tmp = Files.createTempFile(LauncherPaths.stageDir(), ".download-", ".jar");
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url)).timeout(DOWNLOAD_TIMEOUT).build();
            HttpResponse<Path> response =
                    client().send(
                                    request,
                                    HttpResponse.BodyHandlers.ofFile(
                                            tmp,
                                            StandardOpenOption.WRITE,
                                            StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
            }
            String actual = Sha256.of(tmp);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException(
                        "Downloaded launcher hash "
                                + actual
                                + " does not match published metadata "
                                + expectedSha256);
            }
            return LauncherStage.stage(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * True when {@code remote} is a strictly higher dotted-numeric version than {@code own} — the
     * CDN only ever upgrades, so a workshop item ahead of a stale CDN object wins. Any non-numeric
     * segment compares as "not newer": fail soft, never restart-loop on garbage.
     */
    static boolean isNewer(String remote, String own) {
        if (remote == null || own == null) {
            return false;
        }
        String[] a = remote.trim().split("\\.");
        String[] b = own.trim().split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = numericSegment(a, i);
            int right = numericSegment(b, i);
            if (left < 0 || right < 0) {
                return false;
            }
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    /** Segment as a non-negative int; missing segments are 0, unparseable ones -1. */
    private static int numericSegment(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            int value = Integer.parseInt(parts[i].trim());
            return value < 0 ? -1 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
