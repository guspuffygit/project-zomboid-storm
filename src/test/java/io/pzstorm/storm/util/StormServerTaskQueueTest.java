package io.pzstorm.storm.util;

import io.pzstorm.storm.UnitTest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormServerTaskQueueTest implements UnitTest {

    @AfterEach
    void tearDown() {
        StormServerTaskQueue.reset();
    }

    @Test
    void submittedTaskRunsOnDrainingThread() throws Exception {
        Future<Thread> future = StormServerTaskQueue.submit(Thread::currentThread);

        StormServerTaskQueue.drain();

        Assertions.assertSame(Thread.currentThread(), future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void taskExceptionSurfacesThroughFutureNotDrain() {
        Future<Object> future =
                StormServerTaskQueue.submit(
                        () -> {
                            throw new IllegalStateException("boom");
                        });

        // The tick loop must never see task exceptions.
        Assertions.assertDoesNotThrow(StormServerTaskQueue::drain);

        ExecutionException thrown =
                Assertions.assertThrows(
                        ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        Assertions.assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    void undrainedTaskTimesOutInsteadOfWedgingCaller() {
        Future<String> future = StormServerTaskQueue.submit(() -> "never drained");

        Assertions.assertThrows(
                TimeoutException.class, () -> future.get(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void drainRunsTasksInSubmissionOrder() throws Exception {
        StringBuilder order = new StringBuilder();
        StormServerTaskQueue.submit(() -> order.append("a"));
        StormServerTaskQueue.submit(() -> order.append("b"));
        Future<StringBuilder> last = StormServerTaskQueue.submit(() -> order.append("c"));

        StormServerTaskQueue.drain();

        Assertions.assertEquals("abc", last.get(1, TimeUnit.SECONDS).toString());
    }
}
