package io.pzstorm.storm.bullet.trace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A {@link ByteBuffer} argument as the native sees it. {@code Bullet.ToBullet} reads the direct
 * buffer from its base address (GetDirectBufferAddress) regardless of position, and stops at the
 * 0xFF terminators, so the content from index 0 up to {@code limit} is recorded — after {@code
 * cmdBuf.clear()} that is the whole 4096-byte buffer including stale bytes of earlier commands.
 */
public record BufferSnapshot(
        boolean direct,
        boolean littleEndian,
        int position,
        int limit,
        int capacity,
        byte[] content) {

    public static BufferSnapshot of(ByteBuffer bb) {
        byte[] content = new byte[bb.limit()];
        bb.duplicate().position(0).get(content, 0, content.length);
        return new BufferSnapshot(
                bb.isDirect(),
                bb.order() == ByteOrder.LITTLE_ENDIAN,
                bb.position(),
                bb.limit(),
                bb.capacity(),
                content);
    }

    /** A fresh buffer with the same kind, order, capacity, content, limit and position. */
    public ByteBuffer materialize() {
        ByteBuffer bb =
                direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        bb.order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        bb.put(content);
        bb.limit(limit);
        bb.position(position);
        return bb;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BufferSnapshot b
                && b.direct == direct
                && b.littleEndian == littleEndian
                && b.position == position
                && b.limit == limit
                && b.capacity == capacity
                && Arrays.equals(b.content, content);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(content) * 31 + position * 7 + limit;
    }

    @Override
    public String toString() {
        return "ByteBuffer[pos="
                + position
                + " lim="
                + limit
                + " cap="
                + capacity
                + (direct ? " direct" : "")
                + (littleEndian ? " LE" : " BE")
                + " crc="
                + Integer.toHexString(Arrays.hashCode(content))
                + "]";
    }
}
