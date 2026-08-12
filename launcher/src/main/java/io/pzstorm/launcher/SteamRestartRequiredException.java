package io.pzstorm.launcher;

import java.io.IOException;

/**
 * A join was cancelled because Steam is in a stuck state that only a Steam restart clears. Thrown
 * so the launcher UI can show a dedicated popup with actionable instructions instead of the generic
 * "Launch failed" error box.
 */
public final class SteamRestartRequiredException extends IOException {

    private final String summary;

    public SteamRestartRequiredException(String summary, String detail) {
        super(summary + " " + detail);
        this.summary = summary;
    }

    public String summary() {
        return summary;
    }
}
