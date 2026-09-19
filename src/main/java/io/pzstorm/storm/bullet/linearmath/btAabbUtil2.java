// Port of LinearMath/btAabbUtil2.h (Bullet 2.82), BT_USE_DOUBLE_PRECISION, USE_BANCHLESS defined.
package io.pzstorm.storm.bullet.linearmath;

/**
 * Free functions of btAabbUtil2.h. Output references: {@code btVector3&} outputs are written with
 * {@code set} (C++ assignment, which copies w; every assigned value here is an operator result with
 * w=0); {@code btScalar&} is {@code double[]} element 0. Quantized AABBs ({@code unsigned short*})
 * are {@code int[]} (values 0..65535) with an offset.
 */
public final class btAabbUtil2 {
    private btAabbUtil2() {}

    public static void AabbExpand(
            btVector3 aabbMin, btVector3 aabbMax, btVector3 expansionMin, btVector3 expansionMax) {
        aabbMin.set(aabbMin.add(expansionMin));
        aabbMax.set(aabbMax.add(expansionMax));
    }

    public static boolean TestPointAgainstAabb2(
            btVector3 aabbMin1, btVector3 aabbMax1, btVector3 point) {
        boolean overlap = true;
        overlap =
                (aabbMin1.getX() > point.getX() || aabbMax1.getX() < point.getX())
                        ? false
                        : overlap;
        overlap =
                (aabbMin1.getZ() > point.getZ() || aabbMax1.getZ() < point.getZ())
                        ? false
                        : overlap;
        overlap =
                (aabbMin1.getY() > point.getY() || aabbMax1.getY() < point.getY())
                        ? false
                        : overlap;
        return overlap;
    }

    public static boolean TestAabbAgainstAabb2(
            btVector3 aabbMin1, btVector3 aabbMax1, btVector3 aabbMin2, btVector3 aabbMax2) {
        boolean overlap = true;
        overlap =
                (aabbMin1.getX() > aabbMax2.getX() || aabbMax1.getX() < aabbMin2.getX())
                        ? false
                        : overlap;
        overlap =
                (aabbMin1.getZ() > aabbMax2.getZ() || aabbMax1.getZ() < aabbMin2.getZ())
                        ? false
                        : overlap;
        overlap =
                (aabbMin1.getY() > aabbMax2.getY() || aabbMax1.getY() < aabbMin2.getY())
                        ? false
                        : overlap;
        return overlap;
    }

    /** {@code const btVector3* vertices}: vertices[off], [off+1], [off+2]. */
    public static boolean TestTriangleAgainstAabb2(
            btVector3[] vertices, int off, btVector3 aabbMin, btVector3 aabbMax) {
        btVector3 p1 = vertices[off];
        btVector3 p2 = vertices[off + 1];
        btVector3 p3 = vertices[off + 2];
        return TestTriangleAgainstAabb2(p1, p2, p3, aabbMin, aabbMax);
    }

    public static boolean TestTriangleAgainstAabb2(
            btVector3[] vertices, btVector3 aabbMin, btVector3 aabbMax) {
        return TestTriangleAgainstAabb2(vertices, 0, aabbMin, aabbMax);
    }

    /** Same test with the three vertices passed separately. */
    public static boolean TestTriangleAgainstAabb2(
            btVector3 p1, btVector3 p2, btVector3 p3, btVector3 aabbMin, btVector3 aabbMax) {
        if (btMinMax.btMin(btMinMax.btMin(p1.x, p2.x), p3.x) > aabbMax.x) return false;
        if (btMinMax.btMax(btMinMax.btMax(p1.x, p2.x), p3.x) < aabbMin.x) return false;

        if (btMinMax.btMin(btMinMax.btMin(p1.z, p2.z), p3.z) > aabbMax.z) return false;
        if (btMinMax.btMax(btMinMax.btMax(p1.z, p2.z), p3.z) < aabbMin.z) return false;

        if (btMinMax.btMin(btMinMax.btMin(p1.y, p2.y), p3.y) > aabbMax.y) return false;
        if (btMinMax.btMax(btMinMax.btMax(p1.y, p2.y), p3.y) < aabbMin.y) return false;
        return true;
    }

    public static int btOutcode(btVector3 p, btVector3 halfExtent) {
        return (p.getX() < -halfExtent.getX() ? 0x01 : 0x0)
                | (p.getX() > halfExtent.getX() ? 0x08 : 0x0)
                | (p.getY() < -halfExtent.getY() ? 0x02 : 0x0)
                | (p.getY() > halfExtent.getY() ? 0x10 : 0x0)
                | (p.getZ() < -halfExtent.getZ() ? 0x4 : 0x0)
                | (p.getZ() > halfExtent.getZ() ? 0x20 : 0x0);
    }

    /** raySign: {@code unsigned int[3]} (0 or 1); bounds: {@code btVector3[2]}; tmin: out[0]. */
    public static boolean btRayAabb2(
            btVector3 rayFrom,
            btVector3 rayInvDirection,
            int[] raySign,
            btVector3[] bounds,
            double[] tminOut,
            double lambda_min,
            double lambda_max) {
        double tmin, tmax, tymin, tymax, tzmin, tzmax;
        tmin = (bounds[raySign[0]].getX() - rayFrom.getX()) * rayInvDirection.getX();
        tmax = (bounds[1 - raySign[0]].getX() - rayFrom.getX()) * rayInvDirection.getX();
        tymin = (bounds[raySign[1]].getY() - rayFrom.getY()) * rayInvDirection.getY();
        tymax = (bounds[1 - raySign[1]].getY() - rayFrom.getY()) * rayInvDirection.getY();
        tminOut[0] = tmin;

        if ((tmin > tymax) || (tymin > tmax)) return false;

        if (tymin > tmin) tmin = tymin;

        if (tymax < tmax) tmax = tymax;

        tminOut[0] = tmin;

        tzmin = (bounds[raySign[2]].getZ() - rayFrom.getZ()) * rayInvDirection.getZ();
        tzmax = (bounds[1 - raySign[2]].getZ() - rayFrom.getZ()) * rayInvDirection.getZ();

        if ((tmin > tzmax) || (tzmin > tmax)) return false;
        if (tzmin > tmin) tmin = tzmin;
        if (tzmax < tmax) tmax = tzmax;
        tminOut[0] = tmin;
        return ((tmin < lambda_max) && (tmax > lambda_min));
    }

