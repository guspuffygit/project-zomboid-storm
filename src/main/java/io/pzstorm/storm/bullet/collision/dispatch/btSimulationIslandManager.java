// Port of BulletCollision/CollisionDispatch/btSimulationIslandManager.h/.cpp (Bullet 2.82).
// STATIC_SIMULATION_ISLAND_OPTIMIZATION is defined (btUnionFind.h).
package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.btBroadphasePair;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.broadphase.btOverlappingPairCache;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.linearmath.CProfileSample;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import java.util.function.BiPredicate;

/** SimulationIslandManager creates and handles simulation islands, using btUnionFind */
public class btSimulationIslandManager {
    public final btUnionFind m_unionFind = new btUnionFind();

    public final btAlignedObjectArray<btPersistentManifold> m_islandmanifold =
            new btAlignedObjectArray<>();
    public final btAlignedObjectArray<btCollisionObject> m_islandBodies =
            new btAlignedObjectArray<>();

    public boolean m_splitIslands;

    /**
     * struct IslandCallback. C++ passes raw pointers into arrays ({@code btCollisionObject**
     * bodies}, {@code btPersistentManifold** manifolds}); Java passes the backing array ({@link
     * btAlignedObjectArray#m_data}) plus the element offset the pointer points at. An array is null
     * where C++ passes a null pointer (islands without manifolds, empty manifold array).
     */
    public abstract static class IslandCallback {
        /** {@code virtual ~IslandCallback() {}} */
        public void destroy() {}

        public abstract void processIsland(
                Object[] bodies,
                int bodiesOffset,
                int numBodies,
                Object[] manifolds,
                int manifoldsOffset,
                int numManifolds,
                int islandId);
    }

    public btSimulationIslandManager() {
        m_splitIslands = true;
    }

    /** {@code virtual ~btSimulationIslandManager()}: member arrays are destroyed (freed). */
    public void destroy() {
        m_islandBodies.clear();
        m_islandmanifold.clear();
        m_unionFind.destroy();
    }

    public void initUnionFind(int n) {
        m_unionFind.reset(n);
    }

    public btUnionFind getUnionFind() {
        return m_unionFind;
    }

    public void findUnions(btDispatcher dispatcher, btCollisionWorld colWorld) {
        {
            btOverlappingPairCache pairCachePtr = colWorld.getPairCache();
            final int numOverlappingPairs = pairCachePtr.getNumOverlappingPairs();
            if (numOverlappingPairs != 0) {
                btAlignedObjectArray<btBroadphasePair> pairPtr =
                        pairCachePtr.getOverlappingPairArray();

                for (int i = 0; i < numOverlappingPairs; i++) {
                    final btBroadphasePair collisionPair = pairPtr.get(i);
                    btCollisionObject colObj0 =
                            (btCollisionObject) collisionPair.m_pProxy0.m_clientObject;
                    btCollisionObject colObj1 =
                            (btCollisionObject) collisionPair.m_pProxy1.m_clientObject;

                    if (((colObj0 != null) && ((colObj0).mergesSimulationIslands()))
                            && ((colObj1 != null) && ((colObj1).mergesSimulationIslands()))) {

                        m_unionFind.unite((colObj0).getIslandTag(), (colObj1).getIslandTag());
                    }
                }
            }
        }
    }

    public void updateActivationState(btCollisionWorld colWorld, btDispatcher dispatcher) {
        // put the index into m_controllers into m_tag
        int index = 0;
        {
            int i;
            for (i = 0; i < colWorld.getCollisionObjectArray().size(); i++) {
                btCollisionObject collisionObject = colWorld.getCollisionObjectArray().get(i);
                // Adding filtering here
                if (!collisionObject.isStaticOrKinematicObject()) {
                    collisionObject.setIslandTag(index++);
                }
                collisionObject.setCompanionId(-1);
                collisionObject.setHitFraction(1.0);
            }
        }
        // do the union find

        initUnionFind(index);

        findUnions(dispatcher, colWorld);
    }

    public void storeIslandActivationState(btCollisionWorld colWorld) {
        // put the islandId ('find' value) into m_tag
        {
            int index = 0;
            int i;
            for (i = 0; i < colWorld.getCollisionObjectArray().size(); i++) {
                btCollisionObject collisionObject = colWorld.getCollisionObjectArray().get(i);
                if (!collisionObject.isStaticOrKinematicObject()) {
                    collisionObject.setIslandTag(m_unionFind.find(index));
                    // Set the correct object offset in Collision Object Array
                    m_unionFind.getElement(index).m_sz = i;
                    collisionObject.setCompanionId(-1);
                    index++;
                } else {
                    collisionObject.setIslandTag(-1);
                    collisionObject.setCompanionId(-2);
                }
            }
        }
    }

