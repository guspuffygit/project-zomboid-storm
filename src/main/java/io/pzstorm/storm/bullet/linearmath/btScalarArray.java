// Port of LinearMath/btAlignedObjectArray.h specialised for btScalar (double) (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * {@code btAlignedObjectArray<btScalar>} on a primitive {@code double[]}. Same algorithms as {@link
 * btAlignedObjectArray} (growth, quickSort, heapSort, findBinarySearch, findLinearSearch, remove =
 * swap-with-last + pop_back) and the same alloc counters. {@code T()} is 0.0.
 */
public class btScalarArray {
    /** {@code CompareFunc(a, b)} */
    @FunctionalInterface
    public interface Less {
        boolean test(double a, double b);
    }

    /** {@code btAlignedObjectArray<btScalar>::less}: {@code a < b}. */
    public static final Less less = (a, b) -> a < b;

    public double[] m_data;
    public int m_size;
    public int m_capacity;
    public boolean m_ownsMemory;
    private long m_dataAddr;

    public btScalarArray() {
        init();
    }

    public btScalarArray(btScalarArray otherArray) {
        init();
        int otherSize = otherArray.size();
        resize(otherSize);
        otherArray.copy(0, otherSize, m_data);
    }

    public btScalarArray set(btScalarArray other) {
        copyFromArray(other);
        return this;
    }

    protected int allocSize(int size) {
        return (size != 0 ? size * 2 : 1);
    }

    protected void copy(int start, int end, double[] dest) {
        for (int i = start; i < end; ++i) dest[i] = m_data[i];
    }

    protected void init() {
        m_ownsMemory = true;
        m_data = null;
        m_dataAddr = 0;
        m_size = 0;
        m_capacity = 0;
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

    public double at(int n) {
        return m_data[n];
    }

    /** {@code operator[]} read */
    public double get(int n) {
        return m_data[n];
    }

    /** {@code a[n] = v} */
    public void set(int n, double v) {
        m_data[n] = v;
    }

    public void clear() {
        deallocate();
        init();
    }

    public void pop_back() {
        m_size--;
    }

    public void resizeNoInitialize(int newsize) {
        int curSize = size();
        if (newsize < curSize) {
        } else {
            if (newsize > size()) {
                reserve(newsize);
            }
        }
        m_size = newsize;
    }

    public void resize(int newsize) {
        resize(newsize, 0);
    }

    public void resize(int newsize, double fillData) {
        int curSize = size();
        if (newsize < curSize) {
        } else {
            if (newsize > size()) {
                reserve(newsize);
            }
            for (int i = curSize; i < newsize; i++) {
                m_data[i] = fillData;
            }
        }
        m_size = newsize;
    }

    /** Returns the index of the new (uninitialised: keeps any stale value) element. */
    public int expandNonInitializing() {
        int sz = size();
        if (sz == capacity()) {
            reserve(allocSize(size()));
        }
        m_size++;
        return sz;
    }

    /** Returns the index of the new element (set to fillValue). */
    public int expand(double fillValue) {
        int sz = size();
        if (sz == capacity()) {
            reserve(allocSize(size()));
        }
        m_size++;
        m_data[sz] = fillValue;
        return sz;
    }

    public int expand() {
        return expand(0);
    }

    public void push_back(double _Val) {
        int sz = size();
        if (sz == capacity()) {
            reserve(allocSize(size()));
        }
        m_data[m_size] = _Val;
        m_size++;
    }

    public int capacity() {
        return m_capacity;
    }

    public void reserve(int _Count) {
        if (capacity() < _Count) {
            long addr = _Count != 0 ? btGlobals.btAlignedAlloc(8L * _Count, 16) : 0;
            double[] s = new double[_Count];
            if (m_data != null) copy(0, size(), s);
            deallocate();
            m_ownsMemory = true;
            m_data = s;
            m_dataAddr = addr;
            m_capacity = _Count;
        }
    }

    public void quickSortInternal(Less CompareFunc, int lo, int hi) {
        int i = lo, j = hi;
        double x = m_data[(lo + hi) / 2];
        do {
            while (CompareFunc.test(m_data[i], x)) i++;
            while (CompareFunc.test(x, m_data[j])) j--;
            if (i <= j) {
                swap(i, j);
                i++;
                j--;
            }
        } while (i <= j);
        if (lo < j) quickSortInternal(CompareFunc, lo, j);
        if (i < hi) quickSortInternal(CompareFunc, i, hi);
    }

    public void quickSort(Less CompareFunc) {
        if (size() > 1) {
            quickSortInternal(CompareFunc, 0, size() - 1);
        }
    }

    public void downHeap(double[] pArr, int k, int n, Less CompareFunc) {
        double temp = pArr[k - 1];
        while (k <= n / 2) {
            int child = 2 * k;
            if ((child < n) && CompareFunc.test(pArr[child - 1], pArr[child])) {
                child++;
            }
            if (CompareFunc.test(temp, pArr[child - 1])) {
                pArr[k - 1] = pArr[child - 1];
                k = child;
            } else {
                break;
            }
        }
        pArr[k - 1] = temp;
    }

    public void swap(int index0, int index1) {
        double temp = m_data[index0];
        m_data[index0] = m_data[index1];
        m_data[index1] = temp;
    }

    public void heapSort(Less CompareFunc) {
        int k;
        int n = m_size;
        for (k = n / 2; k > 0; k--) {
            downHeap(m_data, k, n, CompareFunc);
        }
        while (n >= 1) {
            swap(0, n - 1);
            n -= 1;
            downHeap(m_data, 1, n, CompareFunc);
        }
    }

    /** double comparison ({@code key > m_data[mid]}, {@code key < m_data[mid]}). */
    public int findBinarySearch(double key) {
        int first = 0;
        int last = size() - 1;
        while (first <= last) {
            int mid = (first + last) / 2;
            if (key > m_data[mid]) first = mid + 1;
            else if (key < m_data[mid]) last = mid - 1;
            else return mid;
        }
        return size();
    }

    public int findLinearSearch(double key) {
        int index = size();
        int i;
        for (i = 0; i < size(); i++) {
            if (m_data[i] == key) {
                index = i;
                break;
            }
        }
        return index;
    }

    public void remove(double key) {
        int findIndex = findLinearSearch(key);
        if (findIndex < size()) {
            swap(findIndex, size() - 1);
            pop_back();
        }
    }

    public void initializeFromBuffer(double[] buffer, int size, int capacity) {
        clear();
        m_ownsMemory = false;
        m_data = buffer;
        m_size = size;
        m_capacity = capacity;
    }

    public void copyFromArray(btScalarArray otherArray) {
        int otherSize = otherArray.size();
        resize(otherSize);
        otherArray.copy(0, otherSize, m_data);
    }
}
