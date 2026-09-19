// Port of BulletCollision/BroadphaseCollision/btDbvtBroadphase.cpp and btDbvtBroadphase.h
// (Bullet 2.82), struct btDbvtBroadphase with its file-local helpers (listappend, listremove,
// listcount) and colliders (btDbvtTreeCollider, BroadphaseRayTester, BroadphaseAabbTester).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The btDbvtBroadphase implements a broadphase using two dynamic AABB bounding volume
 * hierarchies/trees (see btDbvt). One tree is used for static/non-moving objects, and another tree
 * is used for dynamic objects. Objects can move from one tree to the other.
 *
 * <p>Build configuration: DBVT_BP_PROFILE 0, DBVT_BP_SORTPAIRS undefined,
 * DBVT_BP_PREVENTFALSEUPDATE 0, DBVT_BP_ACCURATESLEEPING 0, DBVT_BP_MARGIN 0.05. benchmark() is not
 * compiled (DBVT_BP_ENABLE_BENCHMARK 0).
 */
public class btDbvtBroadphase extends btBroadphaseInterface {
    public static final double DBVT_BP_MARGIN = 0.05;

    /** sizeof(btHashedOverlappingPairCache) in the binary (only feeds the emulated heap). */
    public static final int SIZEOF_HASHED_PAIR_CACHE = 128;

    /* Config */
    public static final int DYNAMIC_SET = 0; /* Dynamic set index */
    public static final int FIXED_SET = 1; /* Fixed set index */
    public static final int STAGECOUNT = 2; /* Number of stages */

    /* Fields */
    public final btDbvt[] m_sets = {new btDbvt(), new btDbvt()}; // Dbvt sets
    public final btDbvtProxy[] m_stageRoots = new btDbvtProxy[STAGECOUNT + 1]; // Stages list
    public btOverlappingPairCache m_paircache; // Pair cache
    public double m_prediction; // Velocity prediction
    public int m_stageCurrent; // Current stage
    public int m_fupdates; // % of fixed updates per frame
    public int m_dupdates; // % of dynamic updates per frame
    public int m_cupdates; // % of cleanup updates per frame
    public int m_newpairs; // Number of pairs created
    public int m_fixedleft; // Fixed optimization left

    /** unsigned in C++ */
    public int m_updates_call; // Number of updates call

    /** unsigned in C++ */
    public int m_updates_done; // Number of updates done

    public double m_updates_ratio; // m_updates_done/m_updates_call
    public int m_pid; // Parse id
    public int m_cid; // Cleanup index
    public int m_gid; // Gen id
    public boolean m_releasepaircache; // Release pair cache on delete
    public boolean m_deferedcollide; // Defere dynamic/static collision to collide call
    public boolean m_needcleanup; // Need to run cleanup?

    /** Emulated address of an owned pair cache (not C++). */
    public long m_paircacheAddress;

    // ---- Helpers ----

    static void listappend(btDbvtProxy item, btDbvtProxy[] list, int idx) {
        item.links[0] = null;
        item.links[1] = list[idx];
        if (list[idx] != null) list[idx].links[0] = item;
        list[idx] = item;
    }

    static void listremove(btDbvtProxy item, btDbvtProxy[] list, int idx) {
        if (item.links[0] != null) item.links[0].links[1] = item.links[1];
        else list[idx] = item.links[1];
        if (item.links[1] != null) item.links[1].links[0] = item.links[0];
    }

    static int listcount(btDbvtProxy root) {
        int n = 0;
        while (root != null) {
            ++n;
            root = root.links[1];
        }
        return (n);
    }

    // ---- Colliders ----

    /** Tree collider */
    public static class btDbvtTreeCollider extends btDbvt.ICollide {
        public btDbvtBroadphase pbp;
        public btDbvtProxy proxy;

        public btDbvtTreeCollider(btDbvtBroadphase p) {
            pbp = p;
        }

        @Override
        public void Process(btDbvtNode na, btDbvtNode nb) {
            if (na != nb) {
                btDbvtProxy pa = (btDbvtProxy) na.data;
                btDbvtProxy pb = (btDbvtProxy) nb.data;
                pbp.m_paircache.addOverlappingPair(pa, pb);
                ++pbp.m_newpairs;
            }
        }

        @Override
        public void Process(btDbvtNode n) {
            Process(n, proxy.leaf);
        }
    }

