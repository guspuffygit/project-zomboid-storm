package io.pzstorm.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mirrors the server-published mod tree into a local directory, before the game process exists — so
 * no jar is ever locked while we replace it. Every file is verified against the manifest's SHA-256;
 * local files that fall out of the manifest are deleted so the directory always equals what the
 * server publishes.
 */
public final class ModSync {

    private final HttpClient http;

    public ModSync() {
        this.http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public static final class SyncResult {
        public int downloaded;
        public int kept;
        public int deleted;
        public long downloadedBytes;
        public String stormVersion = "unknown";
    }

    public static class SyncException extends RuntimeException {
        public SyncException(String message) {
            super(message);
        }

        public SyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Base URI of the server's Storm HTTP endpoint, e.g. {@code http://host:41798}. */
    public static URI baseUri(String host, int stormHttpPort) {
        return URI.create("http://" + host + ":" + stormHttpPort);
    }

    public String fetchServerStormVersion(URI base) {
        try {
            return getString(base.resolve("/storm/version")).trim();
        } catch (Exception e) {
            return null;
        }
    }

    public ModManifest fetchManifest(URI base) {
        String body;
        try {
            body = getString(base.resolve(ModManifest.MANIFEST_PATH));
        } catch (Exception e) {
            throw new SyncException(
                    "Could not fetch mod manifest from " + base + ": " + e.getMessage(), e);
        }
        try {
            return ModManifest.parse(body);
        } catch (RuntimeException e) {
            throw new SyncException("Server sent an invalid mod manifest: " + e.getMessage(), e);
        }
    }

    public SyncResult sync(URI base, ModManifest manifest, Path targetDir) throws IOException {
        SyncResult result = new SyncResult();
        result.stormVersion = manifest.stormVersion;
        Files.createDirectories(targetDir);
        Path root = targetDir.toAbsolutePath().normalize();

        for (String dir : manifest.dirs) {
            Files.createDirectories(contained(root, dir));
        }

        Set<String> wanted = new HashSet<>();
        for (ModManifest.Entry entry : manifest.files) {
            wanted.add(entry.path);
            Path local = contained(root, entry.path);
            if (Files.isRegularFile(local)
                    && Files.size(local) == entry.size
                    && Sha256.of(local).equals(entry.sha256)) {
                result.kept++;
                continue;
            }
            download(base, entry, local);
            result.downloaded++;
            result.downloadedBytes += entry.size;
            Log.info("  downloaded " + entry.path + " (" + entry.size + " bytes)");
        }

        result.deleted = deleteOrphans(root, wanted, manifest.dirs);
        return result;
    }

    private void download(URI base, ModManifest.Entry entry, Path local) throws IOException {
        URI uri =
                base.resolve(
                        ModManifest.FILE_PATH
                                + "?path="
                                + URLEncoder.encode(entry.path, StandardCharsets.UTF_8));
        byte[] body;
        try {
            HttpResponse<byte[]> response =
                    http.send(
                            HttpRequest.newBuilder(uri)
                                    .timeout(Duration.ofMinutes(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new SyncException(
                        "HTTP " + response.statusCode() + " downloading " + entry.path);
            }
            body = response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SyncException("Download failed for " + entry.path + ": " + e.getMessage(), e);
        }
        if (body.length != entry.size) {
            throw new SyncException(
                    "Size mismatch for "
                            + entry.path
                            + ": expected "
                            + entry.size
                            + ", got "
                            + body.length);
        }
        String actual = Sha256.of(body);
        if (!actual.equals(entry.sha256)) {
            throw new SyncException(
                    "Checksum mismatch for "
                            + entry.path
                            + " — refusing to install (expected "
                            + entry.sha256
                            + ", got "
                            + actual
                            + ")");
        }
        Files.createDirectories(local.getParent());
        Path tmp = local.resolveSibling(local.getFileName() + ".part");
        Files.write(tmp, body);
        try {
            Files.move(
                    tmp,
                    local,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int deleteOrphans(Path root, Set<String> wanted, List<String> wantedDirs)
            throws IOException {
        int deleted = 0;
        List<Path> everything;
        try (Stream<Path> walk = Files.walk(root)) {
            everything = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        Set<String> keepDirs = new HashSet<>(wantedDirs);
        for (String file : wanted) {
            int slash = file.lastIndexOf('/');
            while (slash > 0) {
                keepDirs.add(file.substring(0, slash));
                slash = file.lastIndexOf('/', slash - 1);
            }
        }
        for (Path path : everything) {
            if (path.equals(root)) {
                continue;
            }
            String rel = root.relativize(path).toString().replace('\\', '/');
            if (Files.isDirectory(path)) {
                if (!keepDirs.contains(rel) && isEmptyDir(path)) {
                    Files.delete(path);
                }
            } else if (!wanted.contains(rel)) {
                Files.delete(path);
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return !entries.findAny().isPresent();
        }
    }

    /** Resolves a validated manifest path and re-checks it cannot escape the root. */
    private static Path contained(Path root, String relative) {
        ModManifest.validateRelativePath(relative);
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new SyncException("Manifest path escapes sync root: " + relative);
        }
        return resolved;
    }

    private String getString(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }
        return response.body();
    }
}
