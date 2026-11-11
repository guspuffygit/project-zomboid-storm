package io.pzstorm.storm.hotreload;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnRenderTickEvent;
import io.pzstorm.storm.event.lua.OnTickEvenPausedEvent;
import io.pzstorm.storm.http.HttpEndpoint;
import io.pzstorm.storm.http.HttpRequestEvent;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import zombie.network.GameServer;

/**
 * Developer hot-reload endpoints, registered only when {@code -Dstorm.hotreload=true} (and the HTTP
 * server itself is enabled via {@code -Dstorm.http.port}). See {@code
 * io.pzstorm.storm.core.StormBootstrap#startHttpServerIfConfigured}.
 *
 * <ul>
 *   <li>{@code POST /reload} — runs the Lua source in the request body via {@link LuaHotReload}.
 *   <li>{@code POST /eval} — defines {@code EvalScript} from the raw {@code .class} bytes in the
 *       request body and runs it via {@link JavaEvalRunner}.
 * </ul>
 *
 * <p>Work never runs on the HTTP dispatcher thread: {@link LuaHotReload} pcalls on the shared
 * {@code LuaManager.thread}, so running it concurrently with the game loop corrupts the Kahlua
 * stack (seen as {@code Index -2 out of bounds} / {@code ERROR: null} under load). On the dedicated
 * server ({@link GameServer#server}) it is handed to {@link StormServerTaskQueue}, drained by
 * {@code ServerTickAdvice} on the server main thread. On the client it is handed to {@code
 * MainThread} via {@link MainThreadQueue} and pumped by {@link #drainMainThreadQueue}.
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

    @HttpEndpoint(path = "/eval", method = "POST")
    public static void eval(HttpRequestEvent event) throws IOException {
        byte[] classBytes = event.getRequestBody();
        if (classBytes.length == 0) {
            event.send(400, "missing EvalScript.class bytes in request body");
            return;
        }
        event.send(200, runMaybeOnMain(() -> JavaEvalRunner.run(classBytes)));
    }

    @SubscribeEvent
    public static void drainMainThreadQueue(OnRenderTickEvent event) {
        MainThreadQueue.drain();
    }

    /**
     * In-game companion to {@link #drainMainThreadQueue(OnRenderTickEvent)}. {@code OnRenderTick}
     * only fires while {@code GameWindow.doRenderEvent} is set, and Lua clears it on entering a
     * world ({@code MainScreen.lua}, {@code ConnectToServer.lua}), so on its own the drain stops
     * the moment the client is somewhere worth inspecting and every {@code /eval} and {@code /lua}
     * call fails on the 30-second main-thread timeout. {@code OnTickEvenPaused} fires every frame
     * from {@code IngameState.updateInternal} and again from {@code GameWindow}'s paused branch.
     */
    @SubscribeEvent
    public static void drainMainThreadQueueInGame(OnTickEvenPausedEvent event) {
        MainThreadQueue.drain();
    }

    private static final long SERVER_MAIN_TIMEOUT_SECONDS = 30;

    static String runMaybeOnMain(Supplier<String> work) {
        if (GameServer.server) {
            return runOnServerMain(work);
        }
        return MainThreadQueue.runOnMain(work);
    }

    private static String runOnServerMain(Supplier<String> work) {
        Future<String> future = StormServerTaskQueue.submit(work::get);
        try {
            return future.get(SERVER_MAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            return "ERROR: timed out after "
                    + SERVER_MAIN_TIMEOUT_SECONDS
                    + "s waiting for the server main thread";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "ERROR: " + cause;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: interrupted waiting for the server main thread";
        }
    }
}