    public static class BroadphaseRayTester extends btDbvt.ICollide {
        public final btBroadphaseRayCallback m_rayCallback;

        public BroadphaseRayTester(btBroadphaseRayCallback orgCallback) {
            m_rayCallback = orgCallback;
        }

        @Override
        public void Process(btDbvtNode leaf) {
            btDbvtProxy proxy = (btDbvtProxy) leaf.data;
            m_rayCallback.process(proxy);
        }
    }

    public static class BroadphaseAabbTester extends btDbvt.ICollide {
        public final btBroadphaseAabbCallback m_aabbCallback;

        public BroadphaseAabbTester(btBroadphaseAabbCallback orgCallback) {
            m_aabbCallback = orgCallback;
        }

        @Override
        public void Process(btDbvtNode leaf) {
            btDbvtProxy proxy = (btDbvtProxy) leaf.data;
            m_aabbCallback.process(proxy);
        }
    }

    // ---- btDbvtBroadphase ----

    public btDbvtBroadphase() {
        this(null);
    }

    public btDbvtBroadphase(btOverlappingPairCache paircache) {
        m_deferedcollide = false;
        m_needcleanup = true;
        m_releasepaircache = (paircache != null) ? false : true;
        m_prediction = 0;
        m_stageCurrent = 0;
        m_fixedleft = 0;
        m_fupdates = 1;
        m_dupdates = 0;
        m_cupdates = 10;
        m_newpairs = 1;
        m_updates_call = 0;
        m_updates_done = 0;
        m_updates_ratio = 0;
        if (paircache != null) {
            m_paircache = paircache;
        } else {
            m_paircacheAddress = btGlobals.btAlignedAlloc(SIZEOF_HASHED_PAIR_CACHE, 16);
            m_paircache = new btHashedOverlappingPairCache();
        }
        m_gid = 0;
        m_pid = 0;
        m_cid = 0;
        for (int i = 0; i <= STAGECOUNT; ++i) {
            m_stageRoots[i] = null;
        }
    }

    /** ~btDbvtBroadphase(), then the member destructors of m_sets[1], m_sets[0]. */
    @Override
    public void destroy() {
        if (m_releasepaircache) {
            m_paircache.destroy();
            btGlobals.btAlignedFree(m_paircacheAddress);
        }
        m_sets[1].destroy();
        m_sets[0].destroy();
        super.destroy();
    }

    @Override
    public btBroadphaseProxy createProxy(
            btVector3 aabbMin,
            btVector3 aabbMax,
            int shapeType,
            Object userPtr,
            short collisionFilterGroup,
            short collisionFilterMask,
            btDispatcher dispatcher,
            Object multiSapProxy) {
        long addr = btGlobals.btAlignedAlloc(btDbvtProxy.SIZEOF, 16);
        btDbvtProxy proxy =
                new btDbvtProxy(
                        aabbMin, aabbMax, userPtr, collisionFilterGroup, collisionFilterMask);
        proxy.addr = addr;

        btDbvtAabbMm aabb = btDbvtAabbMm.FromMM(aabbMin, aabbMax);

        proxy.stage = m_stageCurrent;
        proxy.m_uniqueId = ++m_gid;
        proxy.leaf = m_sets[0].insert(aabb, proxy);
        listappend(proxy, m_stageRoots, m_stageCurrent);
        if (!m_deferedcollide) {
            btDbvtTreeCollider collider = new btDbvtTreeCollider(this);
            collider.proxy = proxy;
            m_sets[0].collideTV(m_sets[0].m_root, aabb, collider);
            m_sets[1].collideTV(m_sets[1].m_root, aabb, collider);
        }
        return (proxy);
    }

    @Override
    public void destroyProxy(btBroadphaseProxy absproxy, btDispatcher dispatcher) {
        btDbvtProxy proxy = (btDbvtProxy) absproxy;
        if (proxy.stage == STAGECOUNT) m_sets[1].remove(proxy.leaf);
        else m_sets[0].remove(proxy.leaf);
        listremove(proxy, m_stageRoots, proxy.stage);
        m_paircache.removeOverlappingPairsContainingProxy(proxy, dispatcher);
        btGlobals.btAlignedFree(proxy.addr);
        m_needcleanup = true;
    }

    @Override
    public void getAabb(btBroadphaseProxy absproxy, btVector3 aabbMin, btVector3 aabbMax) {
        btDbvtProxy proxy = (btDbvtProxy) absproxy;
        aabbMin.set(proxy.m_aabbMin);
        aabbMax.set(proxy.m_aabbMax);
    }

