package io.pzstorm.storm.bullet.trace.harness;

import io.pzstorm.storm.bullet.trace.BackendSession;
import io.pzstorm.storm.bullet.trace.StaticClassBackend;
import java.nio.file.Files;
import java.nio.file.Path;
import zombie.core.physics.Bullet;

/** The real library behind the stub {@code zombie.core.physics.Bullet}. One per JVM. */
public final class NativeBackend {

    private static BackendSession session;

    private NativeBackend() {}

    public static synchronized BackendSession open(Path library) {
        if (session != null) {
            return session;
        }
        Path lib = library.toAbsolutePath();
        if (!Files.isRegularFile(lib)) {
            throw new IllegalArgumentException("no such library " + lib);
        }
        // natives bind to the loader of the class that loads the library: same as Bullet's
        System.load(lib.toString());
        session =
                new BackendSession(
                        "native:" + lib.getFileName(),
                        StaticClassBackend.of(Bullet.class, false),
                        HarnessUpcalls::install);
        return session;
    }
}
