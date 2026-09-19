// Port of the global variables of Bullet 2.82 (all translation units) linked into
// libPZBulletNoOpenGL64.so [+ PZ glue globals]. Initial values verified against .data/.bss of the
// binary (nm -C -S + objdump of .data): all match upstream 2.82.
package io.pzstorm.storm.bullet.linearmath;

/**
 * Every Bullet global ({@code g*} variables and file statics that are shared state) as a public
 * static field, in one place (PORTING.md "Naming").
 *
 * <p>Callback globals ({@code gContactAddedCallback} etc.) are typed {@code Object} here because
 * their functional-interface types belong to the collision package; the collision agent should
 * narrow them (e.g. to {@code ContactAddedCallback}) when porting btManifoldResult /
 * btPersistentManifold. Integration reconciles.
 *
 * <p>{@link #nextAddress(long)} emulates a fresh bump heap for pointer-ordered code (PTR-ORDER
 * sites, see docs/re-bullet/pointer-order.md).
 */
public final class btGlobals {
    private btGlobals() {}

    // ---- btRigidBody.cpp ----
    /** btRigidBody.cpp: {@code btScalar gDeactivationTime = btScalar(2.);} */
    public static double gDeactivationTime = 2.0;

    /** btRigidBody.cpp: {@code bool gDisableDeactivation = false;} */
    public static boolean gDisableDeactivation = false;

    /** btRigidBody.cpp file static {@code static int uniqueId = 0;} */
    public static int uniqueId = 0;

    // ---- btDiscreteDynamicsWorld / btSequentialImpulseConstraintSolver ----
    /** {@code int gNumSplitImpulseRecoveries = 0;} */
    public static int gNumSplitImpulseRecoveries = 0;

    /** btDiscreteDynamicsWorld.cpp: {@code int gNumClampedCcdMotions = 0;} */
    public static int gNumClampedCcdMotions = 0;

    // ---- btRaycastVehicle.cpp ----
    /** btRaycastVehicle.cpp: {@code btScalar sideFrictionStiffness2 = btScalar(1.0);} */
    public static double sideFrictionStiffness2 = 1.0;

    // ---- btOverlappingPairCache.cpp ----
    public static int gOverlappingPairs = 0;
    public static int gRemovePairs = 0;
    public static int gAddedPairs = 0;
    public static int gFindPairs = 0;

    // ---- btSimpleBroadphase.cpp ----
    public static int gOverlappingSimplePairs = 0;
    public static int gRemoveSimplePairs = 0;
    public static int gAddedSimplePairs = 0;
    public static int gFindSimplePairs = 0;

    // ---- btPersistentManifold.cpp ----
    /** {@code btScalar gContactBreakingThreshold = btScalar(0.02);} (0x3f947ae147ae147b) */
    public static double gContactBreakingThreshold = 0.02;

    /** {@code bool gContactCalcArea3Points = true;} */
    public static boolean gContactCalcArea3Points = true;

    /** {@code ContactDestroyedCallback gContactDestroyedCallback = 0;} */
    public static Object gContactDestroyedCallback = null;

    /** {@code ContactProcessedCallback gContactProcessedCallback = 0;} */
    public static Object gContactProcessedCallback = null;

    // ---- btManifoldResult.cpp ----
    /** {@code ContactAddedCallback gContactAddedCallback = 0;} */
    public static Object gContactAddedCallback = null;

    // ---- btCollisionDispatcher.cpp ----
    /** {@code int gNumManifold = 0;} */
    public static int gNumManifold = 0;

    // ---- btGjkPairDetector.cpp ----
    public static int gNumDeepPenetrationChecks = 0;
    public static int gNumGjkChecks = 0;

    // ---- btQuantizedBvh.cpp ----
    /** btQuantizedBvh.cpp: {@code int maxIterations = 0;} */
    public static int maxIterations = 0;

    // ---- btCompoundCollisionAlgorithm.cpp / btCompoundCompoundCollisionAlgorithm.cpp ----
    /** {@code btShapePairCallback gCompoundChildShapePairCallback = 0;} */
    public static Object gCompoundChildShapePairCallback = null;

    /** {@code btShapePairCallback gCompoundCompoundChildShapePairCallback = 0;} */
    public static Object gCompoundCompoundChildShapePairCallback = null;

