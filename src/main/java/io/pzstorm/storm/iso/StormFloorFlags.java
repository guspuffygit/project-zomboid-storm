package io.pzstorm.storm.iso;

import io.pzstorm.storm.entity.StormSpriteFloorFlagsHolder;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;

/**
 * Replaces the bodies of {@code IsoGridSquare.hasNaturalFloor()}, {@code hasSand()} and {@code
 * hasDirt()}. Vanilla calls {@code getFloor()} — a linear scan of the square's objects for the
 * {@code solidfloor} property — up to four times per call, then classifies the floor sprite's name
 * with {@code startsWith}/{@code contains}/{@code equals} chains. {@code
 * IsoGameCharacter.calculateBaseSpeed} asks every tick for every character; 2.5% of player update
 * on ATF prod (scan #10, 2026-09-02).
 *
 * <p>This does one {@code getFloor()} and caches the classification on the sprite ({@link
 * StormSpriteFloorFlagsHolder}), keyed on the identity of the sprite's {@code name} string, so a
 * renamed sprite ({@code setSpriteFromName} in {@code dirtStamp}) recomputes and a stable one costs
 * a field read and a reference compare. {@link #compute(String)} is a verbatim port of the three
 * vanilla predicates (including {@code hasSand}'s effectively dead {@code
 * floors_exterior_natural_24} branch); {@code StormFloorFlagsTest} diffs it against a transcription
 * of the vanilla expressions. Exact: single-threaded, and the repeated {@code getFloor()} calls in
 * vanilla cannot observe a different object mid-call.
 */
public final class StormFloorFlags {

    public static final int NATURAL = 1;
    public static final int SAND = 2;
    public static final int DIRT = 4;

    private StormFloorFlags() {}

    /** Immutable cache entry; {@code name} is compared by identity. */
    static final class Entry {
        final String name;
        final int flags;

        Entry(String name, int flags) {
            this.name = name;
            this.flags = flags;
        }
    }

    public static boolean hasNaturalFloor(Object square) {
        return (flags(square) & NATURAL) != 0;
    }

    public static boolean hasSand(Object square) {
        return (flags(square) & SAND) != 0;
    }

    public static boolean hasDirt(Object square) {
        return (flags(square) & DIRT) != 0;
    }

    public static int flags(Object square) {
        IsoObject floor = ((IsoGridSquare) square).getFloor();
        if (floor == null) {
            return 0;
        }
        IsoSprite sprite = floor.getSprite();
        if (sprite == null) {
            return 0;
        }
        String name = sprite.getName();
        if (name == null) {
            return 0;
        }
        Object spriteRef = sprite;
        if (!(spriteRef instanceof StormSpriteFloorFlagsHolder)) {
            return compute(name);
        }
        StormSpriteFloorFlagsHolder holder = (StormSpriteFloorFlagsHolder) spriteRef;
        Entry entry = (Entry) holder.getStormFloorFlags();
        if (entry == null || entry.name != name) {
            entry = new Entry(name, compute(name));
            holder.setStormFloorFlags(entry);
        }
        return entry.flags;
    }

    public static int compute(String name) {
        int flags = 0;
        if (name.startsWith("blends_natural_01") || name.startsWith("floors_exterior_natural")) {
            flags |= NATURAL;
        }
        if (name.contains("blends_natural_01") || name.contains("floors_exterior_natural_01")) {
            if (name.equals("blends_natural_01_0")
                    || name.equals("blends_natural_01_5")
                    || name.equals("blends_natural_01_6")
                    || name.equals("blends_natural_01_7")
                    || name.contains("floors_exterior_natural_24")) {
                flags |= SAND;
            }
            if (name.equals("blends_natural_01_64")
                    || name.equals("blends_natural_01_69")
                    || name.equals("blends_natural_01_70")
                    || name.equals("blends_natural_01_71")
                    || name.equals("blends_natural_01_80")
                    || name.equals("blends_natural_01_85")
                    || name.equals("blends_natural_01_86")
                    || name.equals("blends_natural_01_87")
                    || name.equals("floors_exterior_natural_16")
                    || name.equals("floors_exterior_natural_17")
                    || name.equals("floors_exterior_natural_18")
                    || name.equals("floors_exterior_natural_19")) {
                flags |= DIRT;
            }
        }
        return flags;
    }
}