    /** param: in/out [0]; normal: out. */
    public static boolean btRayAabb(
            btVector3 rayFrom,
            btVector3 rayTo,
            btVector3 aabbMin,
            btVector3 aabbMax,
            double[] param,
            btVector3 normal) {
        btVector3 aabbHalfExtent = (aabbMax.sub(aabbMin)).mul(0.5);
        btVector3 aabbCenter = (aabbMax.add(aabbMin)).mul(0.5);
        btVector3 source = rayFrom.sub(aabbCenter);
        btVector3 target = rayTo.sub(aabbCenter);
        int sourceOutcode = btOutcode(source, aabbHalfExtent);
        int targetOutcode = btOutcode(target, aabbHalfExtent);
        if ((sourceOutcode & targetOutcode) == 0x0) {
            double lambda_enter = 0.0;
            double lambda_exit = param[0];
            btVector3 r = target.sub(source);
            int i;
            double normSign = 1;
            btVector3 hitNormal = new btVector3(0, 0, 0);
            int bit = 1;

            for (int j = 0; j < 2; j++) {
                for (i = 0; i != 3; ++i) {
                    if ((sourceOutcode & bit) != 0) {
                        double lambda =
                                (-source.get(i) - aabbHalfExtent.get(i) * normSign) / r.get(i);
                        if (lambda_enter <= lambda) {
                            lambda_enter = lambda;
                            hitNormal.setValue(0, 0, 0);
                            hitNormal.set(i, normSign);
                        }
                    } else if ((targetOutcode & bit) != 0) {
                        double lambda =
                                (-source.get(i) - aabbHalfExtent.get(i) * normSign) / r.get(i);
                        lambda_exit = btMinMax.btSetMin(lambda_exit, lambda);
                    }
                    bit <<= 1;
                }
                normSign = -1.;
            }
            if (lambda_enter <= lambda_exit) {
                param[0] = lambda_enter;
                normal.set(hitNormal);
                return true;
            }
        }
        return false;
    }

    public static void btTransformAabb(
            btVector3 halfExtents,
            double margin,
            btTransform t,
            btVector3 aabbMinOut,
            btVector3 aabbMaxOut) {
        btVector3 halfExtentsWithMargin = halfExtents.add(new btVector3(margin, margin, margin));
        btMatrix3x3 abs_b = t.getBasis().absolute();
        btVector3 center = new btVector3(t.getOrigin());
        btVector3 extent = halfExtentsWithMargin.dot3(abs_b.m_el0, abs_b.m_el1, abs_b.m_el2);
        aabbMinOut.set(center.sub(extent));
        aabbMaxOut.set(center.add(extent));
    }

    public static void btTransformAabb(
            btVector3 localAabbMin,
            btVector3 localAabbMax,
            double margin,
            btTransform trans,
            btVector3 aabbMinOut,
            btVector3 aabbMaxOut) {
        btVector3 localHalfExtents = (localAabbMax.sub(localAabbMin)).mul(0.5);
        localHalfExtents.addLocal(new btVector3(margin, margin, margin));

        btVector3 localCenter = (localAabbMax.add(localAabbMin)).mul(0.5);
        btMatrix3x3 abs_b = trans.getBasis().absolute();
        btVector3 center = trans.transform(localCenter);
        btVector3 extent = localHalfExtents.dot3(abs_b.m_el0, abs_b.m_el1, abs_b.m_el2);
        aabbMinOut.set(center.sub(extent));
        aabbMaxOut.set(center.add(extent));
    }

    /** USE_BANCHLESS variant; returns 1 or 0 (unsigned). */
    public static int testQuantizedAabbAgainstQuantizedAabb(
            int[] aabbMin1,
            int o1,
            int[] aabbMax1,
            int o2,
            int[] aabbMin2,
            int o3,
            int[] aabbMax2,
            int o4) {
        int cond =
                ((aabbMin1[o1] <= aabbMax2[o4]) ? 1 : 0)
                        & ((aabbMax1[o2] >= aabbMin2[o3]) ? 1 : 0)
                        & ((aabbMin1[o1 + 2] <= aabbMax2[o4 + 2]) ? 1 : 0)
                        & ((aabbMax1[o2 + 2] >= aabbMin2[o3 + 2]) ? 1 : 0)
                        & ((aabbMin1[o1 + 1] <= aabbMax2[o4 + 1]) ? 1 : 0)
                        & ((aabbMax1[o2 + 1] >= aabbMin2[o3 + 1]) ? 1 : 0);
        return btScalar.btSelect(cond, 1, 0);
    }

    public static int testQuantizedAabbAgainstQuantizedAabb(
            int[] aabbMin1, int[] aabbMax1, int[] aabbMin2, int[] aabbMax2) {
        return testQuantizedAabbAgainstQuantizedAabb(
                aabbMin1, 0, aabbMax1, 0, aabbMin2, 0, aabbMax2, 0);
    }
}