    @Override
    public void rayTest(
            btVector3 rayFrom,
            btVector3 rayTo,
            btBroadphaseRayCallback rayCallback,
            btVector3 aabbMin,
            btVector3 aabbMax) {
        BroadphaseRayTester callback = new BroadphaseRayTester(rayCallback);

        m_sets[0].rayTestInternal(
                m_sets[0].m_root,
                rayFrom,
                rayTo,
                rayCallback.m_rayDirectionInverse,
                rayCallback.m_signs,
                rayCallback.m_lambda_max,
                aabbMin,
                aabbMax,
                callback);

        m_sets[1].rayTestInternal(
                m_sets[1].m_root,
                rayFrom,
                rayTo,
                rayCallback.m_rayDirectionInverse,
                rayCallback.m_signs,
                rayCallback.m_lambda_max,
                aabbMin,
                aabbMax,
                callback);
    }

    @Override
    public void aabbTest(
            btVector3 aabbMin, btVector3 aabbMax, btBroadphaseAabbCallback aabbCallback) {
        BroadphaseAabbTester callback = new BroadphaseAabbTester(aabbCallback);

        final btDbvtAabbMm bounds = btDbvtAabbMm.FromMM(aabbMin, aabbMax);
        // process all children, that overlap with  the given AABB bounds
        m_sets[0].collideTV(m_sets[0].m_root, bounds, callback);
        m_sets[1].collideTV(m_sets[1].m_root, bounds, callback);
    }

    @Override
    public void setAabb(
            btBroadphaseProxy absproxy,
            btVector3 aabbMin,
            btVector3 aabbMax,
            btDispatcher dispatcher) {
        btDbvtProxy proxy = (btDbvtProxy) absproxy;
        btDbvtAabbMm aabb = btDbvtAabbMm.FromMM(aabbMin, aabbMax);
        {
            boolean docollide = false;
            if (proxy.stage == STAGECOUNT) {
                /* fixed -> dynamic set */
                m_sets[1].remove(proxy.leaf);
                proxy.leaf = m_sets[0].insert(aabb, proxy);
                docollide = true;
            } else {
                /* dynamic set */
                ++m_updates_call;
                if (btDbvtAabbMm.Intersect(proxy.leaf.volume, aabb)) {
                    /* Moving */
                    final btVector3 delta = aabbMin.sub(proxy.m_aabbMin);
                    btVector3 velocity =
                            new btVector3(
                                    ((proxy.m_aabbMax.sub(proxy.m_aabbMin)).div(2))
                                            .mul(m_prediction));
                    if (delta.get(0) < 0) velocity.set(0, -velocity.get(0));
                    if (delta.get(1) < 0) velocity.set(1, -velocity.get(1));
                    if (delta.get(2) < 0) velocity.set(2, -velocity.get(2));
                    if (m_sets[0].update(proxy.leaf, aabb, velocity, DBVT_BP_MARGIN)) {
                        ++m_updates_done;
                        docollide = true;
                    }
                } else {
                    /* Teleporting */
                    m_sets[0].update(proxy.leaf, aabb);
                    ++m_updates_done;
                    docollide = true;
                }
            }
            listremove(proxy, m_stageRoots, proxy.stage);
            proxy.m_aabbMin.set(aabbMin);
            proxy.m_aabbMax.set(aabbMax);
            proxy.stage = m_stageCurrent;
            listappend(proxy, m_stageRoots, m_stageCurrent);
            if (docollide) {
                m_needcleanup = true;
                if (!m_deferedcollide) {
                    btDbvtTreeCollider collider = new btDbvtTreeCollider(this);
                    m_sets[1].collideTTpersistentStack(m_sets[1].m_root, proxy.leaf, collider);
                    m_sets[0].collideTTpersistentStack(m_sets[0].m_root, proxy.leaf, collider);
                }
            }
        }
    }

