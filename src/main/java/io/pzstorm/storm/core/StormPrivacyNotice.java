package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Logs the Terms of Use &amp; Privacy Policy at startup. The document is the launcher's {@code
 * privacy-policy.txt}, copied into storm.jar by {@code processResources} so both ship the same
 * text. Launcher users accept it in a dialog; dedicated-server operators and workshop-only clients
 * never see that dialog, so the log is their notice.
 */
public final class StormPrivacyNotice {

    static final String RESOURCE = "/privacy-policy.txt";
    static final String VERSION_PREFIX = "Version:";

    private StormPrivacyNotice() {}

    public static void log() {
        String text = load();
        if (text == null) {
            LOGGER.warn("Storm privacy policy: resource {} missing from storm.jar", RESOURCE);
            return;
        }
        String version = parseVersion(text);
        // Storm's logback config has no console appender, so a server operator watching the
        // console would never see the notice unless it is also printed to stdout.
        System.out.println(
                "By running Storm you agree to the Storm Terms of Use & Privacy Policy (version "
                        + version
                        + "). Full text follows; it also ships as "
                        + RESOURCE
                        + " inside storm.jar.");
        System.out.println(text);
        LOGGER.info(
                "By running Storm you agree to the Storm Terms of Use & Privacy Policy"
                        + " (version {}). Full text follows; it also ships as {} inside"
                        + " storm.jar.\n{}",
                version,
                RESOURCE,
                text);
    }

    static String load() {
        try (InputStream in = StormPrivacyNotice.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .strip();
        } catch (IOException e) {
            LOGGER.warn("Storm privacy policy: could not read {}", RESOURCE, e);
            return null;
        }
    }

    static String parseVersion(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(VERSION_PREFIX)) {
                String version = trimmed.substring(VERSION_PREFIX.length()).strip();
                if (!version.isEmpty()) {
                    return version;
                }
            }
        }
        return "unversioned";
    }
}
