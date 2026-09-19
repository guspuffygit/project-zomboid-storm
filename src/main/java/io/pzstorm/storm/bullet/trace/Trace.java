package io.pzstorm.storm.bullet.trace;

import java.util.List;

/** A fully loaded trace. */
public record Trace(TraceHeader header, List<CallNode> roots, TraceStats stats) {

    public int callCount() {
        int n = 0;
        for (CallNode c : roots) {
            n += c.size();
        }
        return n;
    }
}
