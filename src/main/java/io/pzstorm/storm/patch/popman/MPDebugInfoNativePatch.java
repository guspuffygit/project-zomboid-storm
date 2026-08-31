package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.popman.StormMPDebugInfo;

/** Binds {@code zombie.popman.MPDebugInfo}'s natives to {@link StormMPDebugInfo}. */
public class MPDebugInfoNativePatch extends NativeFacadePatch {

    static final String[] NATIVES = {
        "n_hasData",
        "n_requestData",
        "n_getLoadedCellsCount",
        "n_getLoadedCellsData",
        "n_getLoadedAreasCount",
        "n_getLoadedAreasData",
        "n_getRepopEventCount",
        "n_getRepopEventData",
    };

    public MPDebugInfoNativePatch() {
        super("zombie.popman.MPDebugInfo", StormMPDebugInfo.class, NATIVES);
    }
}
