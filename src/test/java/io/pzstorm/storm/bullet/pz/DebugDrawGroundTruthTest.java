package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.BulletUpcalls;
import io.pzstorm.storm.bullet.StormBullet;
import io.pzstorm.storm.bullet.StormPhysicsDebugRenderer;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.trace.BackendSession;
import io.pzstorm.storm.bullet.trace.JavaBackend;
import io.pzstorm.storm.bullet.trace.scenario.DebugDrawProbe;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DebugDrawProbe} on the Java port vs. the same probe on the native {@code libPZBullet64.so}
 * ({@code pz-gl-debugdraw.txt.gz}, recorded by {@code GlDebugDrawMain} in Docker, seed 1): every
 * draw upcall, bit for bit, in order.
 */
class DebugDrawGroundTruthTest implements UnitTest {

    private static final String RESOURCE = "pz-gl-debugdraw.txt.gz";

    /**
     * The native probe ran in a fresh process, whose libc {@code rand()} starts as {@code
     * srand(1)}.
     */
    @BeforeEach
    void freshRand() {
        btGlobals.resetAll();
        GlibcRand.srand(1);
    }

    @AfterEach
    void tearDown() {
        PhysicsDebugRenderer.resetForTests();
        BulletUpcalls.target = BulletUpcalls.GameTarget.INSTANCE;
    }

    private static List<String> expected() throws Exception {
        InputStream in = DebugDrawGroundTruthTest.class.getResourceAsStream(RESOURCE);
        assertNotNull(in, RESOURCE);
        List<String> out = new ArrayList<>();
        try (BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                out.add(l);
            }
        }
        return out;
    }

    /** The stub game class of the Docker driver: logs the upcall, every method resolvable. */
    private static final class ProbeSink implements PhysicsDebugRenderer.DrawSink {
        final Consumer<String> out;

        ProbeSink(Consumer<String> out) {
            this.out = out;
        }

        @Override
        public boolean getMethodID(Class<?> clazz, String name, String sig) {
            return true;
        }

        @Override
        public void drawLine(
                Object o,
                float a,
                float b,
                float c,
                float d,
                float e,
                float f,
                float g,
                float h,
                float i,
                float j,
                float k,
                float l) {
            out.accept(DebugDrawProbe.line("drawLine", a, b, c, d, e, f, g, h, i, j, k, l));
        }

        @Override
        public void drawSphere(
                Object o, float a, float b, float c, float d, float e, float f, float g) {
            out.accept(DebugDrawProbe.line("drawSphere", a, b, c, d, e, f, g));
        }

        @Override
        public void drawTriangle(
                Object o,
                float a,
                float b,
                float c,
                float d,
                float e,
                float f,
                float g,
                float h,
                float i,
                float j,
                float k,
                float l,
                float m) {
            out.accept(DebugDrawProbe.line("drawTriangle", a, b, c, d, e, f, g, h, i, j, k, l, m));
        }

        @Override
        public void drawContactPoint(
                Object o,
                float a,
                float b,
                float c,
                float d,
                float e,
                float f,
                float g,
                int h,
                float i,
                float j,
                float k) {
            out.accept(DebugDrawProbe.line("drawContactPoint", a, b, c, d, e, f, g, h, i, j, k));
        }

        @Override
        public void drawCapsule(
                Object o,
                float a,
                float b,
                int c,
                float d,
                float e,
                float f,
                float g,
                float h,
                float i,
                float j,
                float k,
                float l,
                float m,
                float n) {
            out.accept(
                    DebugDrawProbe.line("drawCapsule", a, b, c, d, e, f, g, h, i, j, k, l, m, n));
        }
    }

    @Test
    void matchesNativeLibrary() throws Exception {
        List<String> want = expected();
        List<String> got = new ArrayList<>();
        PhysicsDebugRenderer.resetForTests();
        PhysicsDebugRenderer.sink = new ProbeSink(got::add);
        // direct references so standalone javac (-sourcepath) compiles the reflective targets
        assertNotNull(StormBullet.class);
        assertNotNull(BulletUpcalls.Target.class);
        BackendSession session = JavaBackend.open(getClass().getClassLoader());
        Object self = new Object();
        DebugDrawProbe.run(
                session,
                1,
                new DebugDrawProbe.Renderer() {
                    @Override
                    public void n_debugDrawWorld(int x, int y, int mn, int mx) {
                        StormPhysicsDebugRenderer.n_debugDrawWorld(self, x, y, mn, mx);
                    }

                    @Override
                    public void renderVehicle(int id, int x, int y) {
                        StormPhysicsDebugRenderer.renderVehicle(self, id, x, y);
                    }

                    @Override
                    public void renderRagdoll(int id, int x, int y) {
                        StormPhysicsDebugRenderer.renderRagdoll(self, id, x, y);
                    }

                    @Override
                    public void renderBallistics(int id, int x, int y) {
                        StormPhysicsDebugRenderer.renderBallistics(self, id, x, y);
                    }

                    @Override
                    public void renderBallisticsTarget(int id, int x, int y) {
                        StormPhysicsDebugRenderer.renderBallisticsTarget(self, id, x, y);
                    }
                },
                got::add);
        // PZGL_DUMP=<file>: keep the Java log for a diff against the native capture
        String dump = System.getenv("PZGL_DUMP");
        if (dump != null) {
            java.nio.file.Files.write(java.nio.file.Path.of(dump), got, StandardCharsets.UTF_8);
        }
        int n = Math.min(want.size(), got.size());
        for (int i = 0; i < n; i++) {
            if (!want.get(i).equals(got.get(i))) {
                String section = "";
                for (int j = i; j >= 0; j--) {
                    if (want.get(j).startsWith(">")) {
                        section = want.get(j);
                        break;
                    }
                }
                fail(
                        "first divergence at line "
                                + (i + 1)
                                + " in ["
                                + section
                                + "]\n native: "
                                + want.get(i)
                                + "\n java:   "
                                + got.get(i));
            }
        }
        assertEquals(want.size(), got.size(), "upcall count");
    }
}
