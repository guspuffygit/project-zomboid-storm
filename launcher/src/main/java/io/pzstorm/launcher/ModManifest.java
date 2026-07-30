package io.pzstorm.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parsed {@code GET /storm/client/manifest} response. The manifest describes a file tree (standard
 * Project Zomboid mod-directory layout) that the launcher mirrors locally before the game starts:
 *
 * <pre>
 * {
 *   "stormVersion": "42.20.0_2.4.0",
 *   "dirs":  ["my-mod/common", "my-mod/42"],
 *   "files": [{"path": "my-mod/mod.info", "sha256": "…", "size": 123}, …]
 * }
 * </pre>
 */
public final class ModManifest {

    public static final String MANIFEST_PATH = "/storm/client/manifest";
    public static final String FILE_PATH = "/storm/client/file";

    /** One path segment: no separators, no dot-only names, conservative charset. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ +-]*");

    /** Windows device names are unusable as file/dir names even with an extension. */
    private static final Pattern WINDOWS_RESERVED =
            Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");

    public final String stormVersion;
    public final List<String> dirs;
    public final List<Entry> files;

    /** Steam workshop item ids the server requires; launcher pre-updates them via Steam. */
    public final List<String> workshopItems;

    public ModManifest(String stormVersion, List<String> dirs, List<Entry> files) {
        this(stormVersion, dirs, files, new ArrayList<>());
    }

    public ModManifest(
            String stormVersion, List<String> dirs, List<Entry> files, List<String> workshopItems) {
        this.stormVersion = stormVersion;
        this.dirs = dirs;
        this.files = files;
        this.workshopItems = workshopItems;
    }

    public static final class Entry {
        public final String path;
        public final String sha256;
        public final long size;

        public Entry(String path, String sha256, long size) {
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    public static ModManifest parse(String json) {
        Map<String, Object> root = Json.parseObject(json);
        String stormVersion = ServerProfile.str(root.get("stormVersion"), "unknown");
        List<String> dirs = new ArrayList<>();
        Object rawDirs = root.get("dirs");
        if (rawDirs instanceof List) {
            for (Object dir : (List<?>) rawDirs) {
                dirs.add(validateRelativePath(String.valueOf(dir)));
            }
        }
        List<String> workshopItems = new ArrayList<>();
        Object rawItems = root.get("workshopItems");
        if (rawItems instanceof List) {
            for (Object item : (List<?>) rawItems) {
                String id = String.valueOf(item).trim();
                if (!id.matches("\\d{1,20}")) {
                    throw new Json.JsonException("bad workshop item id: " + id);
                }
                workshopItems.add(id);
            }
        }
        List<Entry> files = new ArrayList<>();
        Object rawFiles = root.get("files");
        if (rawFiles instanceof List) {
            for (Object entry : (List<?>) rawFiles) {
                if (!(entry instanceof Map)) {
                    throw new Json.JsonException("manifest file entry is not an object");
                }
                Map<?, ?> map = (Map<?, ?>) entry;
                String path = validateRelativePath(String.valueOf(map.get("path")));
                String sha256 = String.valueOf(map.get("sha256")).toLowerCase();
                if (!sha256.matches("[0-9a-f]{64}")) {
                    throw new Json.JsonException("bad sha256 for " + path);
                }
                long size = ServerProfile.num(map.get("size"), -1);
                if (size < 0) {
                    throw new Json.JsonException("bad size for " + path);
                }
                files.add(new Entry(path, sha256, size));
            }
        }
        return new ModManifest(stormVersion, dirs, files, workshopItems);
    }

    /**
     * Both sides enforce this: forward slashes, short conservative segments, and no way to escape
     * the sync root. Throws on anything suspicious.
     */
    public static String validateRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512) {
            throw new IllegalArgumentException("bad manifest path: " + path);
        }
        if (path.contains("\\") || path.startsWith("/") || path.endsWith("/")) {
            throw new IllegalArgumentException("bad manifest path: " + path);
        }
        String[] segments = path.split("/", -1);
        if (segments.length > 8) {
            throw new IllegalArgumentException("manifest path too deep: " + path);
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()
                    || segment.endsWith(" ")
                    || segment.endsWith(".")
                    || WINDOWS_RESERVED.matcher(segment).matches()) {
                throw new IllegalArgumentException("bad manifest path segment: " + path);
            }
        }
        return path;
    }
}
