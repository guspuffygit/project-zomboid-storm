// Port of BulletCollision/BroadphaseCollision/btOverlappingPairCache.cpp and
// btOverlappingPairCache.h (Bullet 2.82), class btHashedOverlappingPairCache.
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btIntArray;

/**
 * Hash-space based Pair Cache, thanks to Erin Catto, Box2D, http://www.box2d.org, and Pierre
 * Terdiman, Codercorner, http://codercorner.com.
 *
 * <p>Pairs are stored by value in {@link #m_overlappingPairArray}; a returned {@code
 * btBroadphasePair} is the array slot (C++ {@code &m_overlappingPairArray[i]}). The hash uses proxy
 * {@code m_uniqueId}s only, never pointers.
 */
public class btHashedOverlappingPairCache extends btOverlappingPairCache {
    // private in C++ (public here so tests and other ports can inspect it)
    public final btAlignedObjectArray<btBroadphasePair> m_overlappingPairArray =
            btBroadphasePair.newArray();
    public btOverlapFilterCallback m_overlapFilterCallback;
    public boolean m_blockedForChanges;

    // protected in C++
    public final btIntArray m_hashTable = new btIntArray();
    public final btIntArray m_next = new btIntArray();
    public btOverlappingPairCallback m_ghostPairCallback;

    public btHashedOverlappingPairCache() {
        m_overlapFilterCallback = null;
        m_blockedForChanges = false;
        m_ghostPairCallback = null;
        int initialAllocatedSize = 2;
        m_overlappingPairArray.reserve(initialAllocatedSize);
        growTables();
    }

    /** Empty user destructor; then the member arrays are destroyed (reverse order). */
    @Override
    public void destroy() {
        m_next.clear();
        m_hashTable.clear();
        m_overlappingPairArray.clear();
        super.destroy();
    }

    public boolean needsBroadphaseCollision(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        if (m_overlapFilterCallback != null)
            return m_overlapFilterCallback.needBroadphaseCollision(proxy0, proxy1);

        boolean collides = (proxy0.m_collisionFilterGroup & proxy1.m_collisionFilterMask) != 0;
        collides =
                collides && ((proxy1.m_collisionFilterGroup & proxy0.m_collisionFilterMask) != 0);

        return collides;
    }

    /**
     * Add a pair and return the new pair. If the pair already exists, no new pair is created and
     * the old one is returned.
     */
    @Override
    public btBroadphasePair addOverlappingPair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        btGlobals.gAddedPairs++;

        if (!needsBroadphaseCollision(proxy0, proxy1)) return null;

