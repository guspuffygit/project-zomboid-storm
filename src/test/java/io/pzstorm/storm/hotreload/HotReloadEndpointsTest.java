package io.pzstorm.storm.hotreload;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.util.StormServerTaskQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.network.GameServer;

class HotReloadEndpointsTest implements UnitTest {

    private boolean wasServer;

    @BeforeEach
    void setUp() {
        wasServer = GameServer.server;
        GameServer.server = true;
    }

    @AfterEach
    void tearDown() {
        GameServer.server = wasServer;
        StormServerTaskQueue.reset();
    }

    @Test
    void serverWorkRunsOnServerTickThreadNotCaller() throws Exception {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        CompletableFuture<String> response =
                CompletableFuture.supplyAsync(
                        () ->
                                HotReloadEndpoints.runMaybeOnMain(
                                        () -> {
                                            ranOn.set(Thread.currentThread());
                                            return "OK";
                                        }));

        // Simulate ServerTickAdvice pumping the queue from the server main thread.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!response.isDone() && System.nanoTime() < deadline) {
            StormServerTaskQueue.drain();
            Thread.sleep(5);
        }

        Assertions.assertEquals("OK", response.get(1, TimeUnit.SECONDS));
        Assertions.assertSame(Thread.currentThread(), ranOn.get());
    }

    @Test
    void serverWorkExceptionBecomesErrorResponse() throws Exception {
        CompletableFuture<String> response =
                CompletableFuture.supplyAsync(
                        () ->
                                HotReloadEndpoints.runMaybeOnMain(
                                        () -> {
                                            throw new IllegalStateException("boom");
                                        }));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!response.isDone() && System.nanoTime() < deadline) {
            StormServerTaskQueue.drain();
            Thread.sleep(5);
        }

        String body = response.get(1, TimeUnit.SECONDS);
        Assertions.assertTrue(body.startsWith("ERROR:"), body);
        Assertions.assertTrue(body.contains("boom"), body);
    }
}
