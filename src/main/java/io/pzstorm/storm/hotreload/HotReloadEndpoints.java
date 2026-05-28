package io.pzstorm.storm.hotreload;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnRenderTickEvent;
import io.pzstorm.storm.http.HttpEndpoint;
import io.pzstorm.storm.http.HttpRequestEvent;
import java.io.IOException;
import java.util.function.Supplier;
import zombie.network.GameServer;

/**
 * Developer hot-reload endpoints, registered only when {@code -Dstorm.hotreload=true} (and the HTTP
 * server itself is enabled via {@code -Dstorm.http.port}). See {@code
 * io.pzstorm.storm.core.StormBootstrap#startHttpServerIfConfigured}.
 *
 * <ul>
 *   <li>{@code POST /reload} — runs the Lua source in the request body via {@link LuaHotReload}.
 *   <li>{@code GET /eval} — loads and runs a compiled {@code EvalScript} via {@link
 *       JavaEvalRunner}.
 * </ul>
 *
 * <p>On the dedicated server ({@link GameServer#server}) the work runs directly on the HTTP
 * dispatcher thread, since there is no main-thread tick to drain. On the client the work is handed
 * to {@code MainThread} via {@link MainThreadQueue} and pumped by {@link #drainMainThreadQueue} so
 * it never touches Lua/GL state off-thread.
 */
public final class HotReloadEndpoints {

    public static final String ENABLED_PROPERTY = "storm.hotreload";

    private HotReloadEndpoints() {}

    @HttpEndpoint(path = "/reload", method = "POST")
    public static void reload(HttpRequestEvent event) throws IOException {
        String luaSource = event.getRequestBodyAsString();
        if (luaSource == null || luaSource.isBlank()) {
            event.send(400, "missing Lua source in request body");
            return;
        }
        event.send(200, runMaybeOnMain(() -> LuaHotReload.run(luaSource)));
    }

    @HttpEndpoint(path = "/eval")
    public static void eval(HttpRequestEvent event) throws IOException {
        event.send(200, runMaybeOnMain(JavaEvalRunner::run));
    }

    @SubscribeEvent
    public static void drainMainThreadQueue(OnRenderTickEvent event) {
        MainThreadQueue.drain();
    }

    private static String runMaybeOnMain(Supplier<String> work) {
        if (GameServer.server) {
            return work.get();
        }
        return MainThreadQueue.runOnMain(work);
    }
}
