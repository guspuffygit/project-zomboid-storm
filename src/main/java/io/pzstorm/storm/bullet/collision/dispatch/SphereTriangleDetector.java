package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.narrowphase.btDiscreteCollisionDetectorInterface;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleShape;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** Port of Bullet 2.82 BulletCollision/CollisionDispatch/SphereTriangleDetector.{h,cpp}. */
public class SphereTriangleDetector extends btDiscreteCollisionDetectorInterface {
    private btSphereShape m_sphere;
    private btTriangleShape m_triangle;
    private double m_contactBreakingThreshold;

    public SphereTriangleDetector(
            btSphereShape sphere, btTriangleShape triangle, double contactBreakingThreshold) {
        m_sphere = sphere;
        m_triangle = triangle;
        m_contactBreakingThreshold = contactBreakingThreshold;
    }

    /** virtual ~SphereTriangleDetector() {} */
    public void destroy() {}

    @Override
    public void getClosestPoints(
            ClosestPointInput input, Result output, btIDebugDraw debugDraw, boolean swapResults) {
        btTransform transformA = input.m_transformA;
        btTransform transformB = input.m_transformB;

        btVector3 point = new btVector3(), normal = new btVector3();
        double[] timeOfImpact = {1.};
        double[] depth = {0.};

        // move sphere into triangle space
        btTransform sphereInTr = transformB.inverseTimes(transformA);

        if (collide(
                sphereInTr.getOrigin(),
                point,
                normal,
                depth,
                timeOfImpact,
                m_contactBreakingThreshold)) {
            if (swapResults) {
                btVector3 normalOnB = transformB.getBasis().mul(normal);
                btVector3 normalOnA = normalOnB.negate();
                btVector3 pointOnA = transformB.transform(point).add(normalOnB.mul(depth[0]));
                output.addContactPoint(normalOnA, pointOnA, depth[0]);
            } else {
                output.addContactPoint(
                        transformB.getBasis().mul(normal), transformB.transform(point), depth[0]);
            }
        }
    }

    /** Free function SegmentSqrDistance (external linkage in SphereTriangleDetector.cpp). */
    public static double SegmentSqrDistance(
            btVector3 from, btVector3 to, btVector3 p, btVector3 nearest) {
        btVector3 diff = p.sub(from);
        btVector3 v = to.sub(from);
        double t = v.dot(diff);

        if (t > 0) {
            double dotVV = v.dot(v);
            if (t < dotVV) {
                t /= dotVV;
                diff.subLocal(v.mul(t));
            } else {
                t = 1;
                diff.subLocal(v);
            }
        } else t = 0;

        nearest.set(from.add(v.mul(t)));
        return diff.dot(diff);
    }

    boolean facecontains(btVector3 p, btVector3[] vertices, btVector3 normal) {
        btVector3 lp = new btVector3(p);
        btVector3 lnormal = new btVector3(normal);
        return pointInTriangle(vertices, lnormal, lp);
    }

    /** depth / timeOfImpact are C++ {@code btScalar&} out-params: element [0]. */
    public boolean collide(
            btVector3 sphereCenter,
            btVector3 point,
            btVector3 resultNormal,
            double[] depth,
            double[] timeOfImpact,
            double contactBreakingThreshold) {
        btVector3[] vertices = m_triangle.m_vertices1; // &m_triangle->getVertexPtr(0)

        double radius = m_sphere.getRadius();
        double radiusWithThreshold = radius + contactBreakingThreshold;

        btVector3 normal = (vertices[1].sub(vertices[0])).cross(vertices[2].sub(vertices[0]));
        normal.normalize();
        btVector3 p1ToCentre = sphereCenter.sub(vertices[0]);
        double distanceFromPlane = p1ToCentre.dot(normal);

        if (distanceFromPlane < 0.) {
            // triangle facing the other way
            distanceFromPlane *= -1.;
            normal.mulLocal(-1.);
        }

        boolean isInsideContactPlane = distanceFromPlane < radiusWithThreshold;

        // Check for contact / intersection
        boolean hasContact = false;
        btVector3 contactPoint = new btVector3();
        if (isInsideContactPlane) {
            if (facecontains(sphereCenter, vertices, normal)) {
                // Inside the contact wedge - touches a point on the shell plane
                hasContact = true;
                contactPoint.set(sphereCenter.sub(normal.mul(distanceFromPlane)));
            } else {
                // Could be inside one of the contact capsules
                double contactCapsuleRadiusSqr = radiusWithThreshold * radiusWithThreshold;
                btVector3 nearestOnEdge = new btVector3();
                for (int i = 0; i < m_triangle.getNumEdges(); i++) {
                    btVector3 pa = new btVector3();
                    btVector3 pb = new btVector3();

                    m_triangle.getEdge(i, pa, pb);

                    double distanceSqr = SegmentSqrDistance(pa, pb, sphereCenter, nearestOnEdge);
                    if (distanceSqr < contactCapsuleRadiusSqr) {
                        // Yep, we're inside a capsule
                        hasContact = true;
                        contactPoint.set(nearestOnEdge);
                    }
                }
            }
        }

        if (hasContact) {
            btVector3 contactToCentre = sphereCenter.sub(contactPoint);
            double distanceSqr = contactToCentre.length2();

            if (distanceSqr < radiusWithThreshold * radiusWithThreshold) {
                if (distanceSqr > btScalar.SIMD_EPSILON) {
                    double distance = btScalar.btSqrt(distanceSqr);
                    resultNormal.set(contactToCentre);
                    resultNormal.normalize();
                    point.set(contactPoint);
                    depth[0] = -(radius - distance);
                } else {
                    resultNormal.set(normal);
                    point.set(contactPoint);
                    depth[0] = -radius;
                }
                return true;
            }
        }

        return false;
    }

    boolean pointInTriangle(btVector3[] vertices, btVector3 normal, btVector3 p) {
        btVector3 p1 = vertices[0];
        btVector3 p2 = vertices[1];
        btVector3 p3 = vertices[2];

        btVector3 edge1 = p2.sub(p1);
        btVector3 edge2 = p3.sub(p2);
        btVector3 edge3 = p1.sub(p3);

        btVector3 p1_to_p = p.sub(p1);
        btVector3 p2_to_p = p.sub(p2);
        btVector3 p3_to_p = p.sub(p3);

        btVector3 edge1_normal = edge1.cross(normal);
        btVector3 edge2_normal = edge2.cross(normal);
        btVector3 edge3_normal = edge3.cross(normal);

        double r1, r2, r3;
        r1 = edge1_normal.dot(p1_to_p);
        r2 = edge2_normal.dot(p2_to_p);
        r3 = edge3_normal.dot(p3_to_p);
        if ((r1 > 0 && r2 > 0 && r3 > 0) || (r1 <= 0 && r2 <= 0 && r3 <= 0)) return true;
        return false;
    }
}