    /** inline int getIslandId(const btPersistentManifold* lhs) */
    public static int getIslandId(btPersistentManifold lhs) {
        int islandId;
        btCollisionObject rcolObj0 = (btCollisionObject) lhs.getBody0();
        btCollisionObject rcolObj1 = (btCollisionObject) lhs.getBody1();
        islandId = rcolObj0.getIslandTag() >= 0 ? rcolObj0.getIslandTag() : rcolObj1.getIslandTag();
        return islandId;
    }

    /** function object that routes calls to operator< */
    public static final BiPredicate<btPersistentManifold, btPersistentManifold>
            btPersistentManifoldSortPredicate = (lhs, rhs) -> getIslandId(lhs) < getIslandId(rhs);

    public void buildIslands(btDispatcher dispatcher, btCollisionWorld collisionWorld) {
        try (CProfileSample __profile = new CProfileSample("islandUnionFindAndQuickSort")) {
            btAlignedObjectArray<btCollisionObject> collisionObjects =
                    collisionWorld.getCollisionObjectArray();

            m_islandmanifold.resize(0);

            // we are going to sort the unionfind array, and store the element id in the size
            // afterwards, we clean unionfind, to make sure no-one uses it anymore

            getUnionFind().sortIslands();
            int numElem = getUnionFind().getNumElements();

            int endIslandIndex = 1;
            int startIslandIndex;

            // update the sleeping state for bodies, if all are sleeping
            for (startIslandIndex = 0;
                    startIslandIndex < numElem;
                    startIslandIndex = endIslandIndex) {
                int islandId = getUnionFind().getElement(startIslandIndex).m_id;
                for (endIslandIndex = startIslandIndex + 1;
                        (endIslandIndex < numElem)
                                && (getUnionFind().getElement(endIslandIndex).m_id == islandId);
                        endIslandIndex++) {}

                // int numSleeping = 0;

                boolean allSleeping = true;

                int idx;
                for (idx = startIslandIndex; idx < endIslandIndex; idx++) {
                    int i = getUnionFind().getElement(idx).m_sz;

                    btCollisionObject colObj0 = collisionObjects.get(i);
                    if (colObj0.getIslandTag() == islandId) {
                        if (colObj0.getActivationState() == btCollisionObject.ACTIVE_TAG) {
                            allSleeping = false;
                        }
                        if (colObj0.getActivationState()
                                == btCollisionObject.DISABLE_DEACTIVATION) {
                            allSleeping = false;
                        }
                    }
                }

                if (allSleeping) {
                    for (idx = startIslandIndex; idx < endIslandIndex; idx++) {
                        int i = getUnionFind().getElement(idx).m_sz;
                        btCollisionObject colObj0 = collisionObjects.get(i);
                        if (colObj0.getIslandTag() == islandId) {
                            colObj0.setActivationState(btCollisionObject.ISLAND_SLEEPING);
                        }
                    }
                } else {
                    for (idx = startIslandIndex; idx < endIslandIndex; idx++) {
                        int i = getUnionFind().getElement(idx).m_sz;

                        btCollisionObject colObj0 = collisionObjects.get(i);
                        if (colObj0.getIslandTag() == islandId) {
                            if (colObj0.getActivationState() == btCollisionObject.ISLAND_SLEEPING) {
                                colObj0.setActivationState(btCollisionObject.WANTS_DEACTIVATION);
                                colObj0.setDeactivationTime((double) 0.f);
                            }
                        }
                    }
                }
            }

            int i;
            int maxNumManifolds = dispatcher.getNumManifolds();

            for (i = 0; i < maxNumManifolds; i++) {
                btPersistentManifold manifold = dispatcher.getManifoldByIndexInternal(i);

                btCollisionObject colObj0 = (btCollisionObject) manifold.getBody0();
                btCollisionObject colObj1 = (btCollisionObject) manifold.getBody1();

                /// @todo: check sleeping conditions!
                if (((colObj0 != null)
                                && colObj0.getActivationState()
                                        != btCollisionObject.ISLAND_SLEEPING)
                        || ((colObj1 != null)
                                && colObj1.getActivationState()
                                        != btCollisionObject.ISLAND_SLEEPING)) {

                    // kinematic objects don't merge islands, but wake up all connected objects
                    if (colObj0.isKinematicObject()
                            && colObj0.getActivationState() != btCollisionObject.ISLAND_SLEEPING) {
                        if (colObj0.hasContactResponse()) colObj1.activate();
                    }
                    if (colObj1.isKinematicObject()
                            && colObj1.getActivationState() != btCollisionObject.ISLAND_SLEEPING) {
                        if (colObj1.hasContactResponse()) colObj0.activate();
                    }
                    if (m_splitIslands) {
                        // filtering for response
                        if (dispatcher.needsResponse(colObj0, colObj1))
                            m_islandmanifold.push_back(manifold);
                    }
                }
            }
        }
    }

