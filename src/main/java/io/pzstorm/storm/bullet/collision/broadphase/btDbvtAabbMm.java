// Port of BulletCollision/BroadphaseCollision/btDbvt.h (Bullet 2.82), struct btDbvtAabbMm
// (btDbvtVolume) and its friend functions Intersect/Proximity/Select/Merge/NotEqual (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Value type: C++ {@code a = b} is {@link #set(btDbvtAabbMm)}. The binary is built without
 * BT_USE_SSE, so Intersect/Select/Merge are the DBVT_IMPL_GENERIC versions (Merge touches only
 * components 0..2 and leaves {@code w} alone).
 *
 * <p>Unlinked members not ported: FromPoints (both), Classify, ProjectMinimum, AddSpan.
 */
public class btDbvtAabbMm {
    public final btVector3 mi = new btVector3();
    public final btVector3 mx = new btVector3();

    public btDbvtAabbMm() {}

    public btDbvtAabbMm(btDbvtAabbMm other) {
        set(other);
    }

    /** Implicit copy-assignment (copies all four lanes of mi and mx). */
    public btDbvtAabbMm set(btDbvtAabbMm other) {
        mi.set(other.mi);
        mx.set(other.mx);
        return this;
    }

    public btVector3 Center() {
        return (mi.add(mx)).div(2);
    }

    public btVector3 Lengths() {
        return (mx.sub(mi));
    }

    public btVector3 Extents() {
        return (mx.sub(mi)).div(2);
    }

    /** Returns the member itself (C++ {@code const btVector3&}). */
    public btVector3 Mins() {
        return (mi);
    }

    /** Returns the member itself (C++ {@code const btVector3&}). */
    public btVector3 Maxs() {
        return (mx);
    }

    public btVector3 tMins() {
        return (mi);
    }

    public btVector3 tMaxs() {
        return (mx);
    }

    public static btDbvtAabbMm FromCE(btVector3 c, btVector3 e) {
        btDbvtAabbMm box = new btDbvtAabbMm();
        box.mi.set(c.sub(e));
        box.mx.set(c.add(e));
        return (box);
    }

    public static btDbvtAabbMm FromCR(btVector3 c, double r) {
        return (FromCE(c, new btVector3(r, r, r)));
    }

    public static btDbvtAabbMm FromMM(btVector3 mi, btVector3 mx) {
        btDbvtAabbMm box = new btDbvtAabbMm();
        box.mi.set(mi);
        box.mx.set(mx);
        return (box);
    }

    public void Expand(btVector3 e) {
        mi.subLocal(e);
        mx.addLocal(e);
    }

    public void SignedExpand(btVector3 e) {
        if (e.x() > 0) mx.setX(mx.x() + e.get(0));
        else mi.setX(mi.x() + e.get(0));
        if (e.y() > 0) mx.setY(mx.y() + e.get(1));
        else mi.setY(mi.y() + e.get(1));
        if (e.z() > 0) mx.setZ(mx.z() + e.get(2));
        else mi.setZ(mi.z() + e.get(2));
    }

    public boolean Contain(btDbvtAabbMm a) {
        return ((mi.x() <= a.mi.x())
                && (mi.y() <= a.mi.y())
                && (mi.z() <= a.mi.z())
                && (mx.x() >= a.mx.x())
                && (mx.y() >= a.mx.y())
                && (mx.z() >= a.mx.z()));
    }

    /** {@code Intersect(const btDbvtAabbMm&, const btDbvtAabbMm&)}, generic implementation. */
    public static boolean Intersect(btDbvtAabbMm a, btDbvtAabbMm b) {
        return ((a.mi.x() <= b.mx.x())
                && (a.mx.x() >= b.mi.x())
                && (a.mi.y() <= b.mx.y())
                && (a.mx.y() >= b.mi.y())
                && (a.mi.z() <= b.mx.z())
                && (a.mx.z() >= b.mi.z()));
    }

    /** {@code Intersect(const btDbvtAabbMm&, const btVector3&)}. */
    public static boolean Intersect(btDbvtAabbMm a, btVector3 b) {
        return ((b.x() >= a.mi.x())
                && (b.y() >= a.mi.y())
                && (b.z() >= a.mi.z())
                && (b.x() <= a.mx.x())
                && (b.y() <= a.mx.y())
                && (b.z() <= a.mx.z()));
    }

    public static double Proximity(btDbvtAabbMm a, btDbvtAabbMm b) {
        final btVector3 d = (a.mi.add(a.mx)).sub(b.mi.add(b.mx));
        return (btScalar.btFabs(d.x()) + btScalar.btFabs(d.y()) + btScalar.btFabs(d.z()));
    }

    /** Generic implementation: {@code Proximity(o,a)<Proximity(o,b)?0:1}. */
    public static int Select(btDbvtAabbMm o, btDbvtAabbMm a, btDbvtAabbMm b) {
        return (Proximity(o, a) < Proximity(o, b) ? 0 : 1);
    }

    /** Generic implementation (components 0..2 only; r may alias a or b). */
    public static void Merge(btDbvtAabbMm a, btDbvtAabbMm b, btDbvtAabbMm r) {
        for (int i = 0; i < 3; ++i) {
            if (a.mi.get(i) < b.mi.get(i)) r.mi.set(i, a.mi.get(i));
            else r.mi.set(i, b.mi.get(i));
            if (a.mx.get(i) > b.mx.get(i)) r.mx.set(i, a.mx.get(i));
            else r.mx.set(i, b.mx.get(i));
        }
    }

    public static boolean NotEqual(btDbvtAabbMm a, btDbvtAabbMm b) {
        return ((a.mi.x() != b.mi.x())
                || (a.mi.y() != b.mi.y())
                || (a.mi.z() != b.mi.z())
                || (a.mx.x() != b.mx.x())
                || (a.mx.y() != b.mx.y())
                || (a.mx.z() != b.mx.z()));
    }
}
