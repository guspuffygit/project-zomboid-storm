package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ReattachCellAnimalsMemoTest {

    private static Function<Object, List<?>> countingLoader(AtomicInteger loads) {
        return cell -> {
            loads.incrementAndGet();
            return new ArrayList<>();
        };
    }

    @Test
    void sameFrameAndCellSharesOneLoad() {
        Object cell = new Object();
        AtomicInteger loads = new AtomicInteger();
        Function<Object, List<?>> loader = countingLoader(loads);

        List<?> first = ReattachCellAnimalsMemo.get(7, cell, loader);
        List<?> second = ReattachCellAnimalsMemo.get(7, cell, loader);

        assertSame(first, second);
        assertEquals(1, loads.get());
    }

    @Test
    void newFrameReloads() {
        Object cell = new Object();
        AtomicInteger loads = new AtomicInteger();
        Function<Object, List<?>> loader = countingLoader(loads);

        List<?> first = ReattachCellAnimalsMemo.get(100, cell, loader);
        List<?> second = ReattachCellAnimalsMemo.get(101, cell, loader);

        assertNotSame(first, second);
        assertEquals(2, loads.get());
    }

    @Test
    void differentCellInSameFrameReloads() {
        AtomicInteger loads = new AtomicInteger();
        Function<Object, List<?>> loader = countingLoader(loads);

        List<?> first = ReattachCellAnimalsMemo.get(200, new Object(), loader);
        List<?> second = ReattachCellAnimalsMemo.get(200, new Object(), loader);

        assertNotSame(first, second);
        assertEquals(2, loads.get());
    }
}
