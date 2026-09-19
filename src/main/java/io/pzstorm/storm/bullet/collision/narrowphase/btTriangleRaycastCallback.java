// Port of BulletCollision/NarrowPhaseCollision/btRaycastCallback.{h,cpp} (Bullet 2.82):
// btTriangleRaycastCallback
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btTriangleCallback;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btTriangleRaycastCallback extends btTriangleCallback {
    // input
    public final btVector3 m_from = new btVector3();
    public final btVector3 m_to = new btVector3();

    // @BP Mod - allow backface filtering and unflipped normals
    // enum EFlags
    public static final int kF_None = 0;
    public static final int kF_FilterBackfaces = 1 << 0;

    /** Prevents returned face normal getting flipped when a ray hits a back-facing triangle */
    public static final int kF_KeepUnflippedNormal = 1 << 1;

    /** Uses an approximate but faster ray versus convex intersection algorithm */
    public static final int kF_UseSubSimplexConvexCastRaytest = 1 << 2;

    public static final int kF_Terminator = 0xFFFFFFFF;

    /** unsigned int */
    public int m_flags;

    public double m_hitFraction;

    public btTriangleRaycastCallback(btVector3 from, btVector3 to) {
        this(from, to, 0);
    }

    public btTriangleRaycastCallback(btVector3 from, btVector3 to, int flags) {
        m_from.set(from);
        m_to.set(to);
        // @BP Mod
        m_flags = flags;
        m_hitFraction = 1.;
    }

    @Override
    public void processTriangle(btVector3[] triangle, int partId, int triangleIndex) {
        final btVector3 vert0 = triangle[0];
        final btVector3 vert1 = triangle[1];
        final btVector3 vert2 = triangle[2];

        btVector3 v10 = vert1.sub(vert0);
        btVector3 v20 = vert2.sub(vert0);

        btVector3 triangleNormal = v10.cross(v20);

        final double dist = vert0.dot(triangleNormal);
        double dist_a = triangleNormal.dot(m_from);
        dist_a -= dist;
        double dist_b = triangleNormal.dot(m_to);
        dist_b -= dist;

        if (dist_a * dist_b >= 0.0) {
            return; // same sign
        }

        if (((m_flags & kF_FilterBackfaces) != 0) && (dist_a <= 0.0)) {
            // Backface, skip check
            return;
        }

        final double proj_length = dist_a - dist_b;
        final double distance = (dist_a) / (proj_length);
        // Now we have the intersection point on the plane, we'll see if it's inside the triangle
        // Add an epsilon as a tolerance for the raycast,
        // in case the ray hits exacly on the edge of the triangle.
        // It must be scaled for the triangle size.

        if (distance < m_hitFraction) {

            double edge_tolerance = triangleNormal.length2();
            edge_tolerance *= -0.0001;
            btVector3 point = new btVector3();
            point.setInterpolate3(m_from, m_to, distance);
            {
                btVector3 v0p = vert0.sub(point);
                btVector3 v1p = vert1.sub(point);
                btVector3 cp0 = v0p.cross(v1p);

                if ((cp0.dot(triangleNormal)) >= edge_tolerance) {

                    btVector3 v2p = vert2.sub(point);
                    btVector3 cp1 = v1p.cross(v2p);
                    if ((cp1.dot(triangleNormal)) >= edge_tolerance) {
                        btVector3 cp2 = v2p.cross(v0p);

                        if ((cp2.dot(triangleNormal)) >= edge_tolerance) {
                            // @BP Mod
                            // Triangle normal isn't normalized
                            triangleNormal.normalize();

                            // @BP Mod - Allow for unflipped normal when raycasting against
                            // backfaces
                            if (((m_flags & kF_KeepUnflippedNormal) == 0) && (dist_a <= 0.0)) {
                                m_hitFraction =
                                        reportHit(
                                                triangleNormal.negate(),
                                                distance,
                                                partId,
                                                triangleIndex);
                            } else {
                                m_hitFraction =
                                        reportHit(triangleNormal, distance, partId, triangleIndex);
                            }
                        }
                    }
                }
            }
        }
    }

    public abstract double reportHit(
            btVector3 hitNormalLocal, double hitFraction, int partId, int triangleIndex);
}