    /**
     * @todo: this is random access, it can be walked 'cache friendly'!
     */
    public void buildAndProcessIslands(
            btDispatcher dispatcher, btCollisionWorld collisionWorld, IslandCallback callback) {
        btAlignedObjectArray<btCollisionObject> collisionObjects =
                collisionWorld.getCollisionObjectArray();

        buildIslands(dispatcher, collisionWorld);

        int endIslandIndex = 1;
        int startIslandIndex;
        int numElem = getUnionFind().getNumElements();

        try (CProfileSample __profile = new CProfileSample("processIslands")) {
            if (!m_splitIslands) {
                btAlignedObjectArray<btPersistentManifold> manifold =
                        dispatcher.getInternalManifoldPointer();
                int maxNumManifolds = dispatcher.getNumManifolds();
                // &collisionObjects[0]
                callback.processIsland(
                        collisionObjects.m_data,
                        0,
                        collisionObjects.size(),
                        manifold != null ? manifold.m_data : null,
                        0,
                        maxNumManifolds,
                        -1);
            } else {
                // Sort manifolds, based on islands
                // Sort the vector using predicate and std::sort

                int numManifolds = m_islandmanifold.size();

                // tried a radix sort, but quicksort/heapsort seems still faster
                // @todo rewrite island management
                m_islandmanifold.quickSort(btPersistentManifoldSortPredicate);

                // now process all active islands (sets of manifolds for now)

                int startManifoldIndex = 0;
                int endManifoldIndex = 1;

                // traverse the simulation islands, and call the solver, unless all objects are
                // sleeping/deactivated
                for (startIslandIndex = 0;
                        startIslandIndex < numElem;
                        startIslandIndex = endIslandIndex) {
                    int islandId = getUnionFind().getElement(startIslandIndex).m_id;

                    boolean islandSleeping = true;

                    for (endIslandIndex = startIslandIndex;
                            (endIslandIndex < numElem)
                                    && (getUnionFind().getElement(endIslandIndex).m_id == islandId);
                            endIslandIndex++) {
                        int i = getUnionFind().getElement(endIslandIndex).m_sz;
                        btCollisionObject colObj0 = collisionObjects.get(i);
                        m_islandBodies.push_back(colObj0);
                        if (colObj0.isActive()) islandSleeping = false;
                    }

                    // find the accompanying contact manifold for this islandId
                    int numIslandManifolds = 0;
                    Object[] startManifold = null;
                    int startManifoldOffset = 0;

                    if (startManifoldIndex < numManifolds) {
                        int curIslandId = getIslandId(m_islandmanifold.get(startManifoldIndex));
                        if (curIslandId == islandId) {
                            startManifold =
                                    m_islandmanifold
                                            .m_data; // &m_islandmanifold[startManifoldIndex]
                            startManifoldOffset = startManifoldIndex;

                            for (endManifoldIndex = startManifoldIndex + 1;
                                    (endManifoldIndex < numManifolds)
                                            && (islandId
                                                    == getIslandId(
                                                            m_islandmanifold.get(
                                                                    endManifoldIndex)));
                                    endManifoldIndex++) {}
                            /// Process the actual simulation, only if not sleeping/deactivated
                            numIslandManifolds = endManifoldIndex - startManifoldIndex;
                        }
                    }

                    if (!islandSleeping) {
                        // &m_islandBodies[0]
                        callback.processIsland(
                                m_islandBodies.m_data,
                                0,
                                m_islandBodies.size(),
                                startManifold,
                                startManifoldOffset,
                                numIslandManifolds,
                                islandId);
                    }

                    if (numIslandManifolds != 0) {
                        startManifoldIndex = endManifoldIndex;
                    }

                    m_islandBodies.resize(0);
                }
            } // else if(!splitIslands)
        }
    }

    public boolean getSplitIslands() {
        return m_splitIslands;
    }

    public void setSplitIslands(boolean doSplitIslands) {
        m_splitIslands = doSplitIslands;
    }
}
