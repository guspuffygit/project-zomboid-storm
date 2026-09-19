// Port of BulletCollision/NarrowPhaseCollision/btRaycastCallback.{h,cpp} (Bullet 2.82):
// btTriangleConvexcastCallback
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleCallback;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleShape;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btTriangleConvexcastCallback extends btTriangleCallback {
    public btConvexShape m_convexShape;
    public final btTransform m_convexShapeFrom = new btTransform();
    public final btTransform m_convexShapeTo = new btTransform();
    public final btTransform m_triangleToWorld = new btTransform();
    public double m_hitFraction;
    public double m_triangleCollisionMargin;
    public double m_allowedPenetration;

    public btTriangleConvexcastCallback(
            btConvexShape convexShape,
            btTransform convexShapeFrom,
            btTransform convexShapeTo,
            btTransform triangleToWorld,
            final double triangleCollisionMargin) {
        m_convexShape = convexShape;
        m_convexShapeFrom.set(convexShapeFrom);
        m_convexShapeTo.set(convexShapeTo);
        m_triangleToWorld.set(triangleToWorld);
        m_hitFraction = (double) 1.0f;
        m_triangleCollisionMargin = triangleCollisionMargin;
        m_allowedPenetration = (double) 0.f;
    }

    @Override
    public void processTriangle(btVector3[] triangle, int partId, int triangleIndex) {
        btTriangleShape triangleShape = new btTriangleShape(triangle[0], triangle[1], triangle[2]);
        triangleShape.setMargin(m_triangleCollisionMargin);

        btVoronoiSimplexSolver simplexSolver = new btVoronoiSimplexSolver();
        btGjkEpaPenetrationDepthSolver gjkEpaPenetrationSolver =
                new btGjkEpaPenetrationDepthSolver();

        // USE_SUBSIMPLEX_CONVEX_CAST is not defined
        btContinuousConvexCollision convexCaster =
                new btContinuousConvexCollision(
                        m_convexShape, triangleShape, simplexSolver, gjkEpaPenetrationSolver);

        btConvexCast.CastResult castResult = new btConvexCast.CastResult();
        castResult.m_fraction = 1.;
        castResult.m_allowedPenetration = m_allowedPenetration;
        if (convexCaster.calcTimeOfImpact(
                m_convexShapeFrom,
                m_convexShapeTo,
                m_triangleToWorld,
                m_triangleToWorld,
                castResult)) {
            // add hit
            if (castResult.m_normal.length2() > 0.0001) {
                if (castResult.m_fraction < m_hitFraction) {
                    /* btContinuousConvexCast's normal is already in world space */
                    castResult.m_normal.normalize();

                    reportHit(
                            castResult.m_normal,
                            castResult.m_hitPoint,
                            castResult.m_fraction,
                            partId,
                            triangleIndex);
                }
            }
        }
    }

    public abstract double reportHit(
            btVector3 hitNormalLocal,
            btVector3 hitPointLocal,
            double hitFraction,
            int partId,
            int triangleIndex);
}
