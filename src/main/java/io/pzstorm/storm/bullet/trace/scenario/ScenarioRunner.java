package io.pzstorm.storm.bullet.trace.scenario;

import io.pzstorm.storm.bullet.trace.BackendSession;
import io.pzstorm.storm.bullet.trace.RecordingBackend;
import io.pzstorm.storm.bullet.trace.TraceRecorder;
import io.pzstorm.storm.bullet.trace.TraceWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Runs a scenario live against a backend, recording every call and upcall to a trace file. */
public final class ScenarioRunner {

    private ScenarioRunner() {}

    /**
     * @return number of trace events written
     */
    public static long run(
            Scenario scenario, long seed, BackendSession session, Path out, Consumer<String> log)
            throws Exception {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("source", "scenario");
        meta.put("scenario", scenario.name());
        meta.put("seed", Long.toString(seed));
        meta.put("backend", session.name());
        meta.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        meta.put(
                "java",
                System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        TraceWriter writer = TraceWriter.open(out, meta);
        String[] error = new String[1];
        TraceRecorder recorder =
                new TraceRecorder(
                        writer,
                        e -> {
                            error[0] = e;
                            log.accept(e);
                        });
        BackendSession recorded = RecordingBackend.wrap(session, recorder);
        ScenarioContext ctx = new ScenarioContext(recorded.bullet(), seed, recorder);
        recorded.installUpcalls(ctx.gameUpcalls());
        try {
            scenario.run(ctx);
        } finally {
            recorder.close();
        }
        if (error[0] != null) {
            throw new IllegalStateException("recording failed: " + error[0]);
        }
        return writer.eventCount();
    }
}
