package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btIntArray;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btHashedSimplePairCache.{h,cpp}.
 *
 * <p>Used only by btCompoundCompoundCollisionAlgorithm as its child-algorithm cache. The pair array
 * is a value array ({@code btSimplePairArray}); returned {@link btSimplePair} references are the
 * array slots (C++ {@code &m_overlappingPairArray[index]}). Counters gOverlappingSimplePairs,
 * gRemoveSimplePairs, gAddedSimplePairs, gFindSimplePairs live in {@link btGlobals}.
 */
public class btHashedSimplePairCache {
    /** {@code const int BT_SIMPLE_NULL_PAIR=0xffffffff;} */
    public static final int BT_SIMPLE_NULL_PAIR = 0xffffffff;

    /**
     * sizeof(btHashedSimplePairCache) on x86-64: vptr + array(32) + bool(8) + 2 int arrays(32
     * each).
     */
    public static final int SIZEOF = 0x70;

    public final btAlignedObjectArray<btSimplePair> m_overlappingPairArray =
            new btAlignedObjectArray<>(btSimplePair::new, btSimplePair::set);
    public boolean m_blockedForChanges;

    public final btIntArray m_hashTable = new btIntArray();
    public final btIntArray m_next = new btIntArray();

    public btHashedSimplePairCache() {
        m_blockedForChanges = false;
        int initialAllocatedSize = 2;
        m_overlappingPairArray.reserve(initialAllocatedSize);
        growTables();
    }

    /**
     * {@code virtual ~btHashedSimplePairCache()} — empty body; the member arrays' destructors run
     * afterwards in reverse declaration order (m_next, m_hashTable, m_overlappingPairArray), each
     * calling clear().
     */
    public void destroy() {
        m_next.clear();
        m_hashTable.clear();
        m_overlappingPairArray.clear();
    }

    public void removeAllPairs() {
        m_overlappingPairArray.clear();
        m_hashTable.clear();
        m_next.clear();

        int initialAllocatedSize = 2;
        m_overlappingPairArray.reserve(initialAllocatedSize);
        growTables();
    }

    /** Returns the removed pair's {@code m_userPointer} (C++ {@code void*}). */
    public Object removeOverlappingPair(int indexA, int indexB) {
        btGlobals.gRemoveSimplePairs++;

        int hash = getHash(indexA, indexB) & (m_overlappingPairArray.capacity() - 1);

        btSimplePair pair = internalFindPair(indexA, indexB, hash);
        if (pair == null) {
            return null;
        }

        Object userData = pair.m_userPointer;

        int pairIndex = indexOfSlot(pair);

        // Remove the pair from the hash table.
        int index = m_hashTable.get(hash);

        int previous = BT_SIMPLE_NULL_PAIR;
        while (index != pairIndex) {
            previous = index;
            index = m_next.get(index);
        }

        if (previous != BT_SIMPLE_NULL_PAIR) {
            m_next.set(previous, m_next.get(pairIndex));
        } else {
            m_hashTable.set(hash, m_next.get(pairIndex));
        }

        // We now move the last pair into spot of the
        // pair being removed. We need to fix the hash
        // table indices to support the move.

        int lastPairIndex = m_overlappingPairArray.size() - 1;

        // If the removed pair is the last pair, we are done.
        if (lastPairIndex == pairIndex) {
            m_overlappingPairArray.pop_back();
            return userData;
        }

        // Remove the last pair from the hash table.
        btSimplePair last = m_overlappingPairArray.get(lastPairIndex);
        /* missing swap here too, Nat. */
        int lastHash =
                getHash(last.m_indexA, last.m_indexB) & (m_overlappingPairArray.capacity() - 1);

        index = m_hashTable.get(lastHash);

        previous = BT_SIMPLE_NULL_PAIR;
        while (index != lastPairIndex) {
            previous = index;
            index = m_next.get(index);
        }

        if (previous != BT_SIMPLE_NULL_PAIR) {
            m_next.set(previous, m_next.get(lastPairIndex));
        } else {
            m_hashTable.set(lastHash, m_next.get(lastPairIndex));
        }

        // Copy the last pair into the remove pair's spot.
        m_overlappingPairArray.get(pairIndex).set(m_overlappingPairArray.get(lastPairIndex));

        // Insert the last pair into the hash table
        m_next.set(pairIndex, m_hashTable.get(lastHash));
        m_hashTable.set(lastHash, pairIndex);

        m_overlappingPairArray.pop_back();

        return userData;
    }

    /**
     * Add a pair and return the new pair. If the pair already exists, no new pair is created and
     * the old one is returned.
     */
    public btSimplePair addOverlappingPair(int indexA, int indexB) {
        btGlobals.gAddedSimplePairs++;

        return internalAddPair(indexA, indexB);
    }