    /**
     * this setAabbForceUpdate is similar to setAabb but always forces the aabb update. it is not
     * part of the btBroadphaseInterface but specific to btDbvtBroadphase.
     */
    public void setAabbForceUpdate(
            btBroadphaseProxy absproxy,
            btVector3 aabbMin,
            btVector3 aabbMax,
            btDispatcher dispatcher) {
        btDbvtProxy proxy = (btDbvtProxy) absproxy;
        btDbvtAabbMm aabb = btDbvtAabbMm.FromMM(aabbMin, aabbMax);
        boolean docollide = false;
        if (proxy.stage == STAGECOUNT) {
            /* fixed -> dynamic set */
            m_sets[1].remove(proxy.leaf);
            proxy.leaf = m_sets[0].insert(aabb, proxy);
            docollide = true;
        } else {
            /* dynamic set */
            ++m_updates_call;
            /* Teleporting */
            m_sets[0].update(proxy.leaf, aabb);
            ++m_updates_done;
            docollide = true;
        }
        listremove(proxy, m_stageRoots, proxy.stage);
        proxy.m_aabbMin.set(aabbMin);
        proxy.m_aabbMax.set(aabbMax);
        proxy.stage = m_stageCurrent;
        listappend(proxy, m_stageRoots, m_stageCurrent);
        if (docollide) {
            m_needcleanup = true;
            if (!m_deferedcollide) {
                btDbvtTreeCollider collider = new btDbvtTreeCollider(this);
                m_sets[1].collideTTpersistentStack(m_sets[1].m_root, proxy.leaf, collider);
                m_sets[0].collideTTpersistentStack(m_sets[0].m_root, proxy.leaf, collider);
            }
        }
    }

    @Override
    public void calculateOverlappingPairs(btDispatcher dispatcher) {
        collide(dispatcher);
        performDeferredRemoval(dispatcher);
    }

    public void performDeferredRemoval(btDispatcher dispatcher) {
        if (m_paircache.hasDeferredRemoval()) {
            btAlignedObjectArray<btBroadphasePair> overlappingPairArray =
                    m_paircache.getOverlappingPairArray();

            // perform a sort, to find duplicates and to sort 'invalid' pairs to the end
            overlappingPairArray.quickSort(btBroadphasePairSortPredicate.INSTANCE);

            int invalidPair = 0;

            int i;

            btBroadphasePair previousPair = new btBroadphasePair();
            previousPair.m_pProxy0 = null;
            previousPair.m_pProxy1 = null;
            previousPair.m_algorithm = null;

            for (i = 0; i < overlappingPairArray.size(); i++) {
                btBroadphasePair pair = overlappingPairArray.get(i);

                boolean isDuplicate = btBroadphasePair.equals(pair, previousPair);

                previousPair.set(pair);

                boolean needsRemoval = false;

                if (!isDuplicate) {
                    // important to perform AABB check that is consistent with the broadphase
                    btDbvtProxy pa = (btDbvtProxy) pair.m_pProxy0;
                    btDbvtProxy pb = (btDbvtProxy) pair.m_pProxy1;
                    boolean hasOverlap = btDbvtAabbMm.Intersect(pa.leaf.volume, pb.leaf.volume);

                    if (hasOverlap) {
                        needsRemoval = false;
                    } else {
                        needsRemoval = true;
                    }
                } else {
                    // remove duplicate
                    needsRemoval = true;
                    // should have no algorithm
                }

                if (needsRemoval) {
                    m_paircache.cleanOverlappingPair(pair, dispatcher);

                    pair.m_pProxy0 = null;
                    pair.m_pProxy1 = null;
                    invalidPair++;
                }
            }

            // perform a sort, to sort 'invalid' pairs to the end
            overlappingPairArray.quickSort(btBroadphasePairSortPredicate.INSTANCE);

            overlappingPairArray.resize(overlappingPairArray.size() - invalidPair);
        }
    }