    // ---- btConvexConvexAlgorithm.cpp ----
    /** btConvexConvexAlgorithm.cpp: {@code bool disableCcd = false;} */
    public static boolean disableCcd = false;

    // ---- btPolyhedralContactClipping.cpp ----
    /** {@code int gActualSATPairTests = 0;} */
    public static int gActualSATPairTests = 0;

    /** file static {@code int gExpectedNbTests = 0;} */
    public static int gExpectedNbTests = 0;

    /** file static {@code int gActualNbTests = 0;} */
    public static int gActualNbTests = 0;

    /** file static {@code bool gUseInternalObject = true;} */
    public static boolean gUseInternalObject = true;

    // ---- btAlignedAllocator.cpp ----
    public static int gNumAlignedAllocs = 0;
    public static int gNumAlignedFree = 0;

    /** Only maintained by BT_DEBUG_MEMORY_ALLOCATIONS builds; always 0 in the .so. */
    public static int gTotalBytesAlignedAllocs = 0;

    // ---- PZ glue ----
    /**
     * PZ glue global {@code gDynamicsWorld} (btDiscreteDynamicsWorld*). Typed Object here; owned by
     * the bullet.pz port.
     */
    public static Object gDynamicsWorld = null;

    // ---- pointer-order emulation ----
    /** First address handed out; mimics a glibc heap start (16-byte aligned). */
    public static final long HEAP_BASE = 0x10000000L;

    private static long heapTop = HEAP_BASE;

    /**
     * Returns a monotonically increasing, 16-byte aligned fake address for a heap object of {@code
     * size} bytes. Used for PTR-ORDER sites (objects whose addresses C++ compares or hashes).
     */
    public static synchronized long nextAddress(long size) {
        long a = heapTop;
        long sz = size <= 0 ? 16 : (size + 15) & ~15L;
        // glibc malloc chunk header (16 bytes) between allocations
        heapTop += sz + 16;
        return a;
    }

    /**
     * Run by {@link #resetAddresses()}: owners of free-address lists built on {@link #nextAddress}
     * (e.g. btDbvt's node free list) register here so stale addresses are dropped too.
     */
    public static final java.util.List<Runnable> addressResetHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Resets the fake heap (call when a new world is created, for test determinism). */
    public static synchronized void resetAddresses() {
        heapTop = HEAP_BASE;
        for (Runnable hook : addressResetHooks) hook.run();
    }

    /**
     * btAlignedAllocInternal: counts the allocation (gNumAlignedAllocs++) and returns a fake
     * address. The real function also calls sAlignedAllocFunc; memory itself is Java-managed.
     */
    public static long btAlignedAlloc(long size, int alignment) {
        gNumAlignedAllocs++;
        return nextAddress(size);
    }

    /** btAlignedFreeInternal: {@code if (ptr) { gNumAlignedFree++; free }}. */
    public static void btAlignedFree(long ptr) {
        if (ptr != 0) {
            gNumAlignedFree++;
        }
    }

    /** Resets every global to its initial value (test isolation helper; not in C++). */
    public static void resetAll() {
        gDeactivationTime = 2.0;
        gDisableDeactivation = false;
        uniqueId = 0;
        gNumSplitImpulseRecoveries = 0;
        gNumClampedCcdMotions = 0;
        sideFrictionStiffness2 = 1.0;
        gOverlappingPairs = gRemovePairs = gAddedPairs = gFindPairs = 0;
        gOverlappingSimplePairs = gRemoveSimplePairs = gAddedSimplePairs = gFindSimplePairs = 0;
        gContactBreakingThreshold = 0.02;
        gContactCalcArea3Points = true;
        gContactDestroyedCallback = null;
        gContactProcessedCallback = null;
        gContactAddedCallback = null;
        gNumManifold = 0;
        gNumDeepPenetrationChecks = 0;
        gNumGjkChecks = 0;
        maxIterations = 0;
        gCompoundChildShapePairCallback = null;
        gCompoundCompoundChildShapePairCallback = null;
        disableCcd = false;
        gActualSATPairTests = 0;
        gExpectedNbTests = 0;
        gActualNbTests = 0;
        gUseInternalObject = true;
        gNumAlignedAllocs = 0;
        gNumAlignedFree = 0;
        gTotalBytesAlignedAllocs = 0;
        gDynamicsWorld = null;
        resetAddresses();
    }
}
