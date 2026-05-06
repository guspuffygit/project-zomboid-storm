package io.pzstorm.storm.metrics;

import io.pzstorm.storm.logging.StormLogger;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkSaveDiffLogger {

    private static final int MAX_RANGES_PER_CHUNK = 8;
    private static final int RANGE_HEX_BYTES = 32;
    private static final int CONTEXT_BYTES = 4;
    private static final int RUN_GAP = 4;
    private static final long MAX_LOGS_PER_WINDOW = 30L;
    private static final long WINDOW_MS = 60_000L;

    private static final AtomicLong logsInWindow = new AtomicLong();
    private static final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    private ChunkSaveDiffLogger() {}

    public static void log(int wx, int wy, byte[] prev, ByteBuffer fresh) {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start > WINDOW_MS && windowStart.compareAndSet(start, now)) {
            logsInWindow.set(0L);
        }
        if (logsInWindow.incrementAndGet() > MAX_LOGS_PER_WINDOW) {
            return;
        }

        int newLen = fresh.position();
        if (prev == null) {
            StormLogger.LOGGER.warn(
                    "ChunkSaveDiff ch=({},{}) prevBytes=null (likely post-unload reload)"
                            + " newLen={}",
                    wx,
                    wy,
                    newLen);
            return;
        }

        byte[] newBytes = new byte[newLen];
        if (fresh.hasArray()) {
            System.arraycopy(fresh.array(), 0, newBytes, 0, newLen);
        } else {
            int savedPos = fresh.position();
            fresh.position(0);
            fresh.get(newBytes, 0, newLen);
            fresh.position(savedPos);
        }

        int common = Math.min(prev.length, newBytes.length);
        StringBuilder sb = new StringBuilder(512);
        sb.append("ChunkSaveDiff ch=(")
                .append(wx)
                .append(",")
                .append(wy)
                .append(") prevLen=")
                .append(prev.length)
                .append(" newLen=")
                .append(newBytes.length);

        int ranges = 0;
        int i = 0;
        while (i < common && ranges < MAX_RANGES_PER_CHUNK) {
            if (prev[i] == newBytes[i]) {
                i++;
                continue;
            }
            int rangeStart = i;
            int j = i;
            while (j < common) {
                if (prev[j] != newBytes[j]) {
                    j++;
                    continue;
                }
                int gap = 0;
                int k = j;
                while (k < common && prev[k] == newBytes[k] && gap < RUN_GAP) {
                    k++;
                    gap++;
                }
                if (gap < RUN_GAP) {
                    j = k;
                } else {
                    break;
                }
            }
            int rangeEnd = j;
            int rangeLen = rangeEnd - rangeStart;
            sb.append("\n  diff @0x")
                    .append(Integer.toHexString(rangeStart))
                    .append(" len=")
                    .append(rangeLen)
                    .append(" ctx[-")
                    .append(CONTEXT_BYTES)
                    .append("]=")
                    .append(hex(prev, Math.max(0, rangeStart - CONTEXT_BYTES), rangeStart))
                    .append(" prev=")
                    .append(hex(prev, rangeStart, Math.min(rangeEnd, rangeStart + RANGE_HEX_BYTES)))
                    .append(" new=")
                    .append(
                            hex(
                                    newBytes,
                                    rangeStart,
                                    Math.min(rangeEnd, rangeStart + RANGE_HEX_BYTES)));
            ranges++;
            i = rangeEnd;
        }

        if (prev.length != newBytes.length) {
            int tailStart = Math.min(prev.length, newBytes.length);
            byte[] longer = prev.length > newBytes.length ? prev : newBytes;
            String which = prev.length > newBytes.length ? "prev" : "new";
            int show = Math.min(longer.length - tailStart, RANGE_HEX_BYTES);
            sb.append("\n  tail-only-in-")
                    .append(which)
                    .append(" @0x")
                    .append(Integer.toHexString(tailStart))
                    .append(" extraLen=")
                    .append(longer.length - tailStart)
                    .append(" bytes=")
                    .append(hex(longer, tailStart, tailStart + show));
        }

        if (ranges == MAX_RANGES_PER_CHUNK && i < common) {
            sb.append("\n  ... more diff ranges suppressed");
        }

        StormLogger.LOGGER.warn(sb.toString());
    }

    private static String hex(byte[] arr, int start, int end) {
        if (arr == null || start >= end || start < 0 || end > arr.length) {
            return "[]";
        }
        StringBuilder out = new StringBuilder(2 + (end - start) * 3);
        out.append('[');
        for (int i = start; i < end; i++) {
            if (i > start) {
                out.append(' ');
            }
            int v = arr[i] & 0xFF;
            if (v < 0x10) {
                out.append('0');
            }
            out.append(Integer.toHexString(v));
        }
        out.append(']');
        return out.toString();
    }
}
