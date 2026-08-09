package io.pzstorm.storm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Storm-core self-update over the CDN, decoupled from the workshop item — the storm.jar analogue of
 * the launcher's {@code io.pzstorm.launcher.CdnUpdate}. The deploy task ({@code ./gradlew
 * deployStormJar}) uploads the storm jar to S3 with its SHA-256 and stormVersion attached as object
 * metadata, so a single cheap HEAD through CloudFront answers both "is there something newer?"
 * (version) and "do I already have that build?" (hash) without downloading anything.
 *
 * <p>Unlike the launcher there is no restart hop: this runs in {@link StormBootstrapper#bootstrap}
 * before any storm.jar class is loaded, so a newer jar is simply staged outside the workshop item
 * (Steam owns the item's files — never write there) and substituted into the classpath. The stage
 * is content-addressed ({@code <zomboidDir>/storm/core/stage/<hash>/storm.jar}) and a download is
 * only used after its SHA-256 matches the metadata it was published with.
 *
 * <p>The CDN key is versioned by game build ({@code /storm/core/<pzVersion>/storm.jar}, both
 * versions parsed from the item jar's filename), so a jar built against one PZ build can never be
 * offered to another, and a PZ update naturally moves clients to a fresh (initially empty) key.
 *
 * <p>Every failure here is soft: no network, a missing object, missing metadata, a hash mismatch,
 * or an unparseable jar name all end as "no CDN update" with the workshop item's jar loading as-is.
 * SNAPSHOT builds never update — a dev install's working-tree build always wins.
 */
public final class StormCoreUpdate {

    /** S3 object metadata {@code sha256=<hex>} surfaces as this response header. */
    static final String HASH_HEADER = "x-amz-meta-sha256";

    /** S3 object metadata {@code version=<v>} surfaces as this response header. */
    static final String VERSION_HEADER = "x-amz-meta-version";

    /** Full-URL override; set empty to disable the check entirely. */
    static final String URL_PROPERTY = "storm.core.updateUrl";

    /** Test override for the Zomboid user dir the stage area lives under. */
    static final String ZOMBOID_DIR_PROPERTY = "storm.core.zomboidDir";

    /** Must match deployStormJar's upload key in build.gradle. */
    static final String DEFAULT_URL_TEMPLATE = "https://guspuffy.com/storm/core/%s/storm.jar";

    static final String JAR_NAME = "storm.jar";

    private static final Pattern JAR_PATTERN =
            Pattern.compile("storm-([0-9.]+)_([0-9.]+)(-SNAPSHOT)?\\.jar", Pattern.CASE_INSENSITIVE);

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(2);

    private StormCoreUpdate() {}

    /** Versions carried by the workshop item's jar filename. */
    record LocalJar(String pzVersion, String stormVersion, boolean snapshot) {}

    /** What the CDN currently publishes, read from the object's metadata headers. */
    record Remote(String version, String sha256) {}

    /**
     * The single entry point: given the workshop item's storm jar, returns the jar the bootstrap
     * should load — a staged CDN build when one with a strictly higher stormVersion is published,
     * otherwise {@code itemJar} itself. Never throws.
     */
    public static Path resolve(Path itemJar) {
        try {
            LocalJar local = parse(itemJar.getFileName().toString());
            if (local == null) {
                log("Jar name " + itemJar.getFileName() + " carries no version — skipping update check.");
                return itemJar;
            }
            if (local.snapshot()) {
                log("SNAPSHOT build — the CDN never overrides a dev install.");
                return itemJar;
            }
            String url = updateUrl(local.pzVersion());
            if (url == null) {
                return itemJar;
            }
            Remote remote = fetch(url);
            if (remote == null) {
                // offline or nothing published: keep any cached stage for when the network returns
                return itemJar;
            }
            if (!isNewer(remote.version(), local.stormVersion())) {
                gcStaleStages(null);
                return itemJar;
            }
            if (remote.sha256().equalsIgnoreCase(Sha256.of(itemJar))) {
                gcStaleStages(null);
                return itemJar;
            }
            Path staged = stagedPath(remote.sha256());
            if (!Files.isRegularFile(staged) || !Sha256.of(staged).equalsIgnoreCase(remote.sha256())) {
                log("Downloading Storm core " + remote.version() + " (item has " + local.stormVersion() + ")...");
                staged = download(url, remote.sha256());
            }
            gcStaleStages(staged);
            log("Using CDN Storm core " + remote.version() + " from " + staged);
            return staged;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return itemJar;
        } catch (Exception e) {
            log("Update check failed (" + e + ") — loading the workshop item's jar.");
            return itemJar;
        }
    }

    /** {@code storm-<pzVersion>_<stormVersion>[-SNAPSHOT].jar}, or null when the name doesn't match. */
    static LocalJar parse(String fileName) {
        Matcher m = JAR_PATTERN.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        return new LocalJar(m.group(1), m.group(2), m.group(3) != null);
    }

    /** Property override (empty disables), else the default key for this game build. */
    static String updateUrl(String pzVersion) {
        String override = System.getProperty(URL_PROPERTY);
        if (override != null) {
            return override.isBlank() ? null : override;
        }
        return String.format(DEFAULT_URL_TEMPLATE, pzVersion);
    }

    /**
     * HEADs the published jar and reads its version + hash metadata. Null (never throws) when the
     * URL is unreachable, not a 200, or missing either header — all meaning "no CDN update".
     */
    static Remote fetch(String url) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .timeout(CHECK_TIMEOUT)
                            .build();
            HttpResponse<Void> response =
                    client().send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                log("Update check: HTTP " + response.statusCode() + " from " + url);
                return null;
            }
            String version = response.headers().firstValue(VERSION_HEADER).orElse(null);
            String sha256 = response.headers().firstValue(HASH_HEADER).orElse(null);
            if (version == null || sha256 == null) {
                log("Update check: " + url + " has no version/sha256 metadata — was it uploaded with deployStormJar?");
                return null;
            }
            return new Remote(version.trim(), sha256.trim().toLowerCase(Locale.ROOT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log("Update check failed (" + e + ") — continuing without it.");
            return null;
        }
    }

    /**
     * Downloads the published jar into the content-addressed stage area and returns the staged
     * path. Throws unless the downloaded bytes hash to exactly {@code expectedSha256} — a truncated
     * or tampered download can never be loaded.
     */
    static Path download(String url, String expectedSha256) throws IOException, InterruptedException {
        Files.createDirectories(stageDir());
        Path tmp = Files.createTempFile(stageDir(), ".download-", ".jar");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(DOWNLOAD_TIMEOUT).build();
            HttpResponse<Path> response =
                    client().send(
                            request,
                            HttpResponse.BodyHandlers.ofFile(
                                    tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
            }
            String actual = Sha256.of(tmp);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException(
                        "Downloaded storm jar hash " + actual + " does not match published metadata " + expectedSha256);
            }
            return stage(tmp, actual);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * True when {@code remote} is a strictly higher dotted-numeric version than {@code own} — the
     * CDN only ever upgrades, so a workshop item ahead of a stale CDN object wins. Any non-numeric
     * segment (including {@code -SNAPSHOT}) compares as "not newer": fail soft.
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

    static Path stageDir() {
        String override = System.getProperty(ZOMBOID_DIR_PROPERTY);
        Path zomboidDir =
                override != null && !override.isBlank()
                        ? Paths.get(override)
                        : Paths.get(System.getProperty("user.home"), "Zomboid");
        return zomboidDir.resolve("storm").resolve("core").resolve("stage");
    }

    static Path stagedPath(String sha256) {
        return stageDir().resolve(sha256.substring(0, 16)).resolve(JAR_NAME);
    }

    /**
     * Content-addressed copy into {@code stage/<hash>/}, idempotent and race-safe: two racing JVMs
     * converge on the same dir and the loser of the rename just uses the winner's identical copy.
     */
    private static Path stage(Path verified, String sha256) throws IOException {
        Path target = stagedPath(sha256);
        if (Files.isRegularFile(target)) {
            return target;
        }
        Path tmpDir = Files.createTempDirectory(stageDir(), ".tmp-");
        Files.copy(verified, tmpDir.resolve(JAR_NAME), StandardCopyOption.REPLACE_EXISTING);
        try {
            move(tmpDir, target.getParent());
        } catch (IOException e) {
            deleteRecursively(tmpDir);
            if (!Files.isRegularFile(target)) {
                throw e;
            }
        }
        return target;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /**
     * Staged builds pile up one per version; sweep all but the one in use. Best-effort: a running
     * JVM's mapped jar refuses deletion on Windows, harmlessly, until a later sweep.
     */
    static void gcStaleStages(Path inUseJar) {
        Path stageRoot = stageDir();
        if (!Files.isDirectory(stageRoot)) {
            return;
        }
        Path keep = inUseJar == null ? null : inUseJar.toAbsolutePath().normalize().getParent();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(stageRoot)) {
            for (Path entry : entries) {
                if (keep == null || !entry.toAbsolutePath().normalize().equals(keep)) {
                    deleteRecursively(entry);
                }
            }
        } catch (IOException ignored) {
            // sweep again next boot
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // locked by a running JVM — next sweep gets it
                                }
                            });
        } catch (IOException ignored) {
            // already gone or unreadable — next sweep gets it
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static void log(String message) {
        System.out.println("[StormCoreUpdate] " + message);
    }
}
