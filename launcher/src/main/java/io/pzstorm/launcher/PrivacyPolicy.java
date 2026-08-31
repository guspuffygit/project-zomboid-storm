package io.pzstorm.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The Terms of Use &amp; Privacy Policy the player must accept before the launcher does anything on
 * their behalf. The document ships as a classpath resource; its identity is the SHA-256 of the
 * normalized text, so <em>any</em> edit to the document — not just a remembered version bump —
 * invalidates every recorded acceptance and the launcher asks again. The {@code Version:} header
 * line is informational: it is what the acceptance record in launcher.json shows a human, so keep
 * it in step with real revisions of the document or the acceptance record becomes unreadable.
 */
public final class PrivacyPolicy {

    static final String RESOURCE = "/privacy-policy.txt";
    static final String VERSION_PREFIX = "Version:";

    private final String text;
    private final String version;
    private final String hash;

    private PrivacyPolicy(String text) {
        this.text = text.replace("\r\n", "\n").strip();
        this.version = parseVersion(this.text);
        this.hash = Sha256.of(this.text.getBytes(StandardCharsets.UTF_8));
    }

    /** The document baked into this launcher build. */
    public static PrivacyPolicy current() {
        try (InputStream in = PrivacyPolicy.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing launcher resource " + RESOURCE);
            }
            return new PrivacyPolicy(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read launcher resource " + RESOURCE, e);
        }
    }

    public static PrivacyPolicy of(String text) {
        return new PrivacyPolicy(text);
    }

    public String text() {
        return text;
    }

    public String version() {
        return version;
    }

    public String hash() {
        return hash;
    }

    /** True when the config records acceptance of exactly this document. */
    public boolean isAcceptedBy(LauncherConfig config) {
        return hash.equals(config.acceptedTermsHash);
    }

    /** True when some earlier revision was accepted — the prompt then reads as "updated". */
    public static boolean everAccepted(LauncherConfig config) {
        return !config.acceptedTermsHash.isEmpty();
    }

    public void recordAcceptance(LauncherConfig config) {
        config.acceptedTermsHash = hash;
        config.acceptedTermsVersion = version;
        config.acceptedTermsAt = Instant.now().toString();
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
