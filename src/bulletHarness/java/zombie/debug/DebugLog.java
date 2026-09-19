package zombie.debug;

import io.pzstorm.storm.bullet.trace.harness.HarnessUpcalls;

/** HARNESS STUB: only the method the physics library calls (PZDebugLog::logInternal). */
public final class DebugLog {

    private DebugLog() {}

    public static void nativeLog(String logType, String logSeverity, String logTxt) {
        HarnessUpcalls.handler().nativeLog(logType, logSeverity, logTxt);
    }
}
