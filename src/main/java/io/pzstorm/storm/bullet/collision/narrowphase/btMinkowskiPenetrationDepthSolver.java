// Port of BulletCollision/NarrowPhaseCollision/btMinkowskiPenetrationDepthSolver.{h,cpp} (Bullet
// 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * MinkowskiPenetrationDepthSolver implements bruteforce penetration depth estimation.
 * Implementation is based on sampling the depth using support mapping, and using GJK step to get
 * the witness points.
 */
public class btMinkowskiPenetrationDepthSolver extends btConvexPenetrationDepthSolver {
    static final int NUM_UNITSPHERE_POINTS = 42;

    private static final class btIntermediateResult
            extends btDiscreteCollisionDetectorInterface.Result {
        btIntermediateResult() {
            m_hasResult = false;
        }

        final btVector3 m_normalOnBInWorld = new btVector3();
        final btVector3 m_pointInWorld = new btVector3();
        double m_depth;
        boolean m_hasResult;

        @Override
        public void setShapeIdentifiersA(int partId0, int index0) {}

        @Override
        public void setShapeIdentifiersB(int partId1, int index1) {}

        @Override
        public void addContactPoint(
                btVector3 normalOnBInWorld, btVector3 pointInWorld, double depth) {
            m_normalOnBInWorld.set(normalOnBInWorld);
            m_pointInWorld.set(pointInWorld);
            m_depth = depth;
            m_hasResult = true;
        }
    }

    @Override
    public boolean calcPenDepth(
            btSimplexSolverInterface simplexSolver,
            btConvexShape convexA,
            btConvexShape convexB,
            btTransform transA,
            btTransform transB,
            btVector3 v,
            btVector3 pa,
            btVector3 pb,
            btIDebugDraw debugDraw) {

        boolean check2d = convexA.isConvex2d() && convexB.isConvex2d();

        // just take fixed number of orientation, and sample the penetration depth in that direction
        double minProj = BT_LARGE_FLOAT;
        btVector3 minNorm = new btVector3(0., 0., 0.);
        // btVector3 minA,minB; (uninitialised in C++; always assigned when any finite delta is
        // sampled)
        btVector3 minA = new btVector3(), minB = new btVector3();
        btVector3 seperatingAxisInA = new btVector3(), seperatingAxisInB = new btVector3();
        btVector3 pInA = new btVector3(),
                qInB = new btVector3(),
                pWorld = new btVector3(),
                qWorld = new btVector3(),
                w = new btVector3();

        // USE_BATCHED_SUPPORT
        final int N =
                NUM_UNITSPHERE_POINTS + btConvexShape.MAX_PREFERRED_PENETRATION_DIRECTIONS * 2;
        btVector3[] supportVerticesABatch = newArray(N);
        btVector3[] supportVerticesBBatch = newArray(N);
        btVector3[] seperatingAxisInABatch = newArray(N);
        btVector3[] seperatingAxisInBBatch = newArray(N);
        int i;

        int numSampleDirections = NUM_UNITSPHERE_POINTS;

        for (i = 0; i < numSampleDirections; i++) {
            btVector3 norm = new btVector3(getPenetrationDirections()[i]);
            seperatingAxisInABatch[i].set(btMatrix3x3.mul(norm.negate(), transA.getBasis()));
            seperatingAxisInBBatch[i].set(btMatrix3x3.mul(norm, transB.getBasis()));
        }

        {
            int numPDA = convexA.getNumPreferredPenetrationDirections();
            if (numPDA != 0) {
                for (int i2 = 0; i2 < numPDA; i2++) {
                    btVector3 norm = new btVector3();
                    convexA.getPreferredPenetrationDirection(i2, norm);
                    norm.set(transA.getBasis().mul(norm));
                    getPenetrationDirections()[numSampleDirections].set(norm);
                    seperatingAxisInABatch[numSampleDirections].set(
                            btMatrix3x3.mul(norm.negate(), transA.getBasis()));
                    seperatingAxisInBBatch[numSampleDirections].set(
                            btMatrix3x3.mul(norm, transB.getBasis()));
                    numSampleDirections++;
                }
            }
        }

        {
            int numPDB = convexB.getNumPreferredPenetrationDirections();
            if (numPDB != 0) {
                for (int i2 = 0; i2 < numPDB; i2++) {
                    btVector3 norm = new btVector3();
                    convexB.getPreferredPenetrationDirection(i2, norm);
                    norm.set(transB.getBasis().mul(norm));
                    getPenetrationDirections()[numSampleDirections].set(norm);
                    seperatingAxisInABatch[numSampleDirections].set(
                            btMatrix3x3.mul(norm.negate(), transA.getBasis()));
                    seperatingAxisInBBatch[numSampleDirections].set(
                            btMatrix3x3.mul(norm, transB.getBasis()));
                    numSampleDirections++;
                }
            }
        }

        convexA.batchedUnitVectorGetSupportingVertexWithoutMargin(
                seperatingAxisInABatch, supportVerticesABatch, numSampleDirections);
        convexB.batchedUnitVectorGetSupportingVertexWithoutMargin(
                seperatingAxisInBBatch, supportVerticesBBatch, numSampleDirections);

        for (i = 0; i < numSampleDirections; i++) {
            btVector3 norm = new btVector3(getPenetrationDirections()[i]);
            if (check2d) {
                norm.set(2, (double) 0.f);
            }
            if (norm.length2() > 0.01) {

                seperatingAxisInA.set(seperatingAxisInABatch[i]);
                seperatingAxisInB.set(seperatingAxisInBBatch[i]);

                pInA.set(supportVerticesABatch[i]);
                qInB.set(supportVerticesBBatch[i]);

                pWorld.set(transA.mul(pInA));
                qWorld.set(transB.mul(qInB));
                if (check2d) {
                    pWorld.set(2, (double) 0.f);
                    qWorld.set(2, (double) 0.f);
                }

                w.set(qWorld.sub(pWorld));
                double delta = norm.dot(w);
                // find smallest delta
                if (delta < minProj) {
                    minProj = delta;
                    minNorm.set(norm);
                    minA.set(pWorld);
                    minB.set(qWorld);
                }
            }
        }

        // add the margins

        minA.addLocal(minNorm.mul(convexA.getMarginNonVirtual()));
        minB.subLocal(minNorm.mul(convexB.getMarginNonVirtual()));
        // no penetration
        if (minProj < 0.) return false;

        double extraSeparation = (double) 0.5f; // / scale dependent
        minProj +=
                extraSeparation + (convexA.getMarginNonVirtual() + convexB.getMarginNonVirtual());

        btGjkPairDetector gjkdet = new btGjkPairDetector(convexA, convexB, simplexSolver, null);

        double offsetDist = minProj;
        btVector3 offset = minNorm.mul(offsetDist);

        btDiscreteCollisionDetectorInterface.ClosestPointInput input =
                new btDiscreteCollisionDetectorInterface.ClosestPointInput();

        btVector3 newOrg = transA.getOrigin().add(offset);

        btTransform displacedTrans = new btTransform(transA);
        displacedTrans.setOrigin(newOrg);

        input.m_transformA.set(displacedTrans);
        input.m_transformB.set(transB);
        input.m_maximumDistanceSquared = BT_LARGE_FLOAT; // minProj;

        btIntermediateResult res = new btIntermediateResult();
        gjkdet.setCachedSeperatingAxis(minNorm.negate());
        gjkdet.getClosestPoints(input, res, debugDraw);

        double correctedMinNorm = minProj - res.m_depth;

        // the penetration depth is over-estimated, relax it
        double penetration_relaxation = 1.;
        minNorm.mulLocal(penetration_relaxation);

        if (res.m_hasResult) {

            pa.set(res.m_pointInWorld.sub(minNorm.mul(correctedMinNorm)));
            pb.set(res.m_pointInWorld);
            v.set(minNorm);
        }
        return res.m_hasResult;
    }

