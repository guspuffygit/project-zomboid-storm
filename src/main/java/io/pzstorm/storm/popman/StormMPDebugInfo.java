package io.pzstorm.storm.popman;

import java.nio.ByteBuffer;

/**
 * Java replacement for the eight {@code zombie.popman.MPDebugInfo} natives — the server side of the
 * multiplayer population overlay. Record layouts are the ones {@code serverUpdate} reads back:
 * 12-byte cells, 9-byte areas, 8-byte repopulation events, written from offset 0 of the caller's
 * 1024-byte buffer.
 */
public final class StormMPDebugInfo {

    private StormMPDebugInfo() {}

    /** {@code false} asks for a loaded-cells snapshot, {@code true} for repopulation events. */
    public static boolean n_hasData(boolean repopEvents) {
        PopManCore core = StormPopMan.core();
        return repopEvents ? core.hasRepopEvents() : core.hasMpDebugData();
    }

    public static void n_requestData() {
        StormPopMan.core().requestMpDebugData();
    }

    public static int n_getLoadedCellsCount() {
        return StormPopMan.core().getLoadedCellsCount();
    }

    public static int n_getLoadedCellsData(int offset, ByteBuffer buf) {
        return StormPopMan.core().getLoadedCellsData(offset, buf);
    }

    public static int n_getLoadedAreasCount() {
        return StormPopMan.core().getLoadedAreasCount();
    }

    public static int n_getLoadedAreasData(int offset, ByteBuffer buf) {
        return StormPopMan.core().getLoadedAreasData(offset, buf);
    }

    public static int n_getRepopEventCount() {
        return StormPopMan.core().getRepopEventCount();
    }

    public static int n_getRepopEventData(int offset, ByteBuffer buf) {
        return StormPopMan.core().getRepopEventData(offset, buf);
    }
}
