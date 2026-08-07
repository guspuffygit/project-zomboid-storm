package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader for the game's own launcher config ({@code ProjectZomboid64.json}) — the same file
 * ProjectZomboid64.exe reads. Using it as the source of truth means the launcher always mirrors
 * what a vanilla boot would do (mainClass, classpath, vmArgs) and picks up game-update changes
 * automatically.
 */
public final class PzGameJson {

    public final String mainClass;
    public final List<String> classpath;
    public final List<String> vmArgs;

    /** Windows-version-keyed extra vmArgs, e.g. "10.0.17134" -> [-XX:+UseZGC]. */
    public final Map<String, List<String>> windowsOverlays;

    PzGameJson(
            String mainClass,
            List<String> classpath,
            List<String> vmArgs,
            Map<String, List<String>> windowsOverlays) {
        this.mainClass = mainClass;
        this.classpath = classpath;
        this.vmArgs = vmArgs;
        this.windowsOverlays = windowsOverlays;
    }

    public static PzGameJson read(Path gameDir) throws IOException {
        Path file = gameDir.resolve("ProjectZomboid64.json");
        if (!Files.isRegularFile(file)) {
            // the mac depot ships no json — the app bundle's Info.plist is the source of truth
            PzGameJson mac = MacAppBundle.gameJson(gameDir);
            if (mac != null) {
                return mac;
            }
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return parse(text);
    }

    @SuppressWarnings("unchecked")
    public static PzGameJson parse(String json) {
        Map<String, Object> root = Json.parseObject(json);
        String mainClass = ServerProfile.str(root.get("mainClass"), "").replace('/', '.');
        if (mainClass.isEmpty()) {
            throw new IllegalArgumentException("ProjectZomboid64.json has no mainClass");
        }
        List<String> classpath = strings(root.get("classpath"));
        if (classpath.isEmpty()) {
            throw new IllegalArgumentException("ProjectZomboid64.json has no classpath");
        }
        List<String> vmArgs = strings(root.get("vmArgs"));
        Map<String, List<String>> overlays = new LinkedHashMap<>();
        Object windows = root.get("windows");
        if (windows instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) windows).entrySet()) {
                if (entry.getValue() instanceof Map) {
                    overlays.put(
                            entry.getKey(),
                            strings(((Map<String, Object>) entry.getValue()).get("vmArgs")));
                }
            }
        }
        return new PzGameJson(mainClass, classpath, vmArgs, overlays);
    }

    /**
     * Base vmArgs plus the highest-versioned windows overlay the current OS satisfies (matching
     * ProjectZomboid64.exe's behavior of picking the newest applicable block). Non-Windows gets the
     * base args only.
     */
    public List<String> effectiveVmArgs(String osName, String osVersion) {
        List<String> args = new ArrayList<>(vmArgs);
        if (osName == null || !osName.toLowerCase().contains("win")) {
            return args;
        }
        String bestKey = null;
        for (String key : windowsOverlays.keySet()) {
            if (versionSatisfies(osVersion, key)
                    && (bestKey == null || compareVersions(key, bestKey) > 0)) {
                bestKey = key;
            }
        }
        if (bestKey != null) {
            args.addAll(windowsOverlays.get(bestKey));
        }
        return args;
    }

    /**
     * Lenient comparison: only as many components as both sides supply are compared, so java's
     * "10.0" satisfies the exe's "10.0.17134" key.
     */
    static boolean versionSatisfies(String osVersion, String requiredKey) {
        if (osVersion == null || osVersion.isEmpty()) {
            return false;
        }
        int[] os = parseVersion(osVersion);
        int[] required = parseVersion(requiredKey);
        int common = Math.min(os.length, required.length);
        for (int i = 0; i < common; i++) {
            if (os[i] != required[i]) {
                return os[i] > required[i];
            }
        }
        return true;
    }

    static int compareVersions(String a, String b) {
        int[] va = parseVersion(a);
        int[] vb = parseVersion(b);
        for (int i = 0; i < Math.max(va.length, vb.length); i++) {
            int ca = i < va.length ? va[i] : 0;
            int cb = i < vb.length ? vb[i] : 0;
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        String[] parts = version.trim().split("\\.");
        List<Integer> numbers = new ArrayList<>();
        for (String part : parts) {
            try {
                numbers.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                break;
            }
        }
        int[] result = new int[numbers.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = numbers.get(i);
        }
        return result;
    }

    private static List<String> strings(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null && !String.valueOf(item).isEmpty()) {
                    list.add(String.valueOf(item));
                }
            }
        }
        return list;
    }
}
