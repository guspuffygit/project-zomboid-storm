// Port of LinearMath/btHashMap.h class btHashMap (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * btHashMap&lt;Key, Value&gt;: open hash with chaining through {@code m_next}, dense {@code
 * m_keyArray}/{@code m_valueArray}. Iteration order ({@link #getAtIndex}/{@link #getKeyAtIndex}
 * over {@code 0..size()-1}) is insertion order, perturbed by {@link #remove}, which moves the last
 * pair into the removed slot, exactly as C++.
 *
 * <p>Keys implement {@link HashKey} (btHashInt, btHashPtr, btHashString, btHashKey, btHashKeyPtr).
 * The map stores a copy of each key ({@link HashKey#copyKey}), as C++ stores by value.
 *
 * <p>Values: default pointer mode ({@code Value} is a pointer / boxed primitive; {@link #find}
 * returns the stored reference or null for C++ NULL). Pass a factory/assign pair for value types
 * (the map then copies values in, and {@link #find} returns the slot object = C++ {@code Value*}).
 * To write through a C++ {@code Value*} in pointer mode use {@link #setAtIndex}.
 */
public class btHashMap<K extends btHashMap.HashKey<K>, V> {
    /** {@code const int BT_HASH_NULL=0xffffffff;} */
    public static final int BT_HASH_NULL = 0xffffffff;

    /** The implicit key concept of btHashMap (getHash / equals). */
    public interface HashKey<K extends HashKey<K>> {
        /** C++ {@code unsigned int getHash() const} (unsigned bits in an int). */
        int getHash();

        boolean equals(K other);

        /** Copy used when the map stores the key by value. */
        K copyKey();
    }

    public final btIntArray m_hashTable = new btIntArray();
    public final btIntArray m_next = new btIntArray();
    public final btAlignedObjectArray<V> m_valueArray;
    public final btAlignedObjectArray<K> m_keyArray = new btAlignedObjectArray<>();

    /** Pointer-mode values. */
    public btHashMap() {
        m_valueArray = new btAlignedObjectArray<>();
    }

    /** Value-mode values. */
    public btHashMap(Supplier<V> factory, BiConsumer<V, V> assign) {
        m_valueArray = new btAlignedObjectArray<>(factory, assign);
    }

    protected void growTables(K key) {
        int newCapacity = m_valueArray.capacity();

        if (m_hashTable.size() < newCapacity) {
            int curHashtableSize = m_hashTable.size();

            m_hashTable.resize(newCapacity);
            m_next.resize(newCapacity);

            int i;

            for (i = 0; i < newCapacity; ++i) {
                m_hashTable.set(i, BT_HASH_NULL);
            }
            for (i = 0; i < newCapacity; ++i) {
                m_next.set(i, BT_HASH_NULL);
            }

            for (i = 0; i < curHashtableSize; i++) {
                int hashValue = m_keyArray.get(i).getHash() & (m_valueArray.capacity() - 1);
                m_next.set(i, m_hashTable.get(hashValue));
                m_hashTable.set(hashValue, i);
            }
        }
    }

    public void insert(K key, V value) {
        int hash = key.getHash() & (m_valueArray.capacity() - 1);

        int index = findIndex(key);
        if (index != BT_HASH_NULL) {
            m_valueArray.set(index, value);
            return;
        }

        int count = m_valueArray.size();
        int oldCapacity = m_valueArray.capacity();
        m_valueArray.push_back(value);
        m_keyArray.push_back(key.copyKey());

        int newCapacity = m_valueArray.capacity();
        if (oldCapacity < newCapacity) {
            growTables(key);
            hash = key.getHash() & (m_valueArray.capacity() - 1);
        }
        m_next.set(count, m_hashTable.get(hash));
        m_hashTable.set(hash, count);
    }

    public void remove(K key) {
        int hash = key.getHash() & (m_valueArray.capacity() - 1);

        int pairIndex = findIndex(key);

        if (pairIndex == BT_HASH_NULL) {
            return;
        }

        int index = m_hashTable.get(hash);

        int previous = BT_HASH_NULL;
        while (index != pairIndex) {
            previous = index;
            index = m_next.get(index);
        }

        if (previous != BT_HASH_NULL) {
            m_next.set(previous, m_next.get(pairIndex));
        } else {
            m_hashTable.set(hash, m_next.get(pairIndex));
        }

        int lastPairIndex = m_valueArray.size() - 1;

        if (lastPairIndex == pairIndex) {
            m_valueArray.pop_back();
            m_keyArray.pop_back();
            return;
        }

        int lastHash = m_keyArray.get(lastPairIndex).getHash() & (m_valueArray.capacity() - 1);

        index = m_hashTable.get(lastHash);

        previous = BT_HASH_NULL;
        while (index != lastPairIndex) {
            previous = index;
            index = m_next.get(index);
        }

        if (previous != BT_HASH_NULL) {
            m_next.set(previous, m_next.get(lastPairIndex));
        } else {
            m_hashTable.set(lastHash, m_next.get(lastPairIndex));
        }

        m_valueArray.set(pairIndex, m_valueArray.get(lastPairIndex));
        m_keyArray.set(pairIndex, m_keyArray.get(lastPairIndex));

        m_next.set(pairIndex, m_hashTable.get(lastHash));
        m_hashTable.set(lastHash, pairIndex);

        m_valueArray.pop_back();
        m_keyArray.pop_back();
    }

    public int size() {
        return m_valueArray.size();
    }

    /** {@code *getAtIndex(index)} */
    public V getAtIndex(int index) {
        return m_valueArray.get(index);
    }

    /** {@code *getAtIndex(index) = value} */
    public void setAtIndex(int index, V value) {
        m_valueArray.set(index, value);
    }

    /** Not in 2.82's btHashMap (added in later Bullet); convenience for iterating keys. */
    public K getKeyAtIndex(int index) {
        return m_keyArray.get(index);
    }

    /** {@code operator[](key)} == find(key). */
    public V get(K key) {
        return find(key);
    }

    /** Returns the value (C++ {@code *find(key)}) or null for C++ NULL. */
    public V find(K key) {
        int index = findIndex(key);
        if (index == BT_HASH_NULL) {
            return null;
        }
        return m_valueArray.get(index);
    }

    public int findIndex(K key) {
        int hash = key.getHash() & (m_valueArray.capacity() - 1);

        if (Integer.compareUnsigned(hash, m_hashTable.size()) >= 0) {
            return BT_HASH_NULL;
        }

        int index = m_hashTable.get(hash);
        while ((index != BT_HASH_NULL) && key.equals(m_keyArray.get(index)) == false) {
            index = m_next.get(index);
        }
        return index;
    }

    public void clear() {
        m_hashTable.clear();
        m_next.clear();
        m_valueArray.clear();
        m_keyArray.clear();
    }

    /** Thomas Wang's int hash as written in btHashInt/btHashKey/btHashPtr::getHash. */
    public static int wangHash(int key) {
        key += ~(key << 15);
        key ^= (key >> 10);
        key += (key << 3);
        key ^= (key >> 6);
        key += ~(key << 11);
        key ^= (key >> 16);
        return key;
    }
}
