package io.pzstorm.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Build-time facts baked in by Gradle (storm-launcher.properties). */
public final class LauncherInfo {

    private static final Properties PROPS = load();

    private LauncherInfo() {}

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in =
                LauncherInfo.class.getResourceAsStream("/storm-launcher.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // fall through to defaults; version shows as "dev"
        }
        return props;
    }

    /** The launcher's own version, independent of the Storm/game version. */
    public static String version() {
        return PROPS.getProperty("version", "dev");
    }

    /**
     * CloudFront URL of the published launcher jar for the CDN self-update; empty disables the
     * check (dev builds run from class dirs, tests point this at a local server via {@code
     * -Dstorm.launcher.updateUrl}).
     */
    public static String updateUrl() {
        String override = System.getProperty("storm.launcher.updateUrl");
        if (override != null) {
            return override;
        }
        return PROPS.getProperty("updateUrl", "");
    }

    /** Storm workshop item IDs (prod, stage, dev) used to locate the bootstrap dir. */
    public static List<String> workshopIds() {
        List<String> ids = new ArrayList<>();
        for (String id : PROPS.getProperty("workshopIds", "").split(",")) {
            if (!id.trim().isEmpty()) {
                ids.add(id.trim());
            }
        }
        return ids;
    }
}