    /**
     * {@code &m_overlappingPairArray[0]}: the first slot object (may be null if the slot has never
     * been constructed; C++ would return a pointer to uninitialized storage).
     */
    public btSimplePair getOverlappingPairArrayPtr() {
        return m_overlappingPairArray.m_data == null
                ? null
                : (btSimplePair) m_overlappingPairArray.m_data[0];
    }

    public btAlignedObjectArray<btSimplePair> getOverlappingPairArray() {
        return m_overlappingPairArray;
    }

    public btSimplePair findPair(int indexA, int indexB) {
        btGlobals.gFindSimplePairs++;

        /*if (indexA > indexB)
        btSwap(indexA, indexB);*/

        int hash = getHash(indexA, indexB) & (m_overlappingPairArray.capacity() - 1);

        if (hash >= m_hashTable.size()) {
            return null;
        }

        int index = m_hashTable.get(hash);
        while (index != BT_SIMPLE_NULL_PAIR
                && equalsPair(m_overlappingPairArray.get(index), indexA, indexB) == false) {
            index = m_next.get(index);
        }

        if (index == BT_SIMPLE_NULL_PAIR) {
            return null;
        }

        return m_overlappingPairArray.get(index);
    }

    public int GetCount() {
        return m_overlappingPairArray.size();
    }

    public int getNumOverlappingPairs() {
        return m_overlappingPairArray.size();
    }

    private btSimplePair internalAddPair(int indexA, int indexB) {
        int hash =
                getHash(indexA, indexB)
                        & (m_overlappingPairArray.capacity() - 1); // New hash value with new mask

        btSimplePair pair = internalFindPair(indexA, indexB, hash);
        if (pair != null) {
            return pair;
        }

        int count = m_overlappingPairArray.size();
        int oldCapacity = m_overlappingPairArray.capacity();
        btSimplePair mem = m_overlappingPairArray.expandNonInitializing();

        int newCapacity = m_overlappingPairArray.capacity();

        if (oldCapacity < newCapacity) {
            growTables();
            // hash with new capacity
            hash = getHash(indexA, indexB) & (m_overlappingPairArray.capacity() - 1);
        }

        // new (mem) btSimplePair(indexA,indexB);
        mem.m_indexA = indexA;
        mem.m_indexB = indexB;
        mem.m_userPointer = null;
        mem.m_userValue = 0;
        pair = mem;
        pair.m_userPointer = null;

        m_next.set(count, m_hashTable.get(hash));
        m_hashTable.set(hash, count);

        return pair;
    }

    private void growTables() {
        int newCapacity = m_overlappingPairArray.capacity();

        if (m_hashTable.size() < newCapacity) {
            // grow hashtable and next table
            int curHashtableSize = m_hashTable.size();

            m_hashTable.resize(newCapacity);
            m_next.resize(newCapacity);

            int i;

            for (i = 0; i < newCapacity; ++i) {
                m_hashTable.set(i, BT_SIMPLE_NULL_PAIR);
            }
            for (i = 0; i < newCapacity; ++i) {
                m_next.set(i, BT_SIMPLE_NULL_PAIR);
            }

            for (i = 0; i < curHashtableSize; i++) {

                btSimplePair pair = m_overlappingPairArray.get(i);
                int indexA = pair.m_indexA;
                int indexB = pair.m_indexB;

                int hashValue =
                        getHash(indexA, indexB)
                                & (m_overlappingPairArray.capacity()
                                        - 1); // New hash value with new mask
                m_next.set(i, m_hashTable.get(hashValue));
                m_hashTable.set(hashValue, i);
            }
        }
    }

    private static boolean equalsPair(btSimplePair pair, int indexA, int indexB) {
        return pair.m_indexA == indexA && pair.m_indexB == indexB;
    }

    /**
     * Thomas Wang's hash on {@code indexA | indexB<<16} (unsigned result as an int bit pattern).
     */
    public static int getHash(int indexA, int indexB) {
        int key = (indexA) | ((indexB) << 16);
        // Thomas Wang's hash

        key += ~(key << 15);
        key ^= (key >> 10);
        key += (key << 3);
        key ^= (key >> 6);
        key += ~(key << 11);
        key ^= (key >> 16);
        return key;
    }

    private btSimplePair internalFindPair(int proxyIdA, int proxyIdB, int hash) {
        int index = m_hashTable.get(hash);

        while (index != BT_SIMPLE_NULL_PAIR
                && equalsPair(m_overlappingPairArray.get(index), proxyIdA, proxyIdB) == false) {
            index = m_next.get(index);
        }

        if (index == BT_SIMPLE_NULL_PAIR) {
            return null;
        }

        return m_overlappingPairArray.get(index);
    }

    /** {@code int(pair - &m_overlappingPairArray[0])} */
    private int indexOfSlot(btSimplePair pair) {
        for (int i = 0; i < m_overlappingPairArray.size(); i++) {
            if (m_overlappingPairArray.get(i) == pair) return i;
        }
        throw new IllegalStateException("pair is not a slot of m_overlappingPairArray");
    }
}