    private static final double BT_LARGE_FLOAT = btScalar.BT_LARGE_FLOAT;

    private static btVector3[] newArray(int n) {
        btVector3[] a = new btVector3[n];
        for (int i = 0; i < n; i++) a[i] = new btVector3();
        return a;
    }

    /**
     * Function-local static table (mutable: preferred penetration directions of the current pair
     * are written into slots 42.. and persist across calls, exactly as in C++). Entries 42..61
     * start zero-initialised.
     */
    private static final btVector3[] sPenetrationDirections = {
        new btVector3(0.000000, -0.000000, -1.000000),
        new btVector3(0.723608, -0.525725, -0.447219),
        new btVector3(-0.276388, -0.850649, -0.447219),
        new btVector3(-0.894426, -0.000000, -0.447216),
        new btVector3(-0.276388, 0.850649, -0.447220),
        new btVector3(0.723608, 0.525725, -0.447219),
        new btVector3(0.276388, -0.850649, 0.447220),
        new btVector3(-0.723608, -0.525725, 0.447219),
        new btVector3(-0.723608, 0.525725, 0.447219),
        new btVector3(0.276388, 0.850649, 0.447219),
        new btVector3(0.894426, 0.000000, 0.447216),
        new btVector3(-0.000000, 0.000000, 1.000000),
        new btVector3(0.425323, -0.309011, -0.850654),
        new btVector3(-0.162456, -0.499995, -0.850654),
        new btVector3(0.262869, -0.809012, -0.525738),
        new btVector3(0.425323, 0.309011, -0.850654),
        new btVector3(0.850648, -0.000000, -0.525736),
        new btVector3(-0.525730, -0.000000, -0.850652),
        new btVector3(-0.688190, -0.499997, -0.525736),
        new btVector3(-0.162456, 0.499995, -0.850654),
        new btVector3(-0.688190, 0.499997, -0.525736),
        new btVector3(0.262869, 0.809012, -0.525738),
        new btVector3(0.951058, 0.309013, 0.000000),
        new btVector3(0.951058, -0.309013, 0.000000),
        new btVector3(0.587786, -0.809017, 0.000000),
        new btVector3(0.000000, -1.000000, 0.000000),
        new btVector3(-0.587786, -0.809017, 0.000000),
        new btVector3(-0.951058, -0.309013, -0.000000),
        new btVector3(-0.951058, 0.309013, -0.000000),
        new btVector3(-0.587786, 0.809017, -0.000000),
        new btVector3(-0.000000, 1.000000, -0.000000),
        new btVector3(0.587786, 0.809017, -0.000000),
        new btVector3(0.688190, -0.499997, 0.525736),
        new btVector3(-0.262869, -0.809012, 0.525738),
        new btVector3(-0.850648, 0.000000, 0.525736),
        new btVector3(-0.262869, 0.809012, 0.525738),
        new btVector3(0.688190, 0.499997, 0.525736),
        new btVector3(0.525730, 0.000000, 0.850652),
        new btVector3(0.162456, -0.499995, 0.850654),
        new btVector3(-0.425323, -0.309011, 0.850654),
        new btVector3(-0.425323, 0.309011, 0.850654),
        new btVector3(0.162456, 0.499995, 0.850654),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3(),
        new btVector3()
    };

    public static btVector3[] getPenetrationDirections() {
        return sPenetrationDirections;
    }
}
