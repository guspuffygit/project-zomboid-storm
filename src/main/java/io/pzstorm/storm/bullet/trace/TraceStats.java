package io.pzstorm.storm.bullet.trace;

import java.util.List;
import java.util.Map;

/**
 * What the reader noticed about a trace.
 *
 * @param cleanEnd the writer closed the file (END event present)
 * @param truncated the file ended without END (process died; last event may be cut)
 * @param unfinishedRoots top-level calls that began but never ended
 * @param overlappingRoots top-level calls that began while another thread's call was still open;
 *     replay serialises them in begin order, which may not be what the native saw
 * @param problem first structural inconsistency, or null
 */
public record TraceStats(
        long events,
        boolean cleanEnd,
        boolean truncated,
        int unfinishedRoots,
        int overlappingRoots,
        Map<Long, String> threads,
        List<String> notes,
        String problem) {}
