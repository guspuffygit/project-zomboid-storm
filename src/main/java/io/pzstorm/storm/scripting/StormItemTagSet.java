package io.pzstorm.storm.scripting;

import io.pzstorm.storm.entity.StormItemTagIndexHolder;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;

/**
 * The set behind {@code Item.itemTags}: a {@link HashSet} (so iteration, {@code size}, Lua {@code
 * getItemTags()} callers and the vanilla {@code add} at script-parse time all behave as before)
 * that additionally keeps a bitmask over the tags' {@link StormItemTagIndexHolder} indices, and
 * answers {@link #contains} for an indexed tag with one bit test instead of an identity-hash bucket
 * probe. {@code Item.hasTag(ItemTag)} is called ~280 sites deep in the per-tick character update
 * ({@code isKeyRing}, {@code checkSCBADrain}, container filters …); 3% of player update on ATF prod
 * (scan #10, 2026-09-02).
 *
 * <p>Exactness: {@code ItemTag} has identity {@code equals}, so vanilla {@code contains(tag)} is
 * "this exact instance was added and not removed" — which is precisely what the bit tracks. Every
 * element that is not an index holder (null, or a tag constructed before the index patch applied)
 * takes the {@code HashSet} path for both membership and lookup, so an unpatched {@code ItemTag}
 * class degrades to vanilla behaviour rather than to wrong answers. Removals through {@link
 * #remove}, {@link #clear} and the iterator clear the bit; a bulk removal rebuilds the mask from
 * the surviving elements.
 *
 * <p>{@code isKeyRing()} and {@code checkSCBADrain()} were considered for a per-item /
 * per-character precomputed flag; both reduce to {@code hasTag} calls (plus an enum compare and a
 * worn-items walk of ~10 entries), which after this change cost a handful of nanoseconds — a cached
 * flag would need its own invalidation for no measurable gain.
 */
public class StormItemTagSet extends HashSet<Object> {

    private static final long serialVersionUID = 1L;

    private long[] mask = new long[1];

    @Override
    public boolean contains(Object o) {
        if (o instanceof StormItemTagIndexHolder) {
            return StormTagMask.test(mask, ((StormItemTagIndexHolder) o).getStormIndex());
        }
        return super.contains(o);
    }

    @Override
    public boolean add(Object o) {
        boolean added = super.add(o);
        if (added && o instanceof StormItemTagIndexHolder) {
            mask = StormTagMask.set(mask, ((StormItemTagIndexHolder) o).getStormIndex());
        }
        return added;
    }

    @Override
    public boolean remove(Object o) {
        boolean removed = super.remove(o);
        if (removed && o instanceof StormItemTagIndexHolder) {
            StormTagMask.clear(mask, ((StormItemTagIndexHolder) o).getStormIndex());
        }
        return removed;
    }

    @Override
    public void clear() {
        super.clear();
        mask = new long[1];
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean changed = super.removeAll(c);
        if (changed) {
            rebuild();
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean changed = super.retainAll(c);
        if (changed) {
            rebuild();
        }
        return changed;
    }

    @Override
    public Iterator<Object> iterator() {
        Iterator<Object> delegate = super.iterator();
        return new Iterator<Object>() {
            private Object last;

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public Object next() {
                last = delegate.next();
                return last;
            }

            @Override
            public void remove() {
                delegate.remove();
                if (last instanceof StormItemTagIndexHolder) {
                    StormTagMask.clear(mask, ((StormItemTagIndexHolder) last).getStormIndex());
                }
            }
        };
    }

    @Override
    public Object clone() {
        StormItemTagSet copy = (StormItemTagSet) super.clone();
        copy.mask = mask.clone();
        return copy;
    }

    private void rebuild() {
        long[] fresh = new long[mask.length];
        for (Object o : this) {
            if (o instanceof StormItemTagIndexHolder) {
                fresh = StormTagMask.set(fresh, ((StormItemTagIndexHolder) o).getStormIndex());
            }
        }
        mask = fresh;
    }
}
