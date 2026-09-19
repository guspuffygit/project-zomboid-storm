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
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btCompoundCollisionAlgorithm.{h,cpp}.
 *
 * <p>Supports collision between CompoundCollisionShapes and other collision shapes. The global
 * {@code gCompoundChildShapePairCallback} is {@link btGlobals#gCompoundChildShapePairCallback}
 * (holds a {@link btShapePairCallback}).
 */
public class btCompoundCollisionAlgorithm extends btActivatingCollisionAlgorithm {
    /** sizeof(btCompoundCollisionAlgorithm) on x86-64. */
    public static final int SIZEOF = 0x48;

    public final btAlignedObjectArray<btCollisionAlgorithm> m_childCollisionAlgorithms =
            new btAlignedObjectArray<>();
    public boolean m_isSwapped;

    public btPersistentManifold m_sharedManifold;
    public boolean m_ownsManifold;

    /** to keep track of changes, so that childAlgorithm array can be updated */
    public int m_compoundShapeRevision;

    public btCompoundCollisionAlgorithm(
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            boolean isSwapped) {
        super(ci, body0Wrap, body1Wrap);
        m_isSwapped = isSwapped;
        m_sharedManifold = ci.m_manifold;
        m_ownsManifold = false;

        btCollisionObjectWrapper colObjWrap = m_isSwapped ? body1Wrap : body0Wrap;

        btCompoundShape compoundShape = (btCompoundShape) colObjWrap.getCollisionShape();
        m_compoundShapeRevision = compoundShape.getUpdateRevision();

        preallocateChildAlgorithms(body0Wrap, body1Wrap);
    }

    public void preallocateChildAlgorithms(
            btCollisionObjectWrapper body0Wrap, btCollisionObjectWrapper body1Wrap) {
        btCollisionObjectWrapper colObjWrap = m_isSwapped ? body1Wrap : body0Wrap;
        btCollisionObjectWrapper otherObjWrap = m_isSwapped ? body0Wrap : body1Wrap;

        btCompoundShape compoundShape = (btCompoundShape) colObjWrap.getCollisionShape();

        int numChildren = compoundShape.getNumChildShapes();
        int i;

        m_childCollisionAlgorithms.resize(numChildren);
        for (i = 0; i < numChildren; i++) {
            if (compoundShape.getDynamicAabbTree() != null) {
                m_childCollisionAlgorithms.set(i, null);
            } else {

                btCollisionShape childShape = compoundShape.getChildShape(i);

                btCollisionObjectWrapper childWrap =
                        new btCollisionObjectWrapper(
                                colObjWrap,
                                childShape,
                                colObjWrap.getCollisionObject(),
                                colObjWrap.getWorldTransform(),
                                -1,
                                i); // wrong child trans, but unused (hopefully)
                m_childCollisionAlgorithms.set(
                        i, m_dispatcher.findAlgorithm(childWrap, otherObjWrap, m_sharedManifold));
            }
        }
    }

    public void removeChildAlgorithms() {
        int numChildren = m_childCollisionAlgorithms.size();
        int i;
        for (i = 0; i < numChildren; i++) {
            if (m_childCollisionAlgorithms.get(i) != null) {
                btCollisionAlgorithm algo = m_childCollisionAlgorithms.get(i);
                algo.destroy();
                m_dispatcher.freeCollisionAlgorithm(algo.m_allocAddress);
            }
        }
    }

    /**
     * {@code ~btCompoundCollisionAlgorithm()}: removeChildAlgorithms, then the member array's
     * destructor, then the base destructors.
     */
    @Override
    public void destroy() {
        removeChildAlgorithms();
        m_childCollisionAlgorithms.clear();
        super.destroy();
    }

    public btCollisionAlgorithm getChildAlgorithm(int n) {
        return m_childCollisionAlgorithms.get(n);
    }

    /** File-local {@code struct btCompoundLeafCallback : btDbvt::ICollide}. */
    public static class btCompoundLeafCallback extends btDbvt.ICollide {
        public btCollisionObjectWrapper m_compoundColObjWrap;
        public btCollisionObjectWrapper m_otherObjWrap;
        public btDispatcher m_dispatcher;
        public btDispatcherInfo m_dispatchInfo;
        public btManifoldResult m_resultOut;

        /** {@code btCollisionAlgorithm** m_childCollisionAlgorithms} = &array[0]. */
        public btAlignedObjectArray<btCollisionAlgorithm> m_childCollisionAlgorithms;

        public btPersistentManifold m_sharedManifold;

        public btCompoundLeafCallback(
                btCollisionObjectWrapper compoundObjWrap,
                btCollisionObjectWrapper otherObjWrap,
                btDispatcher dispatcher,
                btDispatcherInfo dispatchInfo,
                btManifoldResult resultOut,
                btAlignedObjectArray<btCollisionAlgorithm> childCollisionAlgorithms,
                btPersistentManifold sharedManifold) {
            m_compoundColObjWrap = compoundObjWrap;
            m_otherObjWrap = otherObjWrap;
            m_dispatcher = dispatcher;
            m_dispatchInfo = dispatchInfo;
            m_resultOut = resultOut;
            m_childCollisionAlgorithms = childCollisionAlgorithms;
            m_sharedManifold = sharedManifold;
        }

        public void ProcessChildShape(btCollisionShape childShape, int index) {
            btCompoundShape compoundShape =
                    (btCompoundShape) m_compoundColObjWrap.getCollisionShape();

            // backup
            btTransform orgTrans = new btTransform(m_compoundColObjWrap.getWorldTransform());
            btTransform orgInterpolationTrans =
                    new btTransform(m_compoundColObjWrap.getWorldTransform());
            btTransform childTrans = compoundShape.getChildTransform(index);
            btTransform newChildWorldTrans = orgTrans.mul(childTrans);

            // perform an AABB check first
            btVector3 aabbMin0 = new btVector3(),
                    aabbMax0 = new btVector3(),
                    aabbMin1 = new btVector3(),
                    aabbMax1 = new btVector3();
            childShape.getAabb(newChildWorldTrans, aabbMin0, aabbMax0);
            m_otherObjWrap
                    .getCollisionShape()
                    .getAabb(m_otherObjWrap.getWorldTransform(), aabbMin1, aabbMax1);

            if (btGlobals.gCompoundChildShapePairCallback != null) {
                if (!((btShapePairCallback) btGlobals.gCompoundChildShapePairCallback)
                        .invoke(m_otherObjWrap.getCollisionShape(), childShape)) return;
            }

            if (btAabbUtil2.TestAabbAgainstAabb2(aabbMin0, aabbMax0, aabbMin1, aabbMax1)) {

                btCollisionObjectWrapper compoundWrap =
                        new btCollisionObjectWrapper(
                                this.m_compoundColObjWrap,
                                childShape,
                                m_compoundColObjWrap.getCollisionObject(),
                                newChildWorldTrans,
                                -1,
                                index);

                // the contactpoint is still projected back using the original inverted worldtrans
                if (m_childCollisionAlgorithms.get(index) == null)
                    m_childCollisionAlgorithms.set(
                            index,
                            m_dispatcher.findAlgorithm(
                                    compoundWrap, m_otherObjWrap, m_sharedManifold));

                btCollisionObjectWrapper tmpWrap = null;

                /// detect swapping case
                if (m_resultOut.getBody0Internal() == m_compoundColObjWrap.getCollisionObject()) {
                    tmpWrap = m_resultOut.getBody0Wrap();
                    m_resultOut.setBody0Wrap(compoundWrap);
                    m_resultOut.setShapeIdentifiersA(-1, index);
                } else {
                    tmpWrap = m_resultOut.getBody1Wrap();
                    m_resultOut.setBody1Wrap(compoundWrap);
                    m_resultOut.setShapeIdentifiersB(-1, index);
                }

                m_childCollisionAlgorithms
                        .get(index)
                        .processCollision(
                                compoundWrap, m_otherObjWrap, m_dispatchInfo, m_resultOut);

                if (m_resultOut.getBody0Internal() == m_compoundColObjWrap.getCollisionObject()) {
                    m_resultOut.setBody0Wrap(tmpWrap);
                } else {
                    m_resultOut.setBody1Wrap(tmpWrap);
                }
            }
        }

        @Override
        public void Process(btDbvtNode leaf) {
            int index = leaf.dataAsInt;

            btCompoundShape compoundShape =
                    (btCompoundShape) m_compoundColObjWrap.getCollisionShape();
            btCollisionShape childShape = compoundShape.getChildShape(index);

            ProcessChildShape(childShape, index);
        }
    }

    @Override
    public void processCollision(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        btCollisionObjectWrapper colObjWrap = m_isSwapped ? body1Wrap : body0Wrap;
        btCollisionObjectWrapper otherObjWrap = m_isSwapped ? body0Wrap : body1Wrap;

        btCompoundShape compoundShape = (btCompoundShape) colObjWrap.getCollisionShape();

        /// btCompoundShape might have changed:
        //// make sure the internal child collision algorithm caches are still valid
        if (compoundShape.getUpdateRevision() != m_compoundShapeRevision) {
            /// clear and update all
            removeChildAlgorithms();

            preallocateChildAlgorithms(body0Wrap, body1Wrap);
        }

        btDbvt tree = compoundShape.getDynamicAabbTree();
        // use a dynamic aabb tree to cull potential child-overlaps
        btCompoundLeafCallback callback =
                new btCompoundLeafCallback(
                        colObjWrap,
                        otherObjWrap,
                        m_dispatcher,
                        dispatchInfo,
                        resultOut,
                        m_childCollisionAlgorithms,
                        m_sharedManifold);

        /// we need to refresh all contact manifolds
        /// note that we should actually recursively traverse all children, btCompoundShape can
        /// nested more then 1 level deep
        /// so we should add a 'refreshManifolds' in the btCollisionAlgorithm
        {
            int i;
            btAlignedObjectArray<btPersistentManifold> manifoldArray = new btAlignedObjectArray<>();
            for (i = 0; i < m_childCollisionAlgorithms.size(); i++) {
                if (m_childCollisionAlgorithms.get(i) != null) {
                    m_childCollisionAlgorithms.get(i).getAllContactManifolds(manifoldArray);
                    for (int m = 0; m < manifoldArray.size(); m++) {
                        if (manifoldArray.get(m).getNumContacts() != 0) {
                            resultOut.setPersistentManifold(manifoldArray.get(m));
                            resultOut.refreshContactPoints();
                            resultOut.setPersistentManifold(null); // ??necessary?
                        }
                    }
                    manifoldArray.resize(0);
                }
            }
            manifoldArray.clear(); // ~btAlignedObjectArray
        }

        if (tree != null) {

            btVector3 localAabbMin = new btVector3(), localAabbMax = new btVector3();
            btTransform otherInCompoundSpace = new btTransform();
            otherInCompoundSpace.set(
                    colObjWrap.getWorldTransform().inverse().mul(otherObjWrap.getWorldTransform()));
            otherObjWrap
                    .getCollisionShape()
                    .getAabb(otherInCompoundSpace, localAabbMin, localAabbMax);

            btDbvtAabbMm bounds = btDbvtAabbMm.FromMM(localAabbMin, localAabbMax);
            // process all children, that overlap with  the given AABB bounds
            tree.collideTV(tree.m_root, bounds, callback);

        } else {
            // iterate over all children, perform an AABB check inside ProcessChildShape
            int numChildren = m_childCollisionAlgorithms.size();
            int i;
            for (i = 0; i < numChildren; i++) {
                callback.ProcessChildShape(compoundShape.getChildShape(i), i);
            }
        }

        {
            // iterate over all children, perform an AABB check inside ProcessChildShape
            int numChildren = m_childCollisionAlgorithms.size();
            int i;
            btAlignedObjectArray<btPersistentManifold> manifoldArray = new btAlignedObjectArray<>();
            btCollisionShape childShape = null;
            btTransform orgTrans = new btTransform();
            btTransform orgInterpolationTrans = new btTransform();
            btTransform newChildWorldTrans = new btTransform();
            btVector3 aabbMin0 = new btVector3(),
                    aabbMax0 = new btVector3(),
                    aabbMin1 = new btVector3(),
                    aabbMax1 = new btVector3();

            for (i = 0; i < numChildren; i++) {
                if (m_childCollisionAlgorithms.get(i) != null) {
                    childShape = compoundShape.getChildShape(i);
                    // if not longer overlapping, remove the algorithm
                    orgTrans.set(colObjWrap.getWorldTransform());
                    orgInterpolationTrans.set(colObjWrap.getWorldTransform());
                    btTransform childTrans = compoundShape.getChildTransform(i);
                    newChildWorldTrans.set(orgTrans.mul(childTrans));

                    // perform an AABB check first
                    childShape.getAabb(newChildWorldTrans, aabbMin0, aabbMax0);
                    otherObjWrap
                            .getCollisionShape()
                            .getAabb(otherObjWrap.getWorldTransform(), aabbMin1, aabbMax1);

                    if (!btAabbUtil2.TestAabbAgainstAabb2(aabbMin0, aabbMax0, aabbMin1, aabbMax1)) {
                        btCollisionAlgorithm algo = m_childCollisionAlgorithms.get(i);
                        algo.destroy();
                        m_dispatcher.freeCollisionAlgorithm(algo.m_allocAddress);
                        m_childCollisionAlgorithms.set(i, null);
                    }
                }
            }
            manifoldArray.clear(); // ~btAlignedObjectArray
        }
    }

    @Override
    public double calculateTimeOfImpact(
            btCollisionObject body0,
            btCollisionObject body1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        // needs to be fixed, using btCollisionObjectWrapper and NOT modifying internal data
        // structures
        btCollisionObject colObj = m_isSwapped ? body1 : body0;
        btCollisionObject otherObj = m_isSwapped ? body0 : body1;

        btCompoundShape compoundShape = (btCompoundShape) colObj.getCollisionShape();

        // We will use the OptimizedBVH, AABB tree to cull potential child-overlaps
        // If both proxies are Compound, we will deal with that directly, by performing
        // sequential/parallel tree traversals
        // given Proxy0 and Proxy1, if both have a tree, Tree0 and Tree1, this means:
        // determine overlapping nodes of Proxy1 using Proxy0 AABB against Tree1
        // then use each overlapping node AABB against Tree0
        // and vise versa.

        double hitFraction = 1.;

        int numChildren = m_childCollisionAlgorithms.size();
        int i;
        btTransform orgTrans = new btTransform();
        double frac;
        for (i = 0; i < numChildren; i++) {
            // backup
            orgTrans.set(colObj.getWorldTransform());

            btTransform childTrans = compoundShape.getChildTransform(i);
            colObj.setWorldTransform(orgTrans.mul(childTrans));

            frac =
                    m_childCollisionAlgorithms
                            .get(i)
                            .calculateTimeOfImpact(colObj, otherObj, dispatchInfo, resultOut);
            if (frac < hitFraction) {
                hitFraction = frac;
            }
            // revert back
            colObj.setWorldTransform(orgTrans);
        }
        return hitFraction;
    }

    @Override
    public void getAllContactManifolds(btAlignedObjectArray<btPersistentManifold> manifoldArray) {
        int i;
        for (i = 0; i < m_childCollisionAlgorithms.size(); i++) {
            if (m_childCollisionAlgorithms.get(i) != null)
                m_childCollisionAlgorithms.get(i).getAllContactManifolds(manifoldArray);
        }
    }

    public static class CreateFunc extends btCollisionAlgorithmCreateFunc {
        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btCompoundCollisionAlgorithm a =
                    new btCompoundCollisionAlgorithm(ci, body0Wrap, body1Wrap, false);
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
            btCompoundCollisionAlgorithm a =
                    new btCompoundCollisionAlgorithm(ci, body0Wrap, body1Wrap, true);
            a.m_allocAddress = mem;
            return a;
        }
    }
}
