package io.pzstorm.storm.bullet.trace;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class TraceTestSupport {

    private TraceTestSupport() {}

    /** Records {@code body} against a fresh fake with the given quirks; returns the trace bytes. */
    static byte[] record(Consumer<BackendSession> body, FakeBullet.Quirk... quirks)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TraceWriter w = new TraceWriter(bos, Map.of("source", "test"));
        List<String> errors = new ArrayList<>();
        TraceRecorder r = new TraceRecorder(w, errors::add);
        body.accept(RecordingBackend.wrap(new FakeBullet(quirks).session(), r));
        r.close();
        if (!errors.isEmpty()) {
            throw new IOException("recording errors: " + errors);
        }
        return bos.toByteArray();
    }

    static Trace read(byte[] bytes) throws IOException {
        try (TraceReader r = new TraceReader(new ByteArrayInputStream(bytes))) {
            List<CallNode> roots = new ArrayList<>();
            r.forEachRoot(roots::add);
            return new Trace(r.header(), roots, r.stats());
        }
    }

    static ReplayReport replay(Trace t, Replayer.Options options, FakeBullet.Quirk... quirks) {
        return new Replayer(new FakeBullet(quirks).session(), new ReplayReport("test", 50), options)
                .replay(t.roots());
    }

    static CallNode root(Trace t, String name) {
        return t.roots().stream().filter(c -> c.sig.name.equals(name)).findFirst().orElseThrow();
    }
}
