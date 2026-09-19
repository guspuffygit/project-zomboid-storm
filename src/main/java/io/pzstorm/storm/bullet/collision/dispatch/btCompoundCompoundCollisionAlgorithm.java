package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvt;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtAabbMm;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtNode;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82
 * BulletCollision/CollisionDispatch/btCompoundCompoundCollisionAlgorithm.{h,cpp}.
 *
 * <p>Supports collision between two btCompoundCollisionShape shapes. 2.82 quirks reproduced: the
 * {@code isSwapped} ctor argument is ignored, and {@code m_compoundShapeRevision0/1} are never
 * updated after construction (so after a compound shape changes, every processCollision clears the
 * cache). The global {@code gCompoundCompoundChildShapePairCallback} is {@link
 * btGlobals#gCompoundCompoundChildShapePairCallback} (holds a {@link btShapePairCallback}).
 */
public class btCompoundCompoundCollisionAlgorithm extends btActivatingCollisionAlgorithm {
    /** sizeof(btCompoundCompoundCollisionAlgorithm) on x86-64. */
    public static final int SIZEOF = 0x50;

    public btHashedSimplePairCache m_childCollisionAlgorithmCache;

    /** fake address of the btAlignedAlloc'ed cache object. */
    public long m_childCollisionAlgorithmCacheAddress;

    public final btAlignedObjectArray<btSimplePair> m_removePairs =
            new btAlignedObjectArray<>(btSimplePair::new, btSimplePair::set);

    public btPersistentManifold m_sharedManifold;
    public boolean m_ownsManifold;

    /** to keep track of changes, so that childAlgorithm array can be updated */
    public int m_compoundShapeRevision0;

    public int m_compoundShapeRevision1;

    public btCompoundCompoundCollisionAlgorithm(
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            boolean isSwapped) {
        super(ci, body0Wrap, body1Wrap);
        m_sharedManifold = ci.m_manifold;
        m_ownsManifold = false;

        long ptr = btGlobals.btAlignedAlloc(btHashedSimplePairCache.SIZEOF, 16);
        m_childCollisionAlgorithmCache = new btHashedSimplePairCache();
        m_childCollisionAlgorithmCacheAddress = ptr;

        btCollisionObjectWrapper col0ObjWrap = body0Wrap;

        btCollisionObjectWrapper col1ObjWrap = body1Wrap;

        btCompoundShape compoundShape0 = (btCompoundShape) col0ObjWrap.getCollisionShape();
        m_compoundShapeRevision0 = compoundShape0.getUpdateRevision();

        btCompoundShape compoundShape1 = (btCompoundShape) col1ObjWrap.getCollisionShape();
        m_compoundShapeRevision1 = compoundShape1.getUpdateRevision();
    }

    /**
     * {@code ~btCompoundCompoundCollisionAlgorithm()}: body, then member m_removePairs' destructor,
     * then base destructors.
     */
    @Override
    public void destroy() {
        removeChildAlgorithms();
        m_childCollisionAlgorithmCache.destroy();
        btGlobals.btAlignedFree(m_childCollisionAlgorithmCacheAddress);
        m_removePairs.clear();
        super.destroy();
    }

    @Override
    public void getAllContactManifolds(btAlignedObjectArray<btPersistentManifold> manifoldArray) {
        int i;
        btAlignedObjectArray<btSimplePair> pairs =
                m_childCollisionAlgorithmCache.getOverlappingPairArray();
        for (i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).m_userPointer != null) {

                ((btCollisionAlgorithm) pairs.get(i).m_userPointer)
                        .getAllContactManifolds(manifoldArray);
            }
        }
    }

    public void removeChildAlgorithms() {
        btAlignedObjectArray<btSimplePair> pairs =
                m_childCollisionAlgorithmCache.getOverlappingPairArray();

        int numChildren = pairs.size();
        int i;
        for (i = 0; i < numChildren; i++) {
            if (pairs.get(i).m_userPointer != null) {
                btCollisionAlgorithm algo = (btCollisionAlgorithm) pairs.get(i).m_userPointer;
                algo.destroy();
                m_dispatcher.freeCollisionAlgorithm(algo.m_allocAddress);
            }
        }
        m_childCollisionAlgorithmCache.removeAllPairs();
    }

    /** File-local {@code struct btCompoundCompoundLeafCallback : btDbvt::ICollide}. */
    public static class btCompoundCompoundLeafCallback extends btDbvt.ICollide {
        public int m_numOverlapPairs;

        public btCollisionObjectWrapper m_compound0ColObjWrap;
        public btCollisionObjectWrapper m_compound1ColObjWrap;
        public btDispatcher m_dispatcher;
        public btDispatcherInfo m_dispatchInfo;
        public btManifoldResult m_resultOut;

        public btHashedSimplePairCache m_childCollisionAlgorithmCache;

        public btPersistentManifold m_sharedManifold;

        public btCompoundCompoundLeafCallback(
                btCollisionObjectWrapper compound1ObjWrap,
                btCollisionObjectWrapper compound0ObjWrap,
                btDispatcher dispatcher,
                btDispatcherInfo dispatchInfo,
                btManifoldResult resultOut,
                btHashedSimplePairCache childAlgorithmsCache,
                btPersistentManifold sharedManifold) {
            m_compound0ColObjWrap = compound1ObjWrap;
            m_compound1ColObjWrap = compound0ObjWrap;
            m_dispatcher = dispatcher;
            m_dispatchInfo = dispatchInfo;
            m_resultOut = resultOut;
            m_childCollisionAlgorithmCache = childAlgorithmsCache;
            m_sharedManifold = sharedManifold;
            m_numOverlapPairs = 0;
        }

        @Override
        public void Process(btDbvtNode leaf0, btDbvtNode leaf1) {
            m_numOverlapPairs++;

            int childIndex0 = leaf0.dataAsInt;
            int childIndex1 = leaf1.dataAsInt;

            btCompoundShape compoundShape0 =
                    (btCompoundShape) m_compound0ColObjWrap.getCollisionShape();

            btCompoundShape compoundShape1 =
                    (btCompoundShape) m_compound1ColObjWrap.getCollisionShape();

            btCollisionShape childShape0 = compoundShape0.getChildShape(childIndex0);
            btCollisionShape childShape1 = compoundShape1.getChildShape(childIndex1);

            // backup
            btTransform orgTrans0 = new btTransform(m_compound0ColObjWrap.getWorldTransform());
            btTransform childTrans0 = compoundShape0.getChildTransform(childIndex0);
            btTransform newChildWorldTrans0 = orgTrans0.mul(childTrans0);

            btTransform orgTrans1 = new btTransform(m_compound1ColObjWrap.getWorldTransform());
            btTransform childTrans1 = compoundShape1.getChildTransform(childIndex1);
            btTransform newChildWorldTrans1 = orgTrans1.mul(childTrans1);

            // perform an AABB check first
            btVector3 aabbMin0 = new btVector3(),
                    aabbMax0 = new btVector3(),
                    aabbMin1 = new btVector3(),
                    aabbMax1 = new btVector3();
            childShape0.getAabb(newChildWorldTrans0, aabbMin0, aabbMax0);
            childShape1.getAabb(newChildWorldTrans1, aabbMin1, aabbMax1);

            if (btGlobals.gCompoundCompoundChildShapePairCallback != null) {
                if (!((btShapePairCallback) btGlobals.gCompoundCompoundChildShapePairCallback)
                        .invoke(childShape0, childShape1)) return;
            }

            if (btAabbUtil2.TestAabbAgainstAabb2(aabbMin0, aabbMax0, aabbMin1, aabbMax1)) {
                btCollisionObjectWrapper compoundWrap0 =
                        new btCollisionObjectWrapper(
                                this.m_compound0ColObjWrap,
                                childShape0,
                                m_compound0ColObjWrap.getCollisionObject(),
                                newChildWorldTrans0,
                                -1,
                                childIndex0);
                btCollisionObjectWrapper compoundWrap1 =
                        new btCollisionObjectWrapper(
                                this.m_compound1ColObjWrap,
                                childShape1,
                                m_compound1ColObjWrap.getCollisionObject(),
                                newChildWorldTrans1,
                                -1,
                                childIndex1);

                btSimplePair pair =
                        m_childCollisionAlgorithmCache.findPair(childIndex0, childIndex1);

                btCollisionAlgorithm colAlgo = null;

                if (pair != null) {
                    colAlgo = (btCollisionAlgorithm) pair.m_userPointer;

                } else {
                    colAlgo =
                            m_dispatcher.findAlgorithm(
                                    compoundWrap0, compoundWrap1, m_sharedManifold);
                    pair =
                            m_childCollisionAlgorithmCache.addOverlappingPair(
                                    childIndex0, childIndex1);
                    pair.m_userPointer = colAlgo;
                }

                btCollisionObjectWrapper tmpWrap0 = null;
                btCollisionObjectWrapper tmpWrap1 = null;

                tmpWrap0 = m_resultOut.getBody0Wrap();
                tmpWrap1 = m_resultOut.getBody1Wrap();

                m_resultOut.setBody0Wrap(compoundWrap0);
                m_resultOut.setBody1Wrap(compoundWrap1);

                m_resultOut.setShapeIdentifiersA(-1, childIndex0);
                m_resultOut.setShapeIdentifiersB(-1, childIndex1);

                colAlgo.processCollision(compoundWrap0, compoundWrap1, m_dispatchInfo, m_resultOut);

                m_resultOut.setBody0Wrap(tmpWrap0);
                m_resultOut.setBody1Wrap(tmpWrap1);
            }
        }
    }

    /**
     * file-static {@code MyIntersect}; {@code Intersect(a,newb)} is the DBVT_IMPL_GENERIC inline
     * (BT_USE_SSE is off in the double build).
     */
    static boolean MyIntersect(btDbvtAabbMm a, btDbvtAabbMm b, btTransform xform) {
        btVector3 newmin = new btVector3(), newmax = new btVector3();
        btAabbUtil2.btTransformAabb(b.Mins(), b.Maxs(), 0.f, xform, newmin, newmax);
        btDbvtAabbMm newb = btDbvtAabbMm.FromMM(newmin, newmax);
        return Intersect(a, newb);
    }

    /** {@code DBVT_INLINE bool Intersect(const btDbvtAabbMm& a, const btDbvtAabbMm& b)} generic. */
    static boolean Intersect(btDbvtAabbMm a, btDbvtAabbMm b) {
        btVector3 ami = a.Mins(), amx = a.Maxs(), bmi = b.Mins(), bmx = b.Maxs();
        return ((ami.x() <= bmx.x())
                && (amx.x() >= bmi.x())
                && (ami.y() <= bmx.y())
                && (amx.y() >= bmi.y())
                && (ami.z() <= bmx.z())
                && (amx.z() >= bmi.z()));
    }

    /**
     * file-static {@code MycollideTT}. The stack is a btAlignedObjectArray (allocation counters
     * match C++); slots hold immutable {@link btDbvt.sStkNN} references, equivalent to the C++
     * value copies since an entry is never mutated in place.
     */
    static void MycollideTT(
            btDbvtNode root0,
            btDbvtNode root1,
            btTransform xform,
            btCompoundCompoundLeafCallback callback) {

        if (root0 != null && root1 != null) {
            int depth = 1;
            int treshold = btDbvt.DOUBLE_STACKSIZE - 4;
            btAlignedObjectArray<btDbvt.sStkNN> stkStack = new btAlignedObjectArray<>();
            stkStack.resize(btDbvt.DOUBLE_STACKSIZE);
            stkStack.set(0, new btDbvt.sStkNN(root0, root1));
            do {
                btDbvt.sStkNN p = stkStack.get(--depth);
                if (MyIntersect(p.a.volume, p.b.volume, xform)) {
                    if (depth > treshold) {
                        stkStack.resize(stkStack.size() * 2);
                        treshold = stkStack.size() - 4;
                    }
                    if (p.a.isinternal()) {
                        if (p.b.isinternal()) {
                            stkStack.set(depth++, new btDbvt.sStkNN(p.a.childs[0], p.b.childs[0]));
                            stkStack.set(depth++, new btDbvt.sStkNN(p.a.childs[1], p.b.childs[0]));
                            stkStack.set(depth++, new btDbvt.sStkNN(p.a.childs[0], p.b.childs[1]));
                            stkStack.set(depth++, new btDbvt.sStkNN(p.a.childs[1], p.b.childs[1]));
                        } else {
                            stkStack.set(depth++, new btDbvt.sStkNN(p.a.childs[0], p.b));
                            stkStack.set(depth++, new btDbvt.sStkNN(p.a.childs[1], p.b));
                        }
                    } else {
                        if (p.b.isinternal()) {
                            stkStack.set(depth++, new btDbvt.sStkNN(p.a, p.b.childs[0]));
                            stkStack.set(depth++, new btDbvt.sStkNN(p.a, p.b.childs[1]));
                        } else {
                            callback.Process(p.a, p.b);
                        }
                    }
                }
            } while (depth != 0);
            stkStack.clear(); // ~btAlignedObjectArray
        }
    }

    @Override
    public void processCollision(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {

        btCollisionObjectWrapper col0ObjWrap = body0Wrap;
        btCollisionObjectWrapper col1ObjWrap = body1Wrap;

        btCompoundShape compoundShape0 = (btCompoundShape) col0ObjWrap.getCollisionShape();
        btCompoundShape compoundShape1 = (btCompoundShape) col1ObjWrap.getCollisionShape();

        /// btCompoundShape might have changed:
        //// make sure the internal child collision algorithm caches are still valid
        if ((compoundShape0.getUpdateRevision() != m_compoundShapeRevision0)
                || (compoundShape1.getUpdateRevision() != m_compoundShapeRevision1)) {
            /// clear all
            removeChildAlgorithms();
        }

        /// we need to refresh all contact manifolds
        /// note that we should actually recursively traverse all children, btCompoundShape can
        /// nested more then 1 level deep
        /// so we should add a 'refreshManifolds' in the btCollisionAlgorithm
        {
            int i;
            btAlignedObjectArray<btPersistentManifold> manifoldArray = new btAlignedObjectArray<>();
            btAlignedObjectArray<btSimplePair> pairs =
                    m_childCollisionAlgorithmCache.getOverlappingPairArray();
            for (i = 0; i < pairs.size(); i++) {
                if (pairs.get(i).m_userPointer != null) {
                    btCollisionAlgorithm algo = (btCollisionAlgorithm) pairs.get(i).m_userPointer;
                    algo.getAllContactManifolds(manifoldArray);
                    for (int m = 0; m < manifoldArray.size(); m++) {
                        if (manifoldArray.get(m).getNumContacts() != 0) {
                            resultOut.setPersistentManifold(manifoldArray.get(m));
                            resultOut.refreshContactPoints();
                            resultOut.setPersistentManifold(null);
                        }
                    }
                    manifoldArray.resize(0);
                }
            }
            manifoldArray.clear(); // ~btAlignedObjectArray
        }

        btDbvt tree0 = compoundShape0.getDynamicAabbTree();
        btDbvt tree1 = compoundShape1.getDynamicAabbTree();

        btCompoundCompoundLeafCallback callback =
                new btCompoundCompoundLeafCallback(
                        col0ObjWrap,
                        col1ObjWrap,
                        this.m_dispatcher,
                        dispatchInfo,
                        resultOut,
                        this.m_childCollisionAlgorithmCache,
                        m_sharedManifold);

        btTransform xform =
                col0ObjWrap.getWorldTransform().inverse().mul(col1ObjWrap.getWorldTransform());
        MycollideTT(tree0.m_root, tree1.m_root, xform, callback);

        // remove non-overlapping child pairs

        {
            // iterate over all children, perform an AABB check inside ProcessChildShape
            btAlignedObjectArray<btSimplePair> pairs =
                    m_childCollisionAlgorithmCache.getOverlappingPairArray();

            int i;
            btAlignedObjectArray<btPersistentManifold> manifoldArray = new btAlignedObjectArray<>();

            btVector3 aabbMin0 = new btVector3(),
                    aabbMax0 = new btVector3(),
                    aabbMin1 = new btVector3(),
                    aabbMax1 = new btVector3();

            for (i = 0; i < pairs.size(); i++) {
                if (pairs.get(i).m_userPointer != null) {
                    btCollisionAlgorithm algo = (btCollisionAlgorithm) pairs.get(i).m_userPointer;

                    {
                        btTransform orgTrans0 = new btTransform();
                        btCollisionShape childShape0 = null;

                        btTransform newChildWorldTrans0 = new btTransform();
                        btTransform orgInterpolationTrans0 = new btTransform();
                        childShape0 = compoundShape0.getChildShape(pairs.get(i).m_indexA);
                        orgTrans0.set(col0ObjWrap.getWorldTransform());
                        orgInterpolationTrans0.set(col0ObjWrap.getWorldTransform());
                        btTransform childTrans0 =
                                compoundShape0.getChildTransform(pairs.get(i).m_indexA);
                        newChildWorldTrans0.set(orgTrans0.mul(childTrans0));
                        childShape0.getAabb(newChildWorldTrans0, aabbMin0, aabbMax0);
                    }

                    {
                        btTransform orgInterpolationTrans1 = new btTransform();
                        btCollisionShape childShape1 = null;
                        btTransform orgTrans1 = new btTransform();
                        btTransform newChildWorldTrans1 = new btTransform();

                        childShape1 = compoundShape1.getChildShape(pairs.get(i).m_indexB);
                        orgTrans1.set(col1ObjWrap.getWorldTransform());
                        orgInterpolationTrans1.set(col1ObjWrap.getWorldTransform());
                        btTransform childTrans1 =
                                compoundShape1.getChildTransform(pairs.get(i).m_indexB);
                        newChildWorldTrans1.set(orgTrans1.mul(childTrans1));
                        childShape1.getAabb(newChildWorldTrans1, aabbMin1, aabbMax1);
                    }

                    if (!btAabbUtil2.TestAabbAgainstAabb2(aabbMin0, aabbMax0, aabbMin1, aabbMax1)) {
                        algo.destroy();
                        m_dispatcher.freeCollisionAlgorithm(algo.m_allocAddress);
                        m_removePairs.push_back(
                                new btSimplePair(pairs.get(i).m_indexA, pairs.get(i).m_indexB));
                    }
                }
            }
            for (int i2 = 0; i2 < m_removePairs.size(); i2++) {
                m_childCollisionAlgorithmCache.removeOverlappingPair(
                        m_removePairs.get(i2).m_indexA, m_removePairs.get(i2).m_indexB);
            }
            m_removePairs.clear();
            manifoldArray.clear(); // ~btAlignedObjectArray
        }
    }

    @Override
    public double calculateTimeOfImpact(
            btCollisionObject body0,
            btCollisionObject body1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        return 0.f;
    }

    public static class CreateFunc extends btCollisionAlgorithmCreateFunc {
        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btCompoundCompoundCollisionAlgorithm a =
                    new btCompoundCompoundCollisionAlgorithm(ci, body0Wrap, body1Wrap, false);
            a.m_allocAddress = mem;
            return a;
        }
    }

    public static class SwappedCreateFunc extends btCollisionAlgorithmCreateFunc {
        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btCompoundCompoundCollisionAlgorithm a =
                    new btCompoundCompoundCollisionAlgorithm(ci, body0Wrap, body1Wrap, true);
            a.m_allocAddress = mem;
            return a;
        }
    }
}
