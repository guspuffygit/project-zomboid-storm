package io.pzstorm.storm.bullet.trace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceRoundTripTest implements UnitTest {

    @Test
    void recordsEveryValueKindAndOutcome() throws Exception {
        Trace t = TraceTestSupport.read(TraceTestSupport.record(FakeBullet::drive));
        assertTrue(t.stats().cleanEnd());
        assertFalse(t.stats().truncated());
        assertNull(t.stats().problem());
        assertEquals("test", t.header().metadata().get("source"));

        assertEquals("1.0.0.28", TraceTestSupport.root(t, "getPZBulletVersion").ret);

        CallNode init = TraceTestSupport.root(t, "initPZBullet");
        assertEquals(3, init.upcalls.size());
        assertEquals("fake init é", init.upcalls.get(0).args[2]);
        assertEquals(SkeletonBoneTable.getBoneName(3), init.upcalls.get(1).ret);
        assertEquals(SkeletonBoneTable.getBoneOrdinal("Bip01_Head"), init.upcalls.get(2).ret);

        CallNode isInit = TraceTestSupport.root(t, "isWorldInit");
        assertTrue(isInit.threw());
        assertEquals("java.lang.IllegalStateException", isInit.thrownClass);
        assertEquals("no world", isInit.thrownMessage);

        CallNode def = TraceTestSupport.root(t, "defineVehicleScript");
        float[] ff = (float[]) def.args[1];
        assertEquals(Float.floatToRawIntBits(-0f), Float.floatToRawIntBits(ff[1]));
        assertTrue(Float.isNaN(ff[2]));

        CallNode rag = TraceTestSupport.root(t, "initializeRagdollSkeleton");
        assertArrayEquals(new int[] {1, 2, 3}, (int[]) rag.args[1]);
        assertArrayEquals(new int[] {2, 3, 4}, (int[]) rag.argsAfter[1]);

        List<CallNode> steps =
                t.roots().stream().filter(c -> c.sig.name.equals("stepSimulation")).toList();
        assertEquals(5, steps.size());
        UpcallNode up = steps.get(0).upcalls.get(0);
        assertEquals(BulletApi.UPDATE_PHYSICS_FOR_LEVEL_IF_NEEDED, up.sig);
        assertEquals(Boolean.TRUE, up.ret);
        assertEquals(1, up.calls.size(), "the nested ToBullet belongs to the upcall");
        CallNode nested = up.calls.get(0);
        assertEquals("ToBullet", nested.sig.name);
        BufferSnapshot bb = (BufferSnapshot) nested.args[0];
        assertTrue(bb.direct());
        assertEquals(64, bb.content().length, "content 0..limit");
        assertEquals(5, bb.content()[0]);
        // roots arrive in begin order; nested calls are not roots
        assertTrue(t.roots().stream().noneMatch(c -> c.sig.name.equals("ToBullet")));
        for (int i = 1; i < t.roots().size(); i++) {
            assertTrue(t.roots().get(i - 1).seq < t.roots().get(i).seq);
        }

        CallNode last = t.roots().get(t.roots().size() - 1);
        assertEquals("getVehiclePhysics", last.sig.name);
        // defineVehicleScript 14 + 3, five steps of 0.01, five nested ToBullet of 5
        assertEquals(17f + 0.05f + 25f, ((float[]) last.argsAfter[1])[0], 1e-4f);
        assertEquals(4f, ((float[]) last.argsAfter[1])[1]);
        assertEquals(1, last.ret);
    }

    @Test
    void byteBufferSnapshotKeepsOrderPositionAndContent() throws Exception {
        ByteBuffer heap = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        heap.put(new byte[] {9, 8, 7});
        heap.limit(6);
        byte[] bytes =
                TraceTestSupport.record(
                        s -> {
                            s.installUpcalls(FakeBullet.game(s.bullet()));
                            s.bullet().ToBullet(heap);
                        });
        CallNode c = TraceTestSupport.read(bytes).roots().get(0);
        BufferSnapshot b = (BufferSnapshot) c.args[0];
        assertFalse(b.direct());
        assertTrue(b.littleEndian());
        assertEquals(3, b.position());
        assertEquals(6, b.limit());
        assertEquals(10, b.capacity());
        assertArrayEquals(new byte[] {9, 8, 7, 0, 0, 0}, b.content());
        ByteBuffer back = (ByteBuffer) Values.materialize(ValueKind.BYTE_BUFFER, b);
        assertEquals(3, back.position());
        assertEquals(6, back.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, back.order());
    }

    @Test
    void gzipFilesRoundTrip(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("t.pzbt.gz");
        TraceWriter w = TraceWriter.open(p, Map.of("k", "v"));
        TraceRecorder r = new TraceRecorder(w, e -> {});
        FakeBullet.drive(RecordingBackend.wrap(new FakeBullet().session(), r));
        r.close();
        byte[] raw = Files.readAllBytes(p);
        assertEquals((byte) 0x1f, raw[0]);
        assertEquals((byte) 0x8b, raw[1]);
        Trace t = TraceReader.readAll(p);
        assertEquals("v", t.header().metadata().get("k"));
        assertTrue(t.stats().cleanEnd());
        assertEquals(16, t.roots().size());
    }

    @Test
    void truncatedTraceYieldsCompletedCallsOnly() throws Exception {
        byte[] full = TraceTestSupport.record(FakeBullet::drive);
        int fullRoots = TraceTestSupport.read(full).roots().size();
        // a cut inside the header is not a trace at all; start past it
        int headerEnd = TraceTestSupport.record(s -> {}).length;
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> TraceTestSupport.read(java.util.Arrays.copyOf(full, headerEnd / 2)));
        int lastSeen = Integer.MAX_VALUE;
        int cuts = 0;
        for (int cut = full.length - 1; cut >= headerEnd; cut -= 7) {
            cuts++;
            byte[] part = java.util.Arrays.copyOf(full, cut);
            Trace t = TraceTestSupport.read(part);
            assertFalse(t.stats().cleanEnd());
            assertTrue(t.stats().truncated());
            assertTrue(t.roots().size() <= fullRoots);
            assertTrue(t.roots().size() <= lastSeen, "shorter prefix, no more roots");
            lastSeen = t.roots().size();
            for (CallNode c : t.roots()) {
                assertTrue(c.completed);
            }
        }
        assertTrue(cuts > 20, "cuts: " + cuts);
        assertEquals(0, lastSeen, "a cut right after the header has no complete call");
    }

    @Test
    void concurrentThreadsKeepPerThreadTreesAndGlobalOrder() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        TraceRecorder r = new TraceRecorder(new TraceWriter(bos, Map.of()), e -> {});
        int threads = 6;
        int perThread = 300;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int k = 0; k < threads; k++) {
            final int id = k;
            Thread th =
                    new Thread(
                            () -> {
                                BackendSession s =
                                        RecordingBackend.wrap(new FakeBullet().session(), r);
                                s.installUpcalls(FakeBullet.game(s.bullet()));
                                float[] ff = new float[4];
                                try {
                                    start.await();
                                } catch (InterruptedException e) {
                                    return;
                                }
                                for (int i = 0; i < perThread; i++) {
                                    s.bullet().stepSimulation(0.01f, 0, 0f);
                                    s.bullet().getVehiclePhysics(id * 10000 + i, ff);
                                }
                            },
                            "rec-" + k);
            ts.add(th);
            th.start();
        }
        start.countDown();
        for (Thread th : ts) {
            th.join();
        }
        r.close();
        try (TraceReader reader = new TraceReader(new ByteArrayInputStream(bos.toByteArray()))) {
            List<CallNode> roots = new ArrayList<>();
            reader.forEachRoot(roots::add);
            TraceStats st = reader.stats();
            assertNull(st.problem());
            assertTrue(st.cleanEnd());
            assertEquals(threads * perThread * 2, roots.size());
            assertEquals(threads, st.threads().size());
            Map<Long, Integer> nextIndex = new HashMap<>();
            for (int i = 0; i < roots.size(); i++) {
                CallNode c = roots.get(i);
                if (i > 0) {
                    assertTrue(roots.get(i - 1).seq < c.seq, "roots in global begin order");
                }
                if (c.sig.name.equals("stepSimulation")) {
                    assertEquals(1, c.upcalls.size());
                    CallNode nested = c.upcalls.get(0).calls.get(0);
                    assertEquals(c.threadId, nested.threadId, "nested call on its own thread");
                    assertTrue(c.seq < nested.seq && nested.seq < c.endSeq);
                } else {
                    int vid = (Integer) c.args[0];
                    int expected = nextIndex.merge(c.threadId, 1, Integer::sum) - 1;
                    assertEquals(expected, vid % 10000, "per-thread call order preserved");
                }
            }
        }
    }

    @Test
    void writerFailureStopsRecordingButCallsContinue() throws Exception {
        java.io.OutputStream failing =
                new java.io.OutputStream() {
                    int n;

                    @Override
                    public void write(int b) throws java.io.IOException {
                        if (++n > 20000) {
                            throw new java.io.IOException("disk full");
                        }
                    }
                };
        List<String> errors = new ArrayList<>();
        TraceRecorder r = new TraceRecorder(new TraceWriter(failing, Map.of()), errors::add);
        FakeBullet fake = new FakeBullet();
        BackendSession s = RecordingBackend.wrap(fake.session(), r);
        s.installUpcalls(FakeBullet.game(s.bullet()));
        float[] ff = new float[8192];
        for (int i = 0; i < 50; i++) {
            s.bullet().getVehiclePhysics(i, ff);
            s.bullet().stepSimulation(0.5f, 0, 0f);
        }
        assertFalse(r.isEnabled());
        assertEquals(1, errors.size(), "reported once: " + errors);
        // 50 steps of 0.5, each with a nested ToBullet of 5
        assertEquals(275f, fake.state, 1e-4f, "every call still reached the library");
        assertEquals(50, fake.toBulletCalls);
        assertNotNull(errors.get(0));
    }
}
