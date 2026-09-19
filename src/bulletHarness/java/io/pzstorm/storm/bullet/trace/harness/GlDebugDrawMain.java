package io.pzstorm.storm.bullet.trace.harness;

import io.pzstorm.storm.bullet.trace.BackendSession;
import io.pzstorm.storm.bullet.trace.scenario.DebugDrawProbe;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import zombie.core.physics.PhysicsDebugRenderer;

/**
 * Native ground truth for the GL debug renderer: runs {@link DebugDrawProbe} against {@code
 * libPZBullet64.so} through the stub {@link PhysicsDebugRenderer} and writes the upcall log (gzip).
 * Usage: {@code GlDebugDrawMain <libPZBullet64.so> <out.txt.gz> <seed>}; see
 * docs/re-bullet/pz-gl.md for the Docker command.
 */
public final class GlDebugDrawMain {

    private GlDebugDrawMain() {}

    public static void main(String[] a) throws Exception {
        BackendSession s = NativeBackend.open(Path.of(a[0]));
        long[] n = {0};
        try (PrintWriter w =
                new PrintWriter(
                        new OutputStreamWriter(
                                new GZIPOutputStream(Files.newOutputStream(Path.of(a[1]))),
                                StandardCharsets.UTF_8))) {
            Consumer<String> out =
                    l -> {
                        w.println(l);
                        n[0]++;
                    };
            PhysicsDebugRenderer.out = out;
            PhysicsDebugRenderer r = new PhysicsDebugRenderer();
            DebugDrawProbe.run(
                    s,
                    Long.parseLong(a[2]),
                    new DebugDrawProbe.Renderer() {
                        @Override
                        public void n_debugDrawWorld(int x, int y, int mn, int mx) {
                            r.n_debugDrawWorld(x, y, mn, mx);
                        }

                        @Override
                        public void renderVehicle(int id, int x, int y) {
                            r.renderVehicle(id, x, y);
                        }

                        @Override
                        public void renderRagdoll(int id, int x, int y) {
                            r.renderRagdoll(id, x, y);
                        }

                        @Override
                        public void renderBallistics(int id, int x, int y) {
                            r.renderBallistics(id, x, y);
                        }

                        @Override
                        public void renderBallisticsTarget(int id, int x, int y) {
                            r.renderBallisticsTarget(id, x, y);
                        }
                    },
                    out);
        }
        System.out.println("lines " + n[0]);
        System.out.flush();
        // skip library destructors, as NativeHarnessMain does
        Runtime.getRuntime().halt(0);
    }
}
