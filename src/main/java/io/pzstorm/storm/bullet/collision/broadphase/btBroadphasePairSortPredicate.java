// Port of BulletCollision/BroadphaseCollision/btBroadphaseProxy.h (Bullet 2.82), class
// btBroadphasePairSortPredicate (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import java.util.function.BiPredicate;

/**
 * {@code operator()(a, b)}. The third clause compares {@code a.m_algorithm > b.m_algorithm} as
 * pointers: PTR-ORDER, emulated with {@link btCollisionAlgorithm#m_allocAddress} (the address the
 * dispatcher's pool/btAlignedAlloc returned; null = address 0) compared unsigned. See
 * docs/re-bullet/pointer-order.md, section "btBroadphasePairSortPredicate".
 */
public class btBroadphasePairSortPredicate
        implements BiPredicate<btBroadphasePair, btBroadphasePair> {
    public static final btBroadphasePairSortPredicate INSTANCE =
            new btBroadphasePairSortPredicate();

    private static long algorithmAddress(btCollisionAlgorithm a) {
        return a == null ? 0L : a.m_allocAddress;
    }

    @Override
    public boolean test(btBroadphasePair a, btBroadphasePair b) {
        final int uidA0 = a.m_pProxy0 != null ? a.m_pProxy0.m_uniqueId : -1;
        final int uidB0 = b.m_pProxy0 != null ? b.m_pProxy0.m_uniqueId : -1;
        final int uidA1 = a.m_pProxy1 != null ? a.m_pProxy1.m_uniqueId : -1;
        final int uidB1 = b.m_pProxy1 != null ? b.m_pProxy1.m_uniqueId : -1;

        return uidA0 > uidB0
                || (a.m_pProxy0 == b.m_pProxy0 && uidA1 > uidB1)
                || (a.m_pProxy0 == b.m_pProxy0
                        && a.m_pProxy1 == b.m_pProxy1
                        && Long.compareUnsigned(
                                        algorithmAddress(a.m_algorithm),
                                        algorithmAddress(b.m_algorithm))
                                > 0);
    }
}
