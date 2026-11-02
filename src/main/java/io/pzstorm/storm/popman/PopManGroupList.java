package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.Collection;

/**
 * The worker's travelling groups, native {@code m_virtualZombies}. Every native writer that adds or
 * removes a group also sets {@code m_virtualZombiesChanged}, and only that flag lets {@code
 * zpop_virtual.bin} be rewritten, so membership changes raise {@link #changed} here.
 */
public final class PopManGroupList extends ArrayList<PopManGroup> {

    public boolean changed;

    @Override
    public boolean add(PopManGroup group) {
        changed = true;
        return super.add(group);
    }

    @Override
    public void add(int index, PopManGroup group) {
        changed = true;
        super.add(index, group);
    }

    @Override
    public boolean addAll(Collection<? extends PopManGroup> added) {
        changed |= !added.isEmpty();
        return super.addAll(added);
    }

    @Override
    public PopManGroup remove(int index) {
        changed = true;
        return super.remove(index);
    }

    @Override
    public boolean remove(Object group) {
        boolean removed = super.remove(group);
        changed |= removed;
        return removed;
    }

    @Override
    public void clear() {
        changed = true;
        super.clear();
    }
}
