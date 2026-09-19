// Port of LinearMath/btGrahamScan2dConvexHull.h (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

import java.util.function.BiPredicate;

/**
 * 2D Graham scan. Arrays are value-mode {@code btAlignedObjectArray<GrahamVector3>} (use {@link
 * #newArray()}); {@code push_back} and {@code swap} copy contents like C++. The anchor angle is
 * {@code (double) -1e30f}. The hull loop is ported verbatim, including its quirk that a point is
 * dropped when the hull has shrunk to one element (the while loop exits without pushing).
 */
public final class btGrahamScan2dConvexHull {
    private btGrahamScan2dConvexHull() {}

    public static class GrahamVector3 extends btVector3 {
        public double m_angle;
        public int m_orgIndex;

        /** Used only as a value-mode slot factory (C++ has no default ctor). */
        public GrahamVector3() {}

        public GrahamVector3(btVector3 org, int orgIndex) {
            super(org);
            m_orgIndex = orgIndex;
        }

        /** Implicit copy-assignment (all members incl. w, m_angle, m_orgIndex). */
        public GrahamVector3 assign(GrahamVector3 o) {
            x = o.x;
            y = o.y;
            z = o.z;
            w = o.w;
            m_angle = o.m_angle;
            m_orgIndex = o.m_orgIndex;
            return this;
        }
    }

    /** Value-mode {@code btAlignedObjectArray<GrahamVector3>}. */
    public static btAlignedObjectArray<GrahamVector3> newArray() {
        return new btAlignedObjectArray<>(GrahamVector3::new, GrahamVector3::assign);
    }

    public static class btAngleCompareFunc implements BiPredicate<GrahamVector3, GrahamVector3> {
        public final btVector3 m_anchor = new btVector3();

        public btAngleCompareFunc(btVector3 anchor) {
            m_anchor.set(anchor);
        }

        @Override
        public boolean test(GrahamVector3 a, GrahamVector3 b) {
            if (a.m_angle != b.m_angle) return a.m_angle < b.m_angle;
            else {
                double al = (a.sub(m_anchor)).length2();
                double bl = (b.sub(m_anchor)).length2();
                if (al != bl) return al < bl;
                else {
                    return a.m_orgIndex < b.m_orgIndex;
                }
            }
        }
    }

    public static void GrahamScanConvexHull2D(
            btAlignedObjectArray<GrahamVector3> originalPoints,
            btAlignedObjectArray<GrahamVector3> hull,
            btVector3 normalAxis) {
        btVector3 axis0 = new btVector3(), axis1 = new btVector3();
        btVector3.btPlaneSpace1(normalAxis, axis0, axis1);

        if (originalPoints.size() <= 1) {
            for (int i = 0; i < originalPoints.size(); i++) hull.push_back(originalPoints.get(0));
            return;
        }
        // step1 : find anchor point with smallest projection on axis0 and move it to first location
        for (int i = 0; i < originalPoints.size(); i++) {
            double projL = originalPoints.get(i).dot(axis0);
            double projR = originalPoints.get(0).dot(axis0);
            if (projL < projR) {
                originalPoints.swap(0, i);
            }
        }

        // also precompute angles
        originalPoints.get(0).m_angle = (double) -1e30f;
        for (int i = 1; i < originalPoints.size(); i++) {
            btVector3 xvec = new btVector3(axis0);
            btVector3 ar = originalPoints.get(i).sub(originalPoints.get(0));
            originalPoints.get(i).m_angle =
                    btVector3.btCross(xvec, ar).dot(normalAxis) / ar.length();
        }

        // step 2: sort all points, based on 'angle' with this anchor
        btAngleCompareFunc comp = new btAngleCompareFunc(originalPoints.get(0));
        originalPoints.quickSortInternal(comp, 1, originalPoints.size() - 1);

        int i;
        for (i = 0; i < 2; i++) hull.push_back(originalPoints.get(i));

        // step 3: keep all 'convex' points and discard concave points (using back tracking)
        for (; i != originalPoints.size(); i++) {
            boolean isConvex = false;
            while (!isConvex && hull.size() > 1) {
                btVector3 a = hull.get(hull.size() - 2);
                btVector3 b = hull.get(hull.size() - 1);
                isConvex =
                        btVector3.btCross(a.sub(b), a.sub(originalPoints.get(i))).dot(normalAxis)
                                > 0;
                if (!isConvex) hull.pop_back();
                else hull.push_back(originalPoints.get(i));
            }
        }
    }
}
