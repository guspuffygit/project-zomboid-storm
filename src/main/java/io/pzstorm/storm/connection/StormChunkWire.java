package io.pzstorm.storm.connection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Binary response framing for the game-port chunk transfer ({@code POST /storm/game/chunks}),
 * shared by the server endpoint (writer) and the client fetcher (reader).
 *
 * <p>Per entry the server answers one of three verdicts, mirroring what the vanilla UDP path can
 * express: {@link #KIND_DATA} carries the zlib-deflated chunk bytes (vanilla {@code SentChunk}),
 * {@link #KIND_NOT_REQUIRED} carries the {@code sameOnServer} flag (vanilla {@code
 * NotRequiredInZip}), and {@link #KIND_RETRY} tells the client to ask again shortly — the vanilla
 * server re-queues a chunk that is neither in {@code ServerMap} nor on disk for up to 3 attempts
 * across ticks, because the cell may be mid-load.
 */
public final class StormChunkWire {

    public static final byte KIND_NOT_REQUIRED = 0;
    public static final byte KIND_DATA = 1;
    public static final byte KIND_RETRY = 2;

    private StormChunkWire() {}

    public record Entry(int requestNumber, byte kind, boolean sameOnServer, @Nullable byte[] data) {

        public static Entry data(int requestNumber, byte[] zip) {
            return new Entry(requestNumber, KIND_DATA, false, zip);
        }

        public static Entry notRequired(int requestNumber, boolean sameOnServer) {
            return new Entry(requestNumber, KIND_NOT_REQUIRED, sameOnServer, null);
        }

        public static Entry retry(int requestNumber) {
            return new Entry(requestNumber, KIND_RETRY, false, null);
        }
    }

    public static byte[] write(List<Entry> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(entries.size());
        for (Entry entry : entries) {
            out.writeInt(entry.requestNumber());
            out.writeByte(entry.kind());
            switch (entry.kind()) {
                case KIND_NOT_REQUIRED -> out.writeBoolean(entry.sameOnServer());
                case KIND_DATA -> {
                    byte[] data = entry.data();
                    if (data == null) {
                        throw new IOException("data entry without payload");
                    }
                    out.writeInt(data.length);
                    out.write(data);
                }
                case KIND_RETRY -> {}
                default -> throw new IOException("unknown entry kind " + entry.kind());
            }
        }
        return buffer.toByteArray();
    }

    public static List<Entry> read(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("negative entry count " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int requestNumber = in.readInt();
            byte kind = in.readByte();
            switch (kind) {
                case KIND_NOT_REQUIRED ->
                        entries.add(Entry.notRequired(requestNumber, in.readBoolean()));
                case KIND_DATA -> {
                    int length = in.readInt();
                    if (length < 0 || length > body.length) {
                        throw new IOException("bad data length " + length);
                    }
                    byte[] data = new byte[length];
                    in.readFully(data);
                    entries.add(Entry.data(requestNumber, data));
                }
                case KIND_RETRY -> entries.add(Entry.retry(requestNumber));
                default -> throw new IOException("unknown entry kind " + kind);
            }
        }
        return entries;
    }
}
