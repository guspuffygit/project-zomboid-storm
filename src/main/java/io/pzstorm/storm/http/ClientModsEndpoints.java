package io.pzstorm.storm.http;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.core.StormVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Publishes an operator-curated directory of java mods to the Storm Launcher, which mirrors it on
 * player machines <i>before</i> the game process starts (so no jar is ever locked during an
 * update).
 *
 * <p>Serves {@code <user.home>/Zomboid/storm/client-mods} (override with {@code
 * -Dstorm.client.mods.dir}). Lay out each subdirectory exactly like a normal mod directory ({@code
 * <mod>/common/}, {@code <mod>/42/<jars>}, {@code mod.info}) — the launcher mirrors the tree
 * verbatim and the client's {@code StormModLoader} then catalogs it with the standard rules.
 *
 * <p>Registered only on server JVMs ({@code -Dstorm.server=true}); read-only; every served path is
 * validated against the same rules the launcher enforces.
 */
public class ClientModsEndpoints {

    public static final String CLIENT_MODS_DIR_PROPERTY = "storm.client.mods.dir";

    /** Refuse to buffer absurd files rather than OOM the HTTP dispatcher thread. */
    private static final long MAX_FILE_SIZE = 256L * 1024 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Keep in sync with io.pzstorm.launcher.ModManifest#validateRelativePath — the
    // launcher re-validates everything it receives, so a mismatch only causes syncs
    // to fail, never traversal.
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ +-]*");
    private static final Pattern WINDOWS_RESERVED =
            Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");

    /** SHA-256 cache keyed by relative path; entries invalidate on size/mtime change. */
    private static final Map<String, HashCacheEntry> HASH_CACHE = new ConcurrentHashMap<>();

    private record HashCacheEntry(long size, long mtime, String sha256) {}

    static Path clientModsDirectory() {
        String override = System.getProperty(CLIENT_MODS_DIR_PROPERTY);
        if (override != null && !override.isEmpty()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), "Zomboid", "storm", "client-mods");
    }

    @HttpEndpoint(path = "/storm/client/manifest")
    public static void manifest(HttpRequestEvent event) throws IOException {
        Map<String, Object> manifest = buildManifest(clientModsDirectory());
        manifest.put("workshopItems", serverWorkshopItems());
        event.sendJson(200, MAPPER.writeValueAsString(manifest));
    }

    /**
     * The server's required workshop item ids (server.ini WorkshopItems, already parsed/validated
     * by GameServer at boot). The launcher pre-updates these via Steam before the game starts, so
     * the join-time workshop screen never fires. Kept out of {@link #buildManifest} so that method
     * stays free of PZ classes.
     */
    private static List<String> serverWorkshopItems() {
        try {
            List<String> ids = new ArrayList<>();
            for (Long itemId : zombie.network.GameServer.WorkshopItems) {
                ids.add(String.valueOf(itemId));
            }
            return ids;
        } catch (Throwable t) {
            LOGGER.warn("Could not read GameServer.WorkshopItems: {}", t.toString());
            return new ArrayList<>();
        }
    }

    @HttpEndpoint(path = "/storm/client/file")
    public static void file(HttpRequestEvent event) throws IOException {
        String relative = event.getQueryParams().get("path");
        if (relative == null || !isValidRelativePath(relative)) {
            event.send(400, "missing or invalid 'path' query parameter");
            return;
        }
        Path root = clientModsDirectory().toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            event.send(404, "no such file: " + relative);
            return;
        }
        if (Files.size(resolved) > MAX_FILE_SIZE) {
            event.send(413, "file too large to serve: " + relative);
            return;
        }
        event.setContentType("application/octet-stream");
        event.send(200, Files.readAllBytes(resolved));
    }

    /** Package-visible for tests. */
    static Map<String, Object> buildManifest(Path rootDir) throws IOException {
        Path root = rootDir.toAbsolutePath().normalize();
        List<String> dirs = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();

        if (Files.isDirectory(root)) {
            List<Path> entries;
            try (Stream<Path> walk = Files.walk(root)) {
                entries =
                        walk.filter(p -> !p.equals(root))
                                .sorted(Comparator.naturalOrder())
                                .toList();
            }
            for (Path entry : entries) {
                String relative = root.relativize(entry).toString().replace('\\', '/');
                if (!isValidRelativePath(relative)) {
                    LOGGER.warn(
                            "client-mods entry '{}' has an unpublishable name — skipping",
                            relative);
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    dirs.add(relative);
                } else if (Files.isRegularFile(entry)) {
                    Map<String, Object> file = new LinkedHashMap<>();
                    file.put("path", relative);
                    file.put("sha256", cachedSha256(entry, relative));
                    file.put("size", Files.size(entry));
                    files.add(file);
                }
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("stormVersion", StormVersion.getVersion());
        manifest.put("dirs", dirs);
        manifest.put("files", files);
        return manifest;
    }

    private static String cachedSha256(Path file, String relative) throws IOException {
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        HashCacheEntry cached = HASH_CACHE.get(relative);
        if (cached != null && cached.size() == size && cached.mtime() == mtime) {
            return cached.sha256();
        }
        String sha256 = sha256(file);
        HASH_CACHE.put(relative, new HashCacheEntry(size, mtime, sha256));
        return sha256;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Package-visible for tests. */
    static boolean isValidRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512) {
            return false;
        }
        if (path.contains("\\") || path.startsWith("/") || path.endsWith("/")) {
            return false;
        }
        String[] segments = path.split("/", -1);
        if (segments.length > 8) {
            return false;
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()
                    || segment.endsWith(" ")
                    || segment.endsWith(".")
                    || WINDOWS_RESERVED.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }
}
