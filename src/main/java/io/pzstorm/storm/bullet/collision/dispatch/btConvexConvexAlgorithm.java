package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.narrowphase.btConvexCast;
import io.pzstorm.storm.bullet.collision.narrowphase.btConvexPenetrationDepthSolver;
import io.pzstorm.storm.bullet.collision.narrowphase.btDiscreteCollisionDetectorInterface;
import io.pzstorm.storm.bullet.collision.narrowphase.btGjkConvexCast;
import io.pzstorm.storm.bullet.collision.narrowphase.btGjkPairDetector;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.narrowphase.btPolyhedralContactClipping;
import io.pzstorm.storm.bullet.collision.narrowphase.btVoronoiSimplexSolver;
import io.pzstorm.storm.bullet.collision.shapes.btCapsuleShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btPolyhedralConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleShape;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btConvexConvexAlgorithm.{h,cpp}.
 * USE_SEPDISTANCE_UTIL2, ZERO_MARGIN, BT_DISABLE_CAPSULE_CAPSULE_COLLIDER and DEBUG_CONTACTS are
 * not defined. {@code btSimplexSolverInterface} is {@code #define}d to btVoronoiSimplexSolver in
 * 2.82 (the linked symbols take {@code btVoronoiSimplexSolver*}).
 */
public class btConvexConvexAlgorithm extends btActivatingCollisionAlgorithm {
    /** sizeof(btConvexConvexAlgorithm) in the x86-64 binary. */
    public static final int SIZEOF = 0x40;

    btVoronoiSimplexSolver m_simplexSolver;
    btConvexPenetrationDepthSolver m_pdSolver;

    boolean m_ownManifold;
    btPersistentManifold m_manifoldPtr;
    boolean m_lowLevelOfDetail;

    int m_numPerturbationIterations;
    int m_minimumPointsPerturbationThreshold;

    // ---- file statics ----

    /** static segmentsClosestPoints; out = {tA, tB}. */
    static void segmentsClosestPoints(
            btVector3 ptsVector,
            btVector3 offsetA,
            btVector3 offsetB,
            double[] tAB,
            btVector3 translation,
            btVector3 dirA,
            double hlenA,
            btVector3 dirB,
            double hlenB) {
        double tA, tB;
        double dirA_dot_dirB = btVector3.btDot(dirA, dirB);
        double dirA_dot_trans = btVector3.btDot(dirA, translation);
        double dirB_dot_trans = btVector3.btDot(dirB, translation);

        double denom = 1.0 - dirA_dot_dirB * dirA_dot_dirB;

        if (denom == 0.0) {
            tA = 0.0;
        } else {
            tA = (dirA_dot_trans - dirB_dot_trans * dirA_dot_dirB) / denom;
            if (tA < -hlenA) tA = -hlenA;
            else if (tA > hlenA) tA = hlenA;
        }

        tB = tA * dirA_dot_dirB - dirB_dot_trans;

        if (tB < -hlenB) {
            tB = -hlenB;
            tA = tB * dirA_dot_dirB + dirA_dot_trans;

            if (tA < -hlenA) tA = -hlenA;
            else if (tA > hlenA) tA = hlenA;
        } else if (tB > hlenB) {
            tB = hlenB;
            tA = tB * dirA_dot_dirB + dirA_dot_trans;

            if (tA < -hlenA) tA = -hlenA;
            else if (tA > hlenA) tA = hlenA;
        }

        offsetA.set(dirA.mul(tA));
        offsetB.set(dirB.mul(tB));

        ptsVector.set(translation.sub(offsetA).add(offsetB));
        tAB[0] = tA;
        tAB[1] = tB;
    }

    /** static capsuleCapsuleDistance. */
    static double capsuleCapsuleDistance(
            btVector3 normalOnB,
            btVector3 pointOnB,
            double capsuleLengthA,
            double capsuleRadiusA,
            double capsuleLengthB,
            double capsuleRadiusB,
            int capsuleAxisA,
            int capsuleAxisB,
            btTransform transformA,
            btTransform transformB,
            double distanceThreshold) {
        btVector3 directionA = transformA.getBasis().getColumn(capsuleAxisA);
        btVector3 translationA = new btVector3(transformA.getOrigin());
        btVector3 directionB = transformB.getBasis().getColumn(capsuleAxisB);
        btVector3 translationB = new btVector3(transformB.getOrigin());

        btVector3 translation = translationB.sub(translationA);

        btVector3 ptsVector = new btVector3();
        btVector3 offsetA = new btVector3(), offsetB = new btVector3();
        double[] tAB = new double[2];

        segmentsClosestPoints(
                ptsVector,
                offsetA,
                offsetB,
                tAB,
                translation,
                directionA,
                capsuleLengthA,
                directionB,
                capsuleLengthB);

        double distance = ptsVector.length() - capsuleRadiusA - capsuleRadiusB;
        if (distance > distanceThreshold) return distance;

        double lenSqr = ptsVector.length2();
        if (lenSqr <= (btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON)) {
            // degenerate case where 2 capsules are likely at the same location
            btVector3 q = new btVector3();
            btVector3.btPlaneSpace1(directionA, normalOnB, q);
        } else {
            // compute the contact normal
            normalOnB.set(ptsVector.mul(-btScalar.btRecipSqrt(lenSqr)));
        }
        pointOnB.set(transformB.getOrigin().add(offsetB).add(normalOnB.mul(capsuleRadiusB)));

        return distance;
    }

    public btConvexConvexAlgorithm(
            btPersistentManifold mf,
            btCollisionAlgorithmConstructionInfo ci,
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btVoronoiSimplexSolver simplexSolver,
            btConvexPenetrationDepthSolver pdSolver,
            int numPerturbationIterations,
            int minimumPointsPerturbationThreshold) {
        super(ci, body0Wrap, body1Wrap);
        m_simplexSolver = simplexSolver;
        m_pdSolver = pdSolver;
        m_ownManifold = false;
        m_manifoldPtr = mf;
        m_lowLevelOfDetail = false;
        m_numPerturbationIterations = numPerturbationIterations;
        m_minimumPointsPerturbationThreshold = minimumPointsPerturbationThreshold;
    }

    @Override
    public void destroy() {
        if (m_ownManifold) {
            if (m_manifoldPtr != null) m_dispatcher.releaseManifold(m_manifoldPtr);
        }
        super.destroy();
    }

    public void setLowLevelOfDetail(boolean useLowLevel) {
        m_lowLevelOfDetail = useLowLevel;
    }

    public btPersistentManifold getManifold() {
        return m_manifoldPtr;
    }

    @Override
    public void getAllContactManifolds(btAlignedObjectArray<btPersistentManifold> manifoldArray) {
        // should we use m_ownManifold to avoid adding duplicates?
        if (m_manifoldPtr != null && m_ownManifold) manifoldArray.push_back(m_manifoldPtr);
    }

    /** Function-local struct btDummyResult in processCollision. */
    static class btDummyResult extends btDiscreteCollisionDetectorInterface.Result {
        @Override
        public void setShapeIdentifiersA(int partId0, int index0) {}

        @Override
        public void setShapeIdentifiersB(int partId1, int index1) {}

        @Override
        public void addContactPoint(
                btVector3 normalOnBInWorld, btVector3 pointInWorld, double depth) {}
    }

    /** Function-local struct btWithoutMarginResult in processCollision. */
    static class btWithoutMarginResult extends btDiscreteCollisionDetectorInterface.Result {
        btDiscreteCollisionDetectorInterface.Result m_originalResult;
        final btVector3 m_reportedNormalOnWorld = new btVector3();
        double m_marginOnA;
        double m_marginOnB;
        double m_reportedDistance;

        boolean m_foundResult;

        btWithoutMarginResult(
                btDiscreteCollisionDetectorInterface.Result result,
                double marginOnA,
                double marginOnB) {
            m_originalResult = result;
            m_marginOnA = marginOnA;
            m_marginOnB = marginOnB;
            m_foundResult = false;
        }

        @Override
        public void setShapeIdentifiersA(int partId0, int index0) {}

        @Override
        public void setShapeIdentifiersB(int partId1, int index1) {}

        @Override
        public void addContactPoint(
                btVector3 normalOnBInWorld, btVector3 pointInWorldOrg, double depthOrg) {
            m_reportedDistance = depthOrg;
            m_reportedNormalOnWorld.set(normalOnBInWorld);

            btVector3 adjustedPointB = pointInWorldOrg.sub(normalOnBInWorld.mul(m_marginOnB));
            m_reportedDistance = depthOrg + (m_marginOnA + m_marginOnB);
            if (m_reportedDistance < 0.) {
                m_foundResult = true;
            }
            m_originalResult.addContactPoint(normalOnBInWorld, adjustedPointB, m_reportedDistance);
        }
    }

    @Override
    public void processCollision(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        if (m_manifoldPtr == null) {
            // swapped?
            m_manifoldPtr =
                    m_dispatcher.getNewManifold(
                            body0Wrap.getCollisionObject(), body1Wrap.getCollisionObject());
            m_ownManifold = true;
        }
        resultOut.setPersistentManifold(m_manifoldPtr);

        btConvexShape min0 = (btConvexShape) body0Wrap.getCollisionShape();
        btConvexShape min1 = (btConvexShape) body1Wrap.getCollisionShape();

        btVector3 normalOnB = new btVector3();
        btVector3 pointOnBWorld = new btVector3();

        if ((min0.getShapeType() == BroadphaseNativeTypes.CAPSULE_SHAPE_PROXYTYPE)
                && (min1.getShapeType() == BroadphaseNativeTypes.CAPSULE_SHAPE_PROXYTYPE)) {
            btCapsuleShape capsuleA = (btCapsuleShape) min0;
            btCapsuleShape capsuleB = (btCapsuleShape) min1;

            double threshold = m_manifoldPtr.getContactBreakingThreshold();

            double dist =
                    capsuleCapsuleDistance(
                            normalOnB,
                            pointOnBWorld,
                            capsuleA.getHalfHeight(),
                            capsuleA.getRadius(),
                            capsuleB.getHalfHeight(),
                            capsuleB.getRadius(),
                            capsuleA.getUpAxis(),
                            capsuleB.getUpAxis(),
                            body0Wrap.getWorldTransform(),
                            body1Wrap.getWorldTransform(),
                            threshold);

            if (dist < threshold) {
                resultOut.addContactPoint(normalOnB, pointOnBWorld, dist);
            }
            resultOut.refreshContactPoints();
            return;
        }

        {
            btGjkPairDetector.ClosestPointInput input = new btGjkPairDetector.ClosestPointInput();

            btGjkPairDetector gjkPairDetector =
                    new btGjkPairDetector(min0, min1, m_simplexSolver, m_pdSolver);
            // TODO: if (dispatchInfo.m_useContinuous)
            gjkPairDetector.setMinkowskiA(min0);
            gjkPairDetector.setMinkowskiB(min1);

            {
                input.m_maximumDistanceSquared =
                        min0.getMargin()
                                + min1.getMargin()
                                + m_manifoldPtr.getContactBreakingThreshold();
                input.m_maximumDistanceSquared *= input.m_maximumDistanceSquared;
            }

            input.m_transformA.set(body0Wrap.getWorldTransform());
            input.m_transformB.set(body1Wrap.getWorldTransform());

            if (min0.isPolyhedral() && min1.isPolyhedral()) {
                btDummyResult dummy = new btDummyResult();

                // btBoxShape is an exception: its vertices are created WITH margin so don't
                // subtract it
                double min0Margin =
                        min0.getShapeType() == BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE
                                ? (double) 0.f
                                : min0.getMargin();
                double min1Margin =
                        min1.getShapeType() == BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE
                                ? (double) 0.f
                                : min1.getMargin();

                btWithoutMarginResult withoutMargin =
                        new btWithoutMarginResult(resultOut, min0Margin, min1Margin);

                btPolyhedralConvexShape polyhedronA = (btPolyhedralConvexShape) min0;
                btPolyhedralConvexShape polyhedronB = (btPolyhedralConvexShape) min1;
                if (polyhedronA.getConvexPolyhedron() != null
                        && polyhedronB.getConvexPolyhedron() != null) {
                    double threshold = m_manifoldPtr.getContactBreakingThreshold();

                    double minDist = (double) -1e30f;
                    btVector3 sepNormalWorldSpace = new btVector3();
                    boolean foundSepAxis = true;

                    if (dispatchInfo.m_enableSatConvex) {
                        foundSepAxis =
                                btPolyhedralContactClipping.findSeparatingAxis(
                                        polyhedronA.getConvexPolyhedron(),
                                        polyhedronB.getConvexPolyhedron(),
                                        body0Wrap.getWorldTransform(),
                                        body1Wrap.getWorldTransform(),
                                        sepNormalWorldSpace,
                                        resultOut);
                    } else {
                        gjkPairDetector.getClosestPoints(
                                input, withoutMargin, dispatchInfo.m_debugDraw);
                        {
                            sepNormalWorldSpace.set(withoutMargin.m_reportedNormalOnWorld);
                            minDist = withoutMargin.m_reportedDistance;
                            foundSepAxis = withoutMargin.m_foundResult && minDist < 0;
                        }
                    }
                    if (foundSepAxis) {
                        btPolyhedralContactClipping.clipHullAgainstHull(
                                sepNormalWorldSpace,
                                polyhedronA.getConvexPolyhedron(),
                                polyhedronB.getConvexPolyhedron(),
                                body0Wrap.getWorldTransform(),
                                body1Wrap.getWorldTransform(),
                                minDist - threshold,
                                threshold,
                                resultOut);
                    }
                    if (m_ownManifold) {
                        resultOut.refreshContactPoints();
                    }
                    return;
                } else {
                    // we can also deal with convex versus triangle (without connectivity data)
                    if (polyhedronA.getConvexPolyhedron() != null
                            && polyhedronB.getShapeType()
                                    == BroadphaseNativeTypes.TRIANGLE_SHAPE_PROXYTYPE) {
                        btAlignedObjectArray<btVector3> vertices =
                                btPolyhedralContactClipping.newVertexArray();
                        btTriangleShape tri = (btTriangleShape) polyhedronB;
                        vertices.push_back(body1Wrap.getWorldTransform().mul(tri.m_vertices1[0]));
                        vertices.push_back(body1Wrap.getWorldTransform().mul(tri.m_vertices1[1]));
                        vertices.push_back(body1Wrap.getWorldTransform().mul(tri.m_vertices1[2]));

                        double threshold = m_manifoldPtr.getContactBreakingThreshold();

                        btVector3 sepNormalWorldSpace = new btVector3();
                        double minDist = (double) -1e30f;
                        double maxDist = threshold;

                        boolean foundSepAxis = false;
                        // if (0) { ... findSeparatingAxis ... } is dead code
                        {
                            gjkPairDetector.getClosestPoints(
                                    input, dummy, dispatchInfo.m_debugDraw);

                            double l2 = gjkPairDetector.getCachedSeparatingAxis().length2();
                            if (l2 > btScalar.SIMD_EPSILON) {
                                sepNormalWorldSpace.set(
                                        gjkPairDetector
                                                .getCachedSeparatingAxis()
                                                .mul((double) 1.f / l2));
                                minDist =
                                        gjkPairDetector.getCachedSeparatingDistance()
                                                - min0.getMargin()
                                                - min1.getMargin();
                                foundSepAxis = true;
                            }
                        }

                        if (foundSepAxis) {
                            btPolyhedralContactClipping.clipFaceAgainstHull(
                                    sepNormalWorldSpace,
                                    polyhedronA.getConvexPolyhedron(),
                                    body0Wrap.getWorldTransform(),
                                    vertices,
                                    minDist - threshold,
                                    maxDist,
                                    resultOut);
                        }

                        if (m_ownManifold) {
                            resultOut.refreshContactPoints();
                        }
                        vertices.clear(); // ~btVertexArray
                        return;
                    }
                }
            }

            gjkPairDetector.getClosestPoints(input, resultOut, dispatchInfo.m_debugDraw);

            // now perturbe directions to get multiple contact points
            if (m_numPerturbationIterations != 0
                    && resultOut.getPersistentManifold().getNumContacts()
                            < m_minimumPointsPerturbationThreshold) {
                int i;
                btVector3 v0 = new btVector3(), v1 = new btVector3();
                btVector3 sepNormalWorldSpace = new btVector3();
                double l2 = gjkPairDetector.getCachedSeparatingAxis().length2();

                if (l2 > btScalar.SIMD_EPSILON) {
                    sepNormalWorldSpace.set(
                            gjkPairDetector.getCachedSeparatingAxis().mul((double) 1.f / l2));

                    btVector3.btPlaneSpace1(sepNormalWorldSpace, v0, v1);

                    boolean perturbeA = true;
                    final double angleLimit = (double) 0.125f * btScalar.SIMD_PI;
                    double perturbeAngle;
                    double radiusA = min0.getAngularMotionDisc();
                    double radiusB = min1.getAngularMotionDisc();
                    if (radiusA < radiusB) {
                        perturbeAngle = btGlobals.gContactBreakingThreshold / radiusA;
                        perturbeA = true;
                    } else {
                        perturbeAngle = btGlobals.gContactBreakingThreshold / radiusB;
                        perturbeA = false;
                    }
                    if (perturbeAngle > angleLimit) perturbeAngle = angleLimit;

                    btTransform unPerturbedTransform;
                    if (perturbeA) {
                        unPerturbedTransform = new btTransform(input.m_transformA);
                    } else {
                        unPerturbedTransform = new btTransform(input.m_transformB);
                    }

                    for (i = 0; i < m_numPerturbationIterations; i++) {
                        if (v0.length2() > btScalar.SIMD_EPSILON) {
                            btQuaternion perturbeRot = new btQuaternion(v0, perturbeAngle);
                            double iterationAngle =
                                    i * (btScalar.SIMD_2_PI / (double) m_numPerturbationIterations);
                            btQuaternion rotq =
                                    new btQuaternion(sepNormalWorldSpace, iterationAngle);

                            if (perturbeA) {
                                input.m_transformA.setBasis(
                                        new btMatrix3x3(rotq.inverse().mul(perturbeRot).mul(rotq))
                                                .mul(body0Wrap.getWorldTransform().getBasis()));
                                input.m_transformB.set(body1Wrap.getWorldTransform());
                            } else {
                                input.m_transformA.set(body0Wrap.getWorldTransform());
                                input.m_transformB.setBasis(
                                        new btMatrix3x3(rotq.inverse().mul(perturbeRot).mul(rotq))
                                                .mul(body1Wrap.getWorldTransform().getBasis()));
                            }

                            btPerturbedContactResult perturbedResultOut =
                                    new btPerturbedContactResult(
                                            resultOut,
                                            input.m_transformA,
                                            input.m_transformB,
                                            unPerturbedTransform,
                                            perturbeA,
                                            dispatchInfo.m_debugDraw);
                            gjkPairDetector.getClosestPoints(
                                    input, perturbedResultOut, dispatchInfo.m_debugDraw);
                        }
                    }
                }
            }
        }

        if (m_ownManifold) {
            resultOut.refreshContactPoints();
        }
    }

    @Override
    public double calculateTimeOfImpact(
            btCollisionObject col0,
            btCollisionObject col1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut) {
        // Rather then checking ALL pairs, only calculate TOI when motion exceeds threshold
        double resultFraction = 1.;

        double squareMot0 =
                (col0.getInterpolationWorldTransform()
                                .getOrigin()
                                .sub(col0.getWorldTransform().getOrigin()))
                        .length2();
        double squareMot1 =
                (col1.getInterpolationWorldTransform()
                                .getOrigin()
                                .sub(col1.getWorldTransform().getOrigin()))
                        .length2();

        if (squareMot0 < col0.getCcdSquareMotionThreshold()
                && squareMot1 < col1.getCcdSquareMotionThreshold()) return resultFraction;

        if (btGlobals.disableCcd) return 1.;

        // An adhoc way of testing the Continuous Collision Detection algorithms
        // Convex0 against sphere for Convex1
        {
            btConvexShape convex0 = (btConvexShape) col0.getCollisionShape();

            btSphereShape sphere1 = new btSphereShape(col1.getCcdSweptSphereRadius());
            btConvexCast.CastResult result = new btConvexCast.CastResult();
            btVoronoiSimplexSolver voronoiSimplex = new btVoronoiSimplexSolver();
            btGjkConvexCast ccd1 = new btGjkConvexCast(convex0, sphere1, voronoiSimplex);
            if (ccd1.calcTimeOfImpact(
                    col0.getWorldTransform(),
                    col0.getInterpolationWorldTransform(),
                    col1.getWorldTransform(),
                    col1.getInterpolationWorldTransform(),
                    result)) {
                // store result.m_fraction in both bodies
                if (col0.getHitFraction() > result.m_fraction)
                    col0.setHitFraction(result.m_fraction);

                if (col1.getHitFraction() > result.m_fraction)
                    col1.setHitFraction(result.m_fraction);

                if (resultFraction > result.m_fraction) resultFraction = result.m_fraction;
            }
        }

        // Sphere (for convex0) against Convex1
        {
            btConvexShape convex1 = (btConvexShape) col1.getCollisionShape();

            btSphereShape sphere0 = new btSphereShape(col0.getCcdSweptSphereRadius());
            btConvexCast.CastResult result = new btConvexCast.CastResult();
            btVoronoiSimplexSolver voronoiSimplex = new btVoronoiSimplexSolver();
            btGjkConvexCast ccd1 = new btGjkConvexCast(sphere0, convex1, voronoiSimplex);
            if (ccd1.calcTimeOfImpact(
                    col0.getWorldTransform(),
                    col0.getInterpolationWorldTransform(),
                    col1.getWorldTransform(),
                    col1.getInterpolationWorldTransform(),
                    result)) {
                if (col0.getHitFraction() > result.m_fraction)
                    col0.setHitFraction(result.m_fraction);

                if (col1.getHitFraction() > result.m_fraction)
                    col1.setHitFraction(result.m_fraction);

                if (resultFraction > result.m_fraction) resultFraction = result.m_fraction;
            }
        }

        return resultFraction;
    }

    public static class CreateFunc extends btCollisionAlgorithmCreateFunc {
        public btConvexPenetrationDepthSolver m_pdSolver;
        public btVoronoiSimplexSolver m_simplexSolver;
        public int m_numPerturbationIterations;
        public int m_minimumPointsPerturbationThreshold;

        public CreateFunc(
                btVoronoiSimplexSolver simplexSolver, btConvexPenetrationDepthSolver pdSolver) {
            m_numPerturbationIterations = 0;
            m_minimumPointsPerturbationThreshold = 3;
            m_simplexSolver = simplexSolver;
            m_pdSolver = pdSolver;
        }

        /** virtual ~CreateFunc() */
        public void destroy() {}

        @Override
        public btCollisionAlgorithm CreateCollisionAlgorithm(
                btCollisionAlgorithmConstructionInfo ci,
                btCollisionObjectWrapper body0Wrap,
                btCollisionObjectWrapper body1Wrap) {
            long mem = ci.m_dispatcher1.allocateCollisionAlgorithm(SIZEOF);
            btConvexConvexAlgorithm a =
                    new btConvexConvexAlgorithm(
                            ci.m_manifold,
                            ci,
                            body0Wrap,
                            body1Wrap,
                            m_simplexSolver,
                            m_pdSolver,
                            m_numPerturbationIterations,
                            m_minimumPointsPerturbationThreshold);
            a.m_allocAddress = mem;
            return a;
        }
    }
}
