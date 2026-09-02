package io.pzstorm.storm.scripting;

import java.util.concurrent.atomic.AtomicInteger;

/** Dense index allocator for {@code ItemTag} instances; called from the tag constructor. */
public final class StormItemTagIndex {

    private static final AtomicInteger NEXT = new AtomicInteger();

    private StormItemTagIndex() {}

    public static int next() {
        return NEXT.getAndIncrement();
    }
}
