// Port of LinearMath/btAlignedObjectArray.h (Bullet 2.82), BT_USE_PLACEMENT_NEW defined,
// BT_USE_MEMCPY not defined, BT_ALLOW_ARRAY_COPY_OPERATOR defined.
package io.pzstorm.storm.bullet.linearmath;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * btAlignedObjectArray&lt;T&gt;. Capacity growth ({@code allocSize = size ? size*2 : 1}), {@link
 * #quickSort}, {@link #heapSort}, {@link #findBinarySearch}, {@link #findLinearSearch} and {@link
 * #remove} (swap with last, then pop_back) are verbatim, so unstable sort / removal order matches
 * C++.
 *
 * <h2>Two storage modes</h2>
 *
 * <ul>
 *   <li><b>Pointer mode</b> ({@link #btAlignedObjectArray()}): for {@code T*} element types. Slots
 *       hold references; {@code a[i] = p} is {@link #set(int, Object)}; {@code T()} is {@code
 *       null}. {@link #findLinearSearch}/{@link #remove} compare with {@code ==} (pointer
 *       identity).
 *   <li><b>Value mode</b> ({@link #btAlignedObjectArray(Supplier, BiConsumer)}): for value element
 *       types (btVector3, structs). Each slot owns its object; {@link #get} returns that object
 *       (like C++ {@code T&}); {@link #push_back}/{@link #set} copy the value into the slot with
 *       {@code assign(dst, src)}; {@link #swap} and the sort pivot/heap temp copy values, exactly
 *       like the C++ {@code T temp = m_data[i]} code. {@link #findLinearSearch}/{@link #remove}
 *       need an equality predicate for value types (C++ {@code operator==}); pass it via {@link
 *       #setEquals} or the two-argument overloads.
 * </ul>
 *
 * <p>Sorts take {@code less.test(a, b)} = C++ {@code CompareFunc(a, b)}. {@link #findBinarySearch}
 * uses {@code key > x} as {@code less(x, key)} and {@code key < x} as {@code less(key, x)}.
 *
 * <p>Allocation counters: every C++ allocate/deallocate goes through btAlignedAllocInternal /
 * btAlignedFreeInternal, which bump {@link btGlobals#gNumAlignedAllocs}/{@link
 * btGlobals#gNumAlignedFree}; the same happens here ({@link #reserve}, {@link #clear}).
 *
 * <p>For pointer mode {@link #expandNonInitializing()} returns whatever reference is in the slot
 * (normally null); write the element with {@code set(size()-1, p)}.
 */
public class btAlignedObjectArray<T> {
    public Object[] m_data;
    public int m_size;
    public int m_capacity;
    public boolean m_ownsMemory;

    /** fake allocation address of m_data (0 = none); only used for the alloc/free counters. */
    private long m_dataAddr;

    /** Value-mode factory ({@code T()}); null in pointer mode. */
    public final Supplier<T> m_factory;

    /** Value-mode assignment {@code dst = src}; null in pointer mode. */
    public final BiConsumer<T, T> m_assign;

    /** Value-mode {@code operator==} used by findLinearSearch/remove; null = identity. */
    public BiPredicate<T, T> m_equals;

    private T swapTemp;
    private T pivotTemp;
    private T heapTemp;

    /** Pointer mode. */
    public btAlignedObjectArray() {
        m_factory = null;
        m_assign = null;
        init();
    }

    /** Value mode. {@code assign.accept(dst, src)} must copy src into dst. */
    public btAlignedObjectArray(Supplier<T> factory, BiConsumer<T, T> assign) {
        m_factory = factory;
        m_assign = assign;
        init();
    }

    /** Value mode with an {@code operator==}. */
    public btAlignedObjectArray(
            Supplier<T> factory, BiConsumer<T, T> assign, BiPredicate<T, T> equals) {
        this(factory, assign);
        m_equals = equals;
    }

    /** Copy constructor ({@code init(); resize(otherSize); otherArray.copy(...)}). */
    public btAlignedObjectArray(btAlignedObjectArray<T> otherArray) {
        m_factory = otherArray.m_factory;
        m_assign = otherArray.m_assign;
        m_equals = otherArray.m_equals;
        init();
        int otherSize = otherArray.size();
        resize(otherSize);
        otherArray.copy(0, otherSize, m_data);
    }

    /** Value-mode array of btVector3 ({@code btAlignedObjectArray<btVector3>}). */
    public static btAlignedObjectArray<btVector3> ofVector3() {
        return new btAlignedObjectArray<>(btVector3::new, btVector3::set, btVector3::equalsValue);
    }

    public void setEquals(BiPredicate<T, T> equals) {
        m_equals = equals;
    }

    public boolean isValueMode() {
        return m_factory != null;
    }

    /** {@code operator=} */
    public btAlignedObjectArray<T> set(btAlignedObjectArray<T> other) {
        copyFromArray(other);
        return this;
    }

    protected int allocSize(int size) {
        return (size != 0 ? size * 2 : 1);
    }

    /** copy-constructs m_data[start..end) into dest (value mode: fresh copies). */
    @SuppressWarnings("unchecked")
    protected void copy(int start, int end, Object[] dest) {
        int i;
        for (i = start; i < end; ++i) {
            if (m_factory != null) {
                T slot = (T) dest[i];
                if (slot == null) {
                    slot = m_factory.get();
                    dest[i] = slot;
                }
                m_assign.accept(slot, (T) m_data[i]);
            } else {
                dest[i] = m_data[i];
            }
        }
    }

    protected void init() {
        m_ownsMemory = true;
        m_data = null;
        m_dataAddr = 0;
        m_size = 0;
        m_capacity = 0;
    }

    protected void destroy(int first, int last) {
        if (m_factory == null && m_data != null) {
            for (int i = first; i < last; i++) {
                m_data[i] = null;
            }
        }
    }

    protected void deallocate() {
        if (m_data != null) {
            if (m_ownsMemory) {
                btGlobals.btAlignedFree(m_dataAddr);
            }
            m_data = null;
            m_dataAddr = 0;
        }
    }

    public int size() {
        return m_size;
    }

    @SuppressWarnings("unchecked")
    public T at(int n) {
        return (T) m_data[n];
    }

    /** {@code operator[]} (value mode: the slot object itself). */
    @SuppressWarnings("unchecked")
    public T get(int n) {
        return (T) m_data[n];
    }

    /** {@code a[n] = val}: pointer mode stores the reference, value mode copies into the slot. */
    @SuppressWarnings("unchecked")
    public void set(int n, T val) {
        if (m_factory != null) {
            T slot = (T) m_data[n];
            if (slot == null) {
                slot = m_factory.get();
                m_data[n] = slot;
            }
            m_assign.accept(slot, val);
        } else {
            m_data[n] = val;
        }
    }

    public void clear() {
        destroy(0, size());
        deallocate();
        init();
    }

    public void pop_back() {
        m_size--;
        if (m_factory == null) {
            m_data[m_size] = null;
        }
    }

    public void resizeNoInitialize(int newsize) {
        int curSize = size();
        if (newsize < curSize) {
        } else {
            if (newsize > size()) {
                reserve(newsize);
            }
        }
        if (m_factory != null) {
            for (int i = curSize; i < newsize; i++) {
                if (m_data[i] == null) m_data[i] = m_factory.get();
            }
        }
        m_size = newsize;
    }

    /** {@code resize(newsize, T())} */
    public void resize(int newsize) {
        resize(newsize, m_factory != null ? m_factory.get() : null);
    }

    public void resize(int newsize, T fillData) {
        int curSize = size();
        if (newsize < curSize) {
            destroy(newsize, curSize);
        } else {
            if (newsize > size()) {
                reserve(newsize);
            }
            for (int i = curSize; i < newsize; i++) {
                placeNew(i, fillData);
            }
        }
        m_size = newsize;
    }

    /** {@code new (&m_data[i]) T(val)} */
    @SuppressWarnings("unchecked")
    private void placeNew(int i, T val) {
        if (m_factory != null) {
            T slot = (T) m_data[i];
            if (slot == null) {
                slot = m_factory.get();
                m_data[i] = slot;
            }
            m_assign.accept(slot, val);
        } else {
            m_data[i] = val;
        }
    }

    @SuppressWarnings("unchecked")
    public T expandNonInitializing() {
        int sz = size();
        if (sz == capacity()) {
            reserve(allocSize(size()));
        }
        m_size++;
        if (m_factory != null && m_data[sz] == null) {
            m_data[sz] = m_factory.get();
        }
        return (T) m_data[sz];
    }

    /** {@code expand(T())} */
    public T expand() {
        return expand(m_factory != null ? m_factory.get() : null);
    }

    @SuppressWarnings("unchecked")
    public T expand(T fillValue) {
        int sz = size();
        if (sz == capacity()) {
            reserve(allocSize(size()));
        }
        m_size++;
        placeNew(sz, fillValue);
        return (T) m_data[sz];
    }

    public void push_back(T _Val) {
        int sz = size();
        if (sz == capacity()) {
            reserve(allocSize(size()));
        }
        placeNew(m_size, _Val);
        m_size++;
    }

    public int capacity() {
        return m_capacity;
    }

    public void reserve(int _Count) {
        if (capacity() < _Count) {
            long addr = _Count != 0 ? btGlobals.btAlignedAlloc(_Count, 16) : 0;
            Object[] s = new Object[_Count];
            // copy(0, size(), s): value mode moves the slot objects (the old storage is freed).
            if (m_data != null) {
                System.arraycopy(m_data, 0, s, 0, size());
            }
            destroy(0, size());
            deallocate();
            m_ownsMemory = true;
            m_data = s;
            m_dataAddr = addr;
            m_capacity = _Count;
        }
    }

    @SuppressWarnings("unchecked")
    private T copyOf(Object src, T scratch) {
        m_assign.accept(scratch, (T) src);
        return scratch;
    }

    @SuppressWarnings("unchecked")
    public void quickSortInternal(BiPredicate<T, T> CompareFunc, int lo, int hi) {
        int i = lo, j = hi;
        T x;
        if (m_factory != null) {
            if (pivotTemp == null) pivotTemp = m_factory.get();
            x = copyOf(m_data[(lo + hi) / 2], pivotTemp);
        } else {
            x = (T) m_data[(lo + hi) / 2];
        }

        do {
            while (CompareFunc.test((T) m_data[i], x)) i++;
            while (CompareFunc.test(x, (T) m_data[j])) j--;
            if (i <= j) {
                swap(i, j);
                i++;
                j--;
            }
        } while (i <= j);

        if (lo < j) quickSortInternal(CompareFunc, lo, j);
        if (i < hi) quickSortInternal(CompareFunc, i, hi);
    }

    public void quickSort(BiPredicate<T, T> CompareFunc) {
        if (size() > 1) {
            quickSortInternal(CompareFunc, 0, size() - 1);
        }
    }

    /** downHeap on this array's storage (pArr = m_data). k and n are 1-based as in C++. */
    @SuppressWarnings("unchecked")
    public void downHeap(int k, int n, BiPredicate<T, T> CompareFunc) {
        Object[] pArr = m_data;
        T temp;
        if (m_factory != null) {
            if (heapTemp == null) heapTemp = m_factory.get();
            temp = copyOf(pArr[k - 1], heapTemp);
        } else {
            temp = (T) pArr[k - 1];
        }
        while (k <= n / 2) {
            int child = 2 * k;

            if ((child < n) && CompareFunc.test((T) pArr[child - 1], (T) pArr[child])) {
                child++;
            }
            if (CompareFunc.test(temp, (T) pArr[child - 1])) {
                assignSlot(k - 1, pArr[child - 1]);
                k = child;
            } else {
                break;
            }
        }
        assignSlot(k - 1, temp);
    }

    @SuppressWarnings("unchecked")
    private void assignSlot(int dst, Object src) {
        if (m_factory != null) {
            m_assign.accept((T) m_data[dst], (T) src);
        } else {
            m_data[dst] = src;
        }
    }

    @SuppressWarnings("unchecked")
    public void swap(int index0, int index1) {
        if (m_factory != null) {
            if (swapTemp == null) swapTemp = m_factory.get();
            T temp = copyOf(m_data[index0], swapTemp);
            m_assign.accept((T) m_data[index0], (T) m_data[index1]);
            m_assign.accept((T) m_data[index1], temp);
        } else {
            Object temp = m_data[index0];
            m_data[index0] = m_data[index1];
            m_data[index1] = temp;
        }
    }

    public void heapSort(BiPredicate<T, T> CompareFunc) {
        int k;
        int n = m_size;
        for (k = n / 2; k > 0; k--) {
            downHeap(k, n, CompareFunc);
        }

        while (n >= 1) {
            swap(0, n - 1);
            n -= 1;
            downHeap(1, n, CompareFunc);
        }
    }

    /** {@code key > x} is {@code less(x, key)}, {@code key < x} is {@code less(key, x)}. */
    @SuppressWarnings("unchecked")
    public int findBinarySearch(T key, BiPredicate<T, T> less) {
        int first = 0;
        int last = size() - 1;

        while (first <= last) {
            int mid = (first + last) / 2;
            if (less.test((T) m_data[mid], key)) first = mid + 1;
            else if (less.test(key, (T) m_data[mid])) last = mid - 1;
            else return mid;
        }
        return size();
    }

    /** {@code m_data[i] == key} via {@link #m_equals}, or identity if none is set. */
    public int findLinearSearch(T key) {
        return findLinearSearch(key, m_equals);
    }

    @SuppressWarnings("unchecked")
    public int findLinearSearch(T key, BiPredicate<T, T> equals) {
        int index = size();
        int i;

        for (i = 0; i < size(); i++) {
            boolean eq = equals != null ? equals.test((T) m_data[i], key) : m_data[i] == key;
            if (eq) {
                index = i;
                break;
            }
        }
        return index;
    }

    public void remove(T key) {
        remove(key, m_equals);
    }

    public void remove(T key, BiPredicate<T, T> equals) {
        int findIndex = findLinearSearch(key, equals);
        if (findIndex < size()) {
            swap(findIndex, size() - 1);
            pop_back();
        }
    }

    /** Adopts an external buffer (not owned; never freed). */
    public void initializeFromBuffer(Object[] buffer, int size, int capacity) {
        clear();
        m_ownsMemory = false;
        m_data = buffer;
        m_size = size;
        m_capacity = capacity;
    }

    public void copyFromArray(btAlignedObjectArray<T> otherArray) {
        int otherSize = otherArray.size();
        resize(otherSize);
        otherArray.copy(0, otherSize, m_data);
    }
}
