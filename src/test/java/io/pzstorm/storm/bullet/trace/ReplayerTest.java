package io.pzstorm.storm.bullet.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.trace.FakeBullet.Quirk;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplayerTest implements UnitTest {

    private static Trace recorded() throws Exception {
        return TraceTestSupport.read(TraceTestSupport.record(FakeBullet::drive));
    }

    private static Set<Divergence.Kind> kinds(ReplayReport r) {
        Set<Divergence.Kind> k = java.util.EnumSet.noneOf(Divergence.Kind.class);
        r.divergences().forEach(d -> k.add(d.kind()));
        return k;
    }

    @Test
    void sameBehaviourReplaysIdentically() throws Exception {
        Trace t = recorded();
        FakeBullet fake = new FakeBullet();
        ReplayReport r =
                new Replayer(fake.session(), new ReplayReport("t", 50), Replayer.Options.defaults())
                        .replay(t.roots());
        assertTrue(r.identical(), r.render());
        assertEquals(16, r.rootCalls());
        assertEquals(21, r.calls(), "16 roots + 5 nested ToBullet re-issued from upcalls");
        assertEquals(5, fake.toBulletCalls, "nested calls reach the backend");
        assertEquals(8, r.upcalls());
        assertTrue(r.render().contains("IDENTICAL"));
    }

    @Test
    void oneUlpInAnOutputArrayIsAnArgOutDivergence() throws Exception {
        ReplayReport r =
                TraceTestSupport.replay(recorded(), Replayer.Options.defaults(), Quirk.ULP_OFF);
        assertFalse(r.identical());
        assertEquals(Set.of(Divergence.Kind.ARG_OUT), kinds(r));
        assertEquals(5, r.divergenceCount());
        Divergence d = r.first();
        assertEquals("getVehiclePhysics", d.method());
        assertTrue(d.detail().contains("first diff at [0]"), d.detail());
        assertTrue(r.render().contains("FIRST DIVERGENCE"));
    }

    @Test
    void returnValueDivergence() throws Exception {
        ReplayReport r =
                TraceTestSupport.replay(
                        recorded(), Replayer.Options.defaults(), Quirk.OTHER_VERSION);
        assertEquals(Set.of(Divergence.Kind.RETURN), kinds(r));
        assertEquals("getPZBulletVersion", r.first().method());
    }

    @Test
    void exceptionOutcomeIsCompared() throws Exception {
        ReplayReport r =
                TraceTestSupport.replay(recorded(), Replayer.Options.defaults(), Quirk.NO_THROW);
        assertEquals(Set.of(Divergence.Kind.EXCEPTION), kinds(r));
        assertTrue(
                r.first().detail().contains("IllegalStateException: no world"), r.first().detail());
    }

    @Test
    void missingUpcallAndItsNestedCallsAreReported() throws Exception {
        FakeBullet fake = new FakeBullet(Quirk.NO_UPCALL);
        ReplayReport r =
                new Replayer(fake.session(), new ReplayReport("t", 50), Replayer.Options.defaults())
                        .replay(recorded().roots());
        assertTrue(kinds(r).contains(Divergence.Kind.MISSING_UPCALL));
        assertEquals(0, fake.toBulletCalls, "no upcall, so no nested call either");
        // the state then differs, which the readbacks show
        assertTrue(kinds(r).contains(Divergence.Kind.ARG_OUT));
        assertTrue(
                r.first().path().contains("upcall updatePhysicsForLevelIfNeeded"),
                r.first().path());
    }

    @Test
    void extraUpcallIsReportedAndAnsweredByDefault() throws Exception {
        ReplayReport r =
                TraceTestSupport.replay(recorded(), Replayer.Options.defaults(), Quirk.EXTRA_LOG);
        assertTrue(kinds(r).contains(Divergence.Kind.EXTRA_UPCALL), r.render());
        assertEquals("nativeLog", r.first().method());
    }

    @Test
    void looseUpcallsAreSkippedWithoutDivergence() throws Exception {
        Replayer.Options loose =
                Replayer.Options.defaults().withLoose(Set.of(BulletApi.NATIVE_LOG));
        ReplayReport r = TraceTestSupport.replay(recorded(), loose, Quirk.EXTRA_LOG);
        assertTrue(r.identical(), r.render());
    }

    @Test
    void upcallArgumentMismatch() throws Exception {
        ReplayReport r =
                TraceTestSupport.replay(recorded(), Replayer.Options.defaults(), Quirk.OTHER_LEVEL);
        assertEquals(Set.of(Divergence.Kind.UPCALL_ARGS), kinds(r));
        assertEquals(5, r.divergenceCount());
        assertTrue(r.first().detail().startsWith("arg2"), r.first().detail());
    }

    @Test
    void stopAtFirstStopsAfterTheFirstDivergingRoot() throws Exception {
        ReplayReport r =
                TraceTestSupport.replay(
                        recorded(),
                        Replayer.Options.defaults().withStopAtFirst(true),
                        Quirk.ULP_OFF);
        assertEquals(1, r.divergenceCount());
    }

    @Test
    void replayCanItselfBeRecordedAndDiffedAgainstTheOriginal() throws Exception {
        Trace original = recorded();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TraceRecorder rec = new TraceRecorder(new TraceWriter(bos, Map.of()), e -> {});
        BackendSession session = RecordingBackend.wrap(new FakeBullet().session(), rec);
        ReplayReport r =
                new Replayer(session, new ReplayReport("t", 50), Replayer.Options.defaults())
                        .replay(original.roots());
        rec.close();
        assertTrue(r.identical(), r.render());
        Trace replayed = TraceTestSupport.read(bos.toByteArray());
        ReplayReport diff = TraceDiff.diff(original.roots(), replayed.roots(), 50);
        assertTrue(diff.identical(), diff.render());
    }

    @Test
    void traceDiffFindsOutputDifferencesBetweenTwoRecordings() throws Exception {
        Trace a = recorded();
        Trace b = TraceTestSupport.read(TraceTestSupport.record(FakeBullet::drive, Quirk.ULP_OFF));
        ReplayReport d = TraceDiff.diff(a.roots(), b.roots(), 50);
        // drive() reuses one float[] for the readbacks, so each off-by-one-ULP output is fed back
        // as the next call's input: 5 output differences, then 4 input differences
        assertEquals(Set.of(Divergence.Kind.ARG_OUT, Divergence.Kind.CALL_MISMATCH), kinds(d));
        assertEquals(Divergence.Kind.ARG_OUT, d.first().kind());
        assertEquals(9, d.divergenceCount());
    }

    @Test
    void traceDiffFindsStructuralDifferences() throws Exception {
        Trace a = recorded();
        Trace b =
                TraceTestSupport.read(TraceTestSupport.record(FakeBullet::drive, Quirk.NO_UPCALL));
        ReplayReport d = TraceDiff.diff(a.roots(), b.roots(), 50);
        assertTrue(kinds(d).contains(Divergence.Kind.STRUCTURE), d.render());
        List<CallNode> shorter = new ArrayList<>(a.roots().subList(0, a.roots().size() - 1));
        assertFalse(TraceDiff.diff(a.roots(), shorter, 50).identical());
    }
}
