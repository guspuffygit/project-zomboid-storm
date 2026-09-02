package io.pzstorm.storm.scripting;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dense index allocators for the registry key classes behind per-character maps; one counter per
 * key class so each {@link io.pzstorm.storm.characters.StormIndexedMap} stays as small as its key
 * space. Called from the key constructors (see the {@code io.pzstorm.storm.advice.registrykey}
 * advice). A registry reset re-registers keys as new instances and so keeps allocating; maps built
 * afterwards simply size to the highest index they see.
 */
public final class StormRegistryKeyIndex {

    private static final AtomicInteger NEXT_STAT = new AtomicInteger();
    private static final AtomicInteger NEXT_MOODLE = new AtomicInteger();
    private static final AtomicInteger NEXT_TRAIT = new AtomicInteger();

    private StormRegistryKeyIndex() {}

    public static int nextStat() {
        return NEXT_STAT.getAndIncrement();
    }

    public static int nextMoodle() {
        return NEXT_MOODLE.getAndIncrement();
    }

    public static int nextTrait() {
        return NEXT_TRAIT.getAndIncrement();
    }
}