    public void collide(btDispatcher dispatcher) {
        /* optimize */
        m_sets[0].optimizeIncremental(1 + (m_sets[0].m_leaves * m_dupdates) / 100);
        if (m_fixedleft != 0) {
            final int count = 1 + (m_sets[1].m_leaves * m_fupdates) / 100;
            m_sets[1].optimizeIncremental(1 + (m_sets[1].m_leaves * m_fupdates) / 100);
            m_fixedleft = Math.max(0, m_fixedleft - count);
        }
        /* dynamic -> fixed set */
        m_stageCurrent = (m_stageCurrent + 1) % STAGECOUNT;
        btDbvtProxy current = m_stageRoots[m_stageCurrent];
        if (current != null) {
            btDbvtTreeCollider collider = new btDbvtTreeCollider(this);
            do {
                btDbvtProxy next = current.links[1];
                listremove(current, m_stageRoots, current.stage);
                listappend(current, m_stageRoots, STAGECOUNT);
                m_sets[0].remove(current.leaf);
                btDbvtAabbMm curAabb = btDbvtAabbMm.FromMM(current.m_aabbMin, current.m_aabbMax);
                current.leaf = m_sets[1].insert(curAabb, current);
                current.stage = STAGECOUNT;
                current = next;
            } while (current != null);
            m_fixedleft = m_sets[1].m_leaves;
            m_needcleanup = true;
        }
        /* collide dynamics */
        {
            btDbvtTreeCollider collider = new btDbvtTreeCollider(this);
            if (m_deferedcollide) {
                m_sets[0].collideTTpersistentStack(m_sets[0].m_root, m_sets[1].m_root, collider);
            }
            if (m_deferedcollide) {
                m_sets[0].collideTTpersistentStack(m_sets[0].m_root, m_sets[0].m_root, collider);
            }
        }
        /* clean up */
        if (m_needcleanup) {
            btAlignedObjectArray<btBroadphasePair> pairs = m_paircache.getOverlappingPairArray();
            if (pairs.size() > 0) {
                int ni =
                        Math.min(
                                pairs.size(),
                                Math.max(m_newpairs, (pairs.size() * m_cupdates) / 100));
                for (int i = 0; i < ni; ++i) {
                    btBroadphasePair p = pairs.get((m_cid + i) % pairs.size());
                    btDbvtProxy pa = (btDbvtProxy) p.m_pProxy0;
                    btDbvtProxy pb = (btDbvtProxy) p.m_pProxy1;
                    if (!btDbvtAabbMm.Intersect(pa.leaf.volume, pb.leaf.volume)) {
                        m_paircache.removeOverlappingPair(pa, pb, dispatcher);
                        --ni;
                        --i;
                    }
                }
                if (pairs.size() > 0) m_cid = (m_cid + ni) % pairs.size();
                else m_cid = 0;
            }
        }
        ++m_pid;
        m_newpairs = 1;
        m_needcleanup = false;
        if (m_updates_call != 0) {
            m_updates_ratio =
                    (double) Integer.toUnsignedLong(m_updates_done)
                            / (double) Integer.toUnsignedLong(m_updates_call);
        } else {
            m_updates_ratio = 0;
        }
        m_updates_done >>>= 1;
        m_updates_call >>>= 1;
    }

    public void optimize() {
        m_sets[0].optimizeTopDown();
        m_sets[1].optimizeTopDown();
    }

    @Override
    public btOverlappingPairCache getOverlappingPairCache() {
        return (m_paircache);
    }

    @Override
    public void getBroadphaseAabb(btVector3 aabbMin, btVector3 aabbMax) {
        btDbvtAabbMm bounds = new btDbvtAabbMm();

        if (!m_sets[0].empty())
            if (!m_sets[1].empty())
                btDbvtAabbMm.Merge(m_sets[0].m_root.volume, m_sets[1].m_root.volume, bounds);
            else bounds.set(m_sets[0].m_root.volume);
        else if (!m_sets[1].empty()) bounds.set(m_sets[1].m_root.volume);
        else bounds.set(btDbvtAabbMm.FromCR(new btVector3(0, 0, 0), 0));
        aabbMin.set(bounds.Mins());
        aabbMax.set(bounds.Maxs());
    }

    /** reset broadphase internal structures, to ensure determinism/reproducability */
    @Override
    public void resetPool(btDispatcher dispatcher) {
        int totalObjects = m_sets[0].m_leaves + m_sets[1].m_leaves;
        if (totalObjects == 0) {
            // reset internal dynamic tree data structures
            m_sets[0].clear();
            m_sets[1].clear();

            m_deferedcollide = false;
            m_needcleanup = true;
            m_stageCurrent = 0;
            m_fixedleft = 0;
            m_fupdates = 1;
            m_dupdates = 0;
            m_cupdates = 10;
            m_newpairs = 1;
            m_updates_call = 0;
            m_updates_done = 0;
            m_updates_ratio = 0;

            m_gid = 0;
            m_pid = 0;
            m_cid = 0;
            for (int i = 0; i <= STAGECOUNT; ++i) {
                m_stageRoots[i] = null;
            }
        }
    }

    @Override
    public void printStats() {}

    public void setVelocityPrediction(double prediction) {
        m_prediction = prediction;
    }

    public double getVelocityPrediction() {
        return m_prediction;
    }
}
