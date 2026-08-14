package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deletes stale versioned storm jars from the workshop item's lib dir before the game boots. Steam
 * sometimes leaves an old {@code storm-<pzVersion>_<stormVersion>.jar} behind when a workshop
 * update renames the jar (seen in the wild: {@code storm-42.19.0_2.3.1-SNAPSHOT.jar} lingering next
 * to {@code storm-42.20.2_2.5.5.jar}), and the game-side {@code URLClassLoader} resolves
 * first-URL-wins — the stale jar's classes silently shadow the current ones, which on old builds
 * NPEs {@code Core.loadOptions} mid-parse (resolution reset to 1280x720, TOS re-shown every start).
 * The guard lives here rather than in the bootstrap because the bootstrap only ships via a workshop
 * publish; the launcher self-updates over the CDN, so cleaning here reaches affected players
 * without one.
 *
 * <p>Only jars whose names carry a parseable version AND compare strictly older than the newest one
 * present are deleted — an unparseable name is left alone (deleting a file we can't rank is riskier
 * than leaving it). Every failure is soft: the launcher runs before the game JVM exists, so nothing
 * should hold these files, but a Windows lock from a lingering game process just logs a warning and
 * the next launch retries.
 */
public final class StaleStormJarCleanup {

    /** Keep in sync with {@code io.pzstorm.storm.StormCoreUpdate#JAR_PATTERN}. */
    private static final Pattern JAR_PATTERN =
            Pattern.compile(
                    "storm-([0-9.]+)_([0-9.]+)(-SNAPSHOT)?\\.jar", Pattern.CASE_INSENSITIVE);

    private StaleStormJarCleanup() {}

    record JarVersion(String pzVersion, String stormVersion, boolean snapshot) {}

    /** Never throws — a failed cleanup must not block the launch. */
    public static void run(Path bootstrapDir) {
        try {
            Path libDir = libDirOf(bootstrapDir);
            if (libDir == null || !Files.isDirectory(libDir)) {
                return;
            }
            List<Path> stormJars = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(libDir)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.startsWith("storm-")
                            && name.endsWith(".jar")
                            && !name.equals("storm-bootstrap.jar")
                            && Files.isRegularFile(entry)) {
                        stormJars.add(entry);
                    }
                }
            }
            for (Path stale : staleJars(stormJars)) {
                try {
                    Files.delete(stale);
                    Log.info(
                            "Deleted stale storm jar Steam left behind after a workshop update: "
                                    + stale);
                } catch (IOException e) {
                    Log.warn(
                            "Could not delete stale storm jar "
                                    + stale
                                    + " (still locked by a running game?) — "
                                    + e);
                }
            }
        } catch (Exception e) {
            Log.warn("Stale storm jar cleanup failed — continuing with the launch: " + e);
        }
    }

    /**
     * The dir the bootstrap loads libraries from. Keep in sync with {@code
     * io.pzstorm.storm.StormBootstrapper}, which resolves {@code ../42/lib} relative to its own
     * jar's directory (the bootstrap dir).
     */
    static Path libDirOf(Path bootstrapDir) {
        if (bootstrapDir == null) {
            return null;
        }
        Path stormDir = bootstrapDir.toAbsolutePath().normalize().getParent();
        return stormDir == null ? null : stormDir.resolve("42").resolve("lib");
    }

    /**
     * The jars safe to delete: parseable versioned names strictly older than the newest parseable
     * one, on (pzVersion, stormVersion) dotted-numeric compare with release over snapshot at a tied
     * version.
     */
    static List<Path> staleJars(List<Path> stormJars) {
        JarVersion bestVer = null;
        for (Path jar : stormJars) {
            JarVersion ver = parse(jar.getFileName().toString());
            if (ver != null && (bestVer == null || compareVersions(ver, bestVer) > 0)) {
                bestVer = ver;
            }
        }
        List<Path> stale = new ArrayList<>();
        if (bestVer == null) {
            return stale;
        }
        for (Path jar : stormJars) {
            JarVersion ver = parse(jar.getFileName().toString());
            if (ver != null && compareVersions(ver, bestVer) < 0) {
                stale.add(jar);
            }
        }
        return stale;
    }

    static JarVersion parse(String fileName) {
        Matcher m = JAR_PATTERN.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        return new JarVersion(m.group(1), m.group(2), m.group(3) != null);
    }

    static int compareVersions(JarVersion a, JarVersion b) {
        int pz = compareDottedNumeric(a.pzVersion(), b.pzVersion());
        if (pz != 0) {
            return pz;
        }
        int st = compareDottedNumeric(a.stormVersion(), b.stormVersion());
        if (st != 0) {
            return st;
        }
        if (a.snapshot() == b.snapshot()) {
            return 0;
        }
        return a.snapshot() ? -1 : 1;
    }

    static int compareDottedNumeric(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            int left = numericSegment(as, i);
            int right = numericSegment(bs, i);
            if (left < 0 || right < 0) {
                return 0;
            }
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
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
}
