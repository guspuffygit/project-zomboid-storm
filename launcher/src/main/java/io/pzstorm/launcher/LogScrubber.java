package io.pzstorm.launcher;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the operating-system account name from text before it leaves the machine in a log report.
 * Logs are full of absolute paths, and on every platform the account name is a path segment: {@code
 * C:\Users\<name>} (Windows, either slash, also {@code /mnt/c/Users/<name>} under WSL), {@code
 * /Users/<name>} (macOS) and {@code /home/<name>} (Linux). JVM fatal-error dumps also print the
 * process environment, so {@code USERNAME=} / {@code USER=} / {@code LOGNAME=} lines are blanked as
 * well. Finally the exact current account name is redacted wherever it stands as a whole word — a
 * last net for anything that prints it bare.
 */
public final class LogScrubber {

    static final String PLACEHOLDER = "<user>";

    /**
     * A home-directory segment. Account names may contain spaces ("John Smith"), so the segment
     * runs to the next separator or line end rather than the next blank; characters that cannot be
     * in a file name on any platform end it too.
     */
    private static final String SEGMENT = "[^\\\\/\\r\\n\"'<>|:;*?,()\\[\\]]+";

    private static final Pattern USERS_DIR =
            Pattern.compile("([\\\\/])(Users)\\1" + SEGMENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern HOME_DIR = Pattern.compile("/home/" + SEGMENT);

    private static final Pattern ENV_LINE =
            Pattern.compile("^(USERNAME|USER|LOGNAME)=.*$", Pattern.MULTILINE);

    /** Account names this short would redact ordinary log vocabulary more than they identify. */
    private static final int MIN_BARE_NAME_LENGTH = 3;

    private LogScrubber() {}

    public static String scrub(String text) {
        return scrub(text, System.getProperty("user.name"));
    }

    /** UTF-8 in, UTF-8 out; a tail cut mid-character decodes to a single replacement char. */
    public static byte[] scrub(byte[] utf8) {
        return scrub(new String(utf8, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    static String scrub(String text, String osUser) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = USERS_DIR.matcher(text).replaceAll("$1$2$1" + PLACEHOLDER);
        out = HOME_DIR.matcher(out).replaceAll("/home/" + PLACEHOLDER);
        out = ENV_LINE.matcher(out).replaceAll("$1=" + PLACEHOLDER);
        if (osUser != null && osUser.length() >= MIN_BARE_NAME_LENGTH) {
            Pattern bare =
                    Pattern.compile(
                            "(?<![\\p{L}\\p{N}_])" + Pattern.quote(osUser) + "(?![\\p{L}\\p{N}_])");
            out = bare.matcher(out).replaceAll(Matcher.quoteReplacement(PLACEHOLDER));
        }
        return out;
    }
}
