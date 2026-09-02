package io.pzstorm.storm.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Drop-in {@link ArrayList} that reports every content mutation to {@link
 * StormInventoryWeight#bumpContainer} for the {@code ItemContainer} it backs. Installed as {@code
 * ItemContainer.items} by {@code ItemContainerTrackedListPatch} (constructors, {@code setItems},
 * {@code emptyIt}) so the character weight memo is invalidated by direct {@code getItems().add(..)}
 * sites as well as by the container's own methods.
 *
 * <p>Every public mutator {@code ArrayList} exposes is overridden; the iterator and list-iterator
 * removal/insertion paths and {@code subList().clear()} funnel into {@code remove(int)}, {@code
 * add(int, E)}, {@code set} and {@code removeRange}, which are covered. Ordering-only operations
 * ({@code sort}, capacity hints) are weight-neutral and left alone. Kahlua dispatches Lua calls on
 * the returned list through {@code ArrayList}'s exposed metatable (class metatable lookup walks
 * superclasses), so Lua {@code getItems():size()} / {@code get(i)} keep working.
 */
public class StormTrackedItemList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    private final transient Object container;

    public StormTrackedItemList(Object container, Collection<? extends E> initial) {
        super(initial);
        this.container = container;
    }

    public Object getContainer() {
        return container;
    }

    /** Invoked after every content mutation. */
    protected void onMutate() {
        StormInventoryWeight.bumpContainer(container);
    }

    @Override
    public boolean add(E e) {
        boolean changed = super.add(e);
        onMutate();
        return changed;
    }

    @Override
    public void add(int index, E element) {
        super.add(index, element);
        onMutate();
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean changed = super.addAll(c);
        if (changed) {
            onMutate();
        }
        return changed;
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        boolean changed = super.addAll(index, c);
        if (changed) {
            onMutate();
        }
        return changed;
    }

    @Override
    public E remove(int index) {
        E removed = super.remove(index);
        onMutate();
        return removed;
    }

    @Override
    public boolean remove(Object o) {
        boolean changed = super.remove(o);
        if (changed) {
            onMutate();
        }
        return changed;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean changed = super.removeAll(c);
        if (changed) {
            onMutate();
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean changed = super.retainAll(c);
        if (changed) {
            onMutate();
        }
        return changed;
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        boolean changed = super.removeIf(filter);
        if (changed) {
            onMutate();
        }
        return changed;
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        super.removeRange(fromIndex, toIndex);
        if (fromIndex < toIndex) {
            onMutate();
        }
    }

    @Override
    public void clear() {
        boolean wasEmpty = isEmpty();
        super.clear();
        if (!wasEmpty) {
            onMutate();
        }
    }

    @Override
    public E set(int index, E element) {
        E previous = super.set(index, element);
        onMutate();
        return previous;
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        super.replaceAll(operator);
        onMutate();
    }
}
