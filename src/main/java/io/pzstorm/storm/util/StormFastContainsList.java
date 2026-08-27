package io.pzstorm.storm.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * {@code ArrayList} drop-in whose {@code contains}/{@code indexOf} negative probes and {@code
 * remove(Object)} misses are O(1) via a count-map mirror, and whose {@code removeAll}
 * short-circuits on an empty argument (vanilla {@code ArrayList.removeAll} walks every element
 * calling {@code c.contains} even when {@code c} is empty — {@code IsoCell.ProcessRemoveItems} does
 * exactly that with both lists every tick, ~0.9% of server main in the ATF 2026-08-26 profile).
 *
 * <p>Swapped into vanilla fields by constructor-exit advice ({@code IsoCell.processItems} / {@code
 * processWorldItems}, {@code ServerMap.releventNow}, {@code DesignationZoneAnimal.foodOnGround}).
 * Those element types ({@code InventoryItem}, {@code ServerMap.ServerCell}, {@code
 * IsoWorldInventoryObject}) override neither {@code equals} nor {@code hashCode}, so the {@code
 * HashMap} mirror's equality matches {@code ArrayList.contains}'s {@code equals} scan exactly. Do
 * not swap this under a list whose elements have a mutable {@code hashCode} — the mirror would
 * desync and {@code contains} would return stale answers.
 *
 * <p>Every {@code ArrayList} mutation path funnels through the overrides below, including the paths
 * JDK collaborators re-enter through virtually: {@code Itr.remove} → {@code remove(int)}, {@code
 * ListItr.set/add} → {@code set/add(int, E)}, {@code SubList.clear} → {@code removeRange}. The
 * {@code SequencedCollection} methods are overridden explicitly because {@code
 * ArrayList.removeFirst/removeLast} mutate through the internal {@code fastRemove} rather than
 * {@code remove(int)} (caught by the oracle test on JDK 21+). Bulk filters ({@code removeAll},
 * {@code retainAll}, {@code removeIf}, {@code replaceAll}) bypass the single-element methods, so
 * they rebuild the mirror afterwards — O(n), same order as the vanilla operation itself. Not
 * thread-safe, exactly like the {@code ArrayList} it replaces.
 */
public final class StormFastContainsList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    private final transient HashMap<Object, Integer> counts = new HashMap<>();

    /**
     * Replacement factory for constructor-exit advice: copies whatever the vanilla field
     * initializer put there (usually an empty {@code ArrayList}, but constructor bodies may have
     * added elements before the advice runs). Takes {@code Object} so advice bodies stay free of
     * unchecked casts.
     */
    @SuppressWarnings("unchecked")
    public static <E> StormFastContainsList<E> copyOf(Object source) {
        StormFastContainsList<E> list = new StormFastContainsList<>();
        if (source instanceof Collection) {
            list.addAll((Collection<? extends E>) source);
        }
        return list;
    }

    @Override
    public boolean contains(Object o) {
        return counts.containsKey(o);
    }

    @Override
    public int indexOf(Object o) {
        return counts.containsKey(o) ? super.indexOf(o) : -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        return counts.containsKey(o) ? super.lastIndexOf(o) : -1;
    }

    @Override
    public boolean add(E e) {
        super.add(e);
        inc(e);
        return true;
    }

    @Override
    public void add(int index, E element) {
        super.add(index, element);
        inc(element);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        int oldSize = size();
        boolean changed = super.addAll(c);
        for (int i = oldSize, n = size(); i < n; i++) {
            inc(get(i));
        }
        return changed;
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        int added = c.size();
        boolean changed = super.addAll(index, c);
        for (int i = index; i < index + added; i++) {
            inc(get(i));
        }
        return changed;
    }

    @Override
    public boolean remove(Object o) {
        if (!counts.containsKey(o)) {
            return false;
        }
        boolean removed = super.remove(o);
        if (removed) {
            dec(o);
        }
        return removed;
    }

    @Override
    public E remove(int index) {
        E removed = super.remove(index);
        dec(removed);
        return removed;
    }

    @Override
    public void addFirst(E element) {
        add(0, element);
    }

    @Override
    public void addLast(E element) {
        add(element);
    }

    @Override
    public E removeFirst() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return remove(0);
    }

    @Override
    public E removeLast() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        return remove(size() - 1);
    }

    @Override
    public E set(int index, E element) {
        E old = super.set(index, element);
        dec(old);
        inc(element);
        return old;
    }

    @Override
    public void clear() {
        super.clear();
        counts.clear();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        if (c.isEmpty()) {
            return false;
        }
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
    public boolean removeIf(Predicate<? super E> filter) {
        boolean changed = super.removeIf(filter);
        if (changed) {
            rebuild();
        }
        return changed;
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        super.replaceAll(operator);
        rebuild();
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        for (int i = fromIndex; i < toIndex; i++) {
            dec(get(i));
        }
        super.removeRange(fromIndex, toIndex);
    }

    @Override
    public Object clone() {
        return copyOf(this);
    }

    private void inc(Object e) {
        Integer c = counts.get(e);
        counts.put(e, c == null ? 1 : c + 1);
    }

    private void dec(Object e) {
        Integer c = counts.get(e);
        if (c == null) {
            return;
        }
        if (c == 1) {
            counts.remove(e);
        } else {
            counts.put(e, c - 1);
        }
    }

    private void rebuild() {
        counts.clear();
        for (int i = 0, n = size(); i < n; i++) {
            inc(get(i));
        }
    }
}