        return internalAddPair(proxy0, proxy1);
    }

    @Override
    public void cleanOverlappingPair(btBroadphasePair pair, btDispatcher dispatcher) {
        if (pair.m_algorithm != null && dispatcher != null) {
            {
                pair.m_algorithm.destroy();
                dispatcher.freeCollisionAlgorithm(pair.m_algorithm.m_allocAddress);
                pair.m_algorithm = null;
            }
        }
    }

    /** Local class {@code CleanPairCallback} of cleanProxyFromPairs. */
    private static final class CleanPairCallback extends btOverlapCallback {
        private final btBroadphaseProxy m_cleanProxy;
        private final btOverlappingPairCache m_pairCache;
        private final btDispatcher m_dispatcher;

        CleanPairCallback(
                btBroadphaseProxy cleanProxy,
                btOverlappingPairCache pairCache,
                btDispatcher dispatcher) {
            m_cleanProxy = cleanProxy;
            m_pairCache = pairCache;
            m_dispatcher = dispatcher;
        }

        @Override
        public boolean processOverlap(btBroadphasePair pair) {
            if ((pair.m_pProxy0 == m_cleanProxy) || (pair.m_pProxy1 == m_cleanProxy)) {
                m_pairCache.cleanOverlappingPair(pair, m_dispatcher);
            }
            return false;
        }
    }

    @Override
    public void cleanProxyFromPairs(btBroadphaseProxy proxy, btDispatcher dispatcher) {
        CleanPairCallback cleanPairs = new CleanPairCallback(proxy, this, dispatcher);

        processAllOverlappingPairs(cleanPairs, dispatcher);
    }

    /** Local class {@code RemovePairCallback} of removeOverlappingPairsContainingProxy. */
    private static final class RemovePairCallback extends btOverlapCallback {
        private final btBroadphaseProxy m_obsoleteProxy;

        RemovePairCallback(btBroadphaseProxy obsoleteProxy) {
            m_obsoleteProxy = obsoleteProxy;
        }

        @Override
        public boolean processOverlap(btBroadphasePair pair) {
            return ((pair.m_pProxy0 == m_obsoleteProxy) || (pair.m_pProxy1 == m_obsoleteProxy));
        }
    }

    @Override
    public void removeOverlappingPairsContainingProxy(
            btBroadphaseProxy proxy, btDispatcher dispatcher) {
        RemovePairCallback removeCallback = new RemovePairCallback(proxy);

        processAllOverlappingPairs(removeCallback, dispatcher);
    }

    @Override
    public btBroadphasePair findPair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        btGlobals.gFindPairs++;
        if (proxy0.m_uniqueId > proxy1.m_uniqueId) {
            btBroadphaseProxy tmp = proxy0;
            proxy0 = proxy1;
            proxy1 = tmp;
        }
        int proxyId1 = proxy0.getUid();
        int proxyId2 = proxy1.getUid();

        int hash = getHash(proxyId1, proxyId2) & (m_overlappingPairArray.capacity() - 1);

        if (hash >= m_hashTable.size()) {
            return null;
        }

        int index = m_hashTable.get(hash);
        while (index != BT_NULL_PAIR
                && equalsPair(m_overlappingPairArray.get(index), proxyId1, proxyId2) == false) {
            index = m_next.get(index);
        }

        if (index == BT_NULL_PAIR) {
            return null;
        }

        return m_overlappingPairArray.get(index);
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
                m_hashTable.set(i, BT_NULL_PAIR);
            }
            for (i = 0; i < newCapacity; ++i) {
                m_next.set(i, BT_NULL_PAIR);
            }

            for (i = 0; i < curHashtableSize; i++) {
                final btBroadphasePair pair = m_overlappingPairArray.get(i);
                int proxyId1 = pair.m_pProxy0.getUid();
                int proxyId2 = pair.m_pProxy1.getUid();
                // New hash value with new mask
                int hashValue =
                        getHash(proxyId1, proxyId2) & (m_overlappingPairArray.capacity() - 1);
                m_next.set(i, m_hashTable.get(hashValue));
                m_hashTable.set(hashValue, i);
            }
        }
    }

    private btBroadphasePair internalAddPair(btBroadphaseProxy proxy0, btBroadphaseProxy proxy1) {
        if (proxy0.m_uniqueId > proxy1.m_uniqueId) {
            btBroadphaseProxy tmp = proxy0;
            proxy0 = proxy1;
            proxy1 = tmp;
        }
        int proxyId1 = proxy0.getUid();
        int proxyId2 = proxy1.getUid();

        // New hash value with new mask
        int hash = getHash(proxyId1, proxyId2) & (m_overlappingPairArray.capacity() - 1);

        btBroadphasePair pair = internalFindPair(proxy0, proxy1, hash);
        if (pair != null) {
            return pair;
        }

        int count = m_overlappingPairArray.size();
        int oldCapacity = m_overlappingPairArray.capacity();
        btBroadphasePair mem = m_overlappingPairArray.expandNonInitializing();

        // this is where we add an actual pair, so also call the 'ghost'
        if (m_ghostPairCallback != null) m_ghostPairCallback.addOverlappingPair(proxy0, proxy1);

        int newCapacity = m_overlappingPairArray.capacity();

        if (oldCapacity < newCapacity) {
            growTables();
            // hash with new capacity
            hash = getHash(proxyId1, proxyId2) & (m_overlappingPairArray.capacity() - 1);
        }

        pair = mem.init(proxy0, proxy1);
        pair.m_algorithm = null;
        pair.m_internalTmpValue = 0;

        m_next.set(count, m_hashTable.get(hash));
        m_hashTable.set(hash, count);

        return pair;
    }

    @Override
    public Object removeOverlappingPair(
            btBroadphaseProxy proxy0, btBroadphaseProxy proxy1, btDispatcher dispatcher) {
        btGlobals.gRemovePairs++;
        if (proxy0.m_uniqueId > proxy1.m_uniqueId) {
            btBroadphaseProxy tmp = proxy0;
            proxy0 = proxy1;
            proxy1 = tmp;
        }
        int proxyId1 = proxy0.getUid();
        int proxyId2 = proxy1.getUid();

        int hash = getHash(proxyId1, proxyId2) & (m_overlappingPairArray.capacity() - 1);

        // internalFindPair, keeping the index (C++ recovers it as pair - &array[0] below)
        int foundIndex = internalFindPairIndex(proxy0, proxy1, hash);
        if (foundIndex == BT_NULL_PAIR) {
            return null;
        }
        btBroadphasePair pair = m_overlappingPairArray.get(foundIndex);

        cleanOverlappingPair(pair, dispatcher);

        Object userData = pair.m_internalInfo1;

        int pairIndex = foundIndex;

        // Remove the pair from the hash table.
        int index = m_hashTable.get(hash);

        int previous = BT_NULL_PAIR;
        while (index != pairIndex) {
            previous = index;
            index = m_next.get(index);
        }

        if (previous != BT_NULL_PAIR) {
            m_next.set(previous, m_next.get(pairIndex));
        } else {
            m_hashTable.set(hash, m_next.get(pairIndex));
        }

        // We now move the last pair into spot of the pair being removed. We need to fix the hash
        // table indices to support the move.

        int lastPairIndex = m_overlappingPairArray.size() - 1;

        if (m_ghostPairCallback != null)
            m_ghostPairCallback.removeOverlappingPair(proxy0, proxy1, dispatcher);

        // If the removed pair is the last pair, we are done.
        if (lastPairIndex == pairIndex) {
            m_overlappingPairArray.pop_back();
            return userData;
        }

        // Remove the last pair from the hash table.
        final btBroadphasePair last = m_overlappingPairArray.get(lastPairIndex);
        /* missing swap here too, Nat. */
        int lastHash =
                getHash(last.m_pProxy0.getUid(), last.m_pProxy1.getUid())
                        & (m_overlappingPairArray.capacity() - 1);

        index = m_hashTable.get(lastHash);

        previous = BT_NULL_PAIR;
        while (index != lastPairIndex) {
            previous = index;
            index = m_next.get(index);
        }

        if (previous != BT_NULL_PAIR) {
            m_next.set(previous, m_next.get(lastPairIndex));
        } else {
            m_hashTable.set(lastHash, m_next.get(lastPairIndex));
        }

        // Copy the last pair into the remove pair's spot.
        m_overlappingPairArray.set(pairIndex, m_overlappingPairArray.get(lastPairIndex));

        // Insert the last pair into the hash table
        m_next.set(pairIndex, m_hashTable.get(lastHash));
        m_hashTable.set(lastHash, pairIndex);

        m_overlappingPairArray.pop_back();

        return userData;
    }

    @Override
    public void processAllOverlappingPairs(btOverlapCallback callback, btDispatcher dispatcher) {
        int i;

        for (i = 0; i < m_overlappingPairArray.size(); ) {
            btBroadphasePair pair = m_overlappingPairArray.get(i);
            if (callback.processOverlap(pair)) {
                removeOverlappingPair(pair.m_pProxy0, pair.m_pProxy1, dispatcher);

                btGlobals.gOverlappingPairs--;
            } else {
                i++;
            }
        }
    }

    @Override
    public btAlignedObjectArray<btBroadphasePair> getOverlappingPairArrayPtr() {
        return m_overlappingPairArray;
    }

    @Override
    public btAlignedObjectArray<btBroadphasePair> getOverlappingPairArray() {
        return m_overlappingPairArray;
    }

    public int GetCount() {
        return m_overlappingPairArray.size();
    }

    public btOverlapFilterCallback getOverlapFilterCallback() {
        return m_overlapFilterCallback;
    }

    @Override
    public void setOverlapFilterCallback(btOverlapFilterCallback callback) {
        m_overlapFilterCallback = callback;
    }

    @Override
    public int getNumOverlappingPairs() {
        return m_overlappingPairArray.size();
    }

    private boolean equalsPair(btBroadphasePair pair, int proxyId1, int proxyId2) {
        return pair.m_pProxy0.getUid() == proxyId1 && pair.m_pProxy1.getUid() == proxyId2;
    }

    /**
     * {@code unsigned int getHash(unsigned int proxyId1, unsigned int proxyId2)}. The arithmetic is
     * on a signed {@code int key}, so {@code >>} is an arithmetic shift. The unsigned result is
     * then {@code & (capacity-1)} and cast to int by every caller, so returning the int bits is
     * equivalent.
     */
    public static int getHash(int proxyId1, int proxyId2) {
        int key = proxyId1 | (proxyId2 << 16);
        // Thomas Wang's hash

        key += ~(key << 15);
        key ^= (key >> 10);
        key += (key << 3);
        key ^= (key >> 6);
        key += ~(key << 11);
        key ^= (key >> 16);
        return key;
    }

    private btBroadphasePair internalFindPair(
            btBroadphaseProxy proxy0, btBroadphaseProxy proxy1, int hash) {
        int index = internalFindPairIndex(proxy0, proxy1, hash);
        if (index == BT_NULL_PAIR) {
            return null;
        }
        return m_overlappingPairArray.get(index);
    }

    /** Body of internalFindPair returning the index (or BT_NULL_PAIR) instead of the pointer. */
    private int internalFindPairIndex(
            btBroadphaseProxy proxy0, btBroadphaseProxy proxy1, int hash) {
        int proxyId1 = proxy0.getUid();
        int proxyId2 = proxy1.getUid();

        int index = m_hashTable.get(hash);

        while (index != BT_NULL_PAIR
                && equalsPair(m_overlappingPairArray.get(index), proxyId1, proxyId2) == false) {
            index = m_next.get(index);
        }

        return index;
    }

    @Override
    public boolean hasDeferredRemoval() {
        return false;
    }

    @Override
    public void setInternalGhostPairCallback(btOverlappingPairCallback ghostPairCallback) {
        m_ghostPairCallback = ghostPairCallback;
    }

    @Override
    public void sortOverlappingPairs(btDispatcher dispatcher) {
        /// need to keep hashmap in sync with pair address, so rebuild all
        btAlignedObjectArray<btBroadphasePair> tmpPairs = btBroadphasePair.newArray();
        int i;
        for (i = 0; i < m_overlappingPairArray.size(); i++) {
            tmpPairs.push_back(m_overlappingPairArray.get(i));
        }

        for (i = 0; i < tmpPairs.size(); i++) {
            removeOverlappingPair(tmpPairs.get(i).m_pProxy0, tmpPairs.get(i).m_pProxy1, dispatcher);
        }

        for (i = 0; i < m_next.size(); i++) {
            m_next.set(i, BT_NULL_PAIR);
        }

        tmpPairs.quickSort(new btBroadphasePairSortPredicate());

        for (i = 0; i < tmpPairs.size(); i++) {
            addOverlappingPair(tmpPairs.get(i).m_pProxy0, tmpPairs.get(i).m_pProxy1);
        }

        // ~btBroadphasePairArray() of the local
        tmpPairs.clear();
    }
}
