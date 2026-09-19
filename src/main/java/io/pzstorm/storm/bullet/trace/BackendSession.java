package io.pzstorm.storm.bullet.trace;

import java.util.function.Consumer;

/**
 * A live implementation of the library: the {@link BulletBackend} to call and a way to route the
 * upcalls it makes. Only one session per JVM can use the native library (its world is global).
 */
public record BackendSession(
        String name, BulletBackend bullet, Consumer<UpcallHandler> upcallInstaller) {

    public void installUpcalls(UpcallHandler handler) {
        upcallInstaller.accept(handler);
    }
}
