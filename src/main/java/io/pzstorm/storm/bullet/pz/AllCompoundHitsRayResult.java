// Port of PZ AllCompoundHitsRayResult (PZBallistics.cpp) from decomp @0016e350 (vector<Hit>
// realloc)
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;

/**
 * Result sink of {@link PZBallistics#raycastAllWithCompoundSupport} / {@link
 * PZBallistics#raycastClosestWithCompoundSupport}: a plain {@code std::vector<Hit>} (no base class,
 * no virtuals).
 */
public class AllCompoundHitsRayResult {

    /** {@code struct Hit} (0x60 bytes). */
    public static class Hit {
        /** +0x00 */
        public btCollisionObject obj;

        /** +0x08: the hit shape (compound child shape, or the object's shape). */
        public btCollisionShape shape;

        /** +0x10: compound child index, -1 when the object is not compound. */
        public int childIndex;

        /** +0x18 */
        public final btVector3 hitPoint = new btVector3();

        /** +0x38 */
        public final btVector3 hitNormal = new btVector3();

        /** +0x58 */
        public double fraction;

        public Hit() {}

        public Hit(
                btCollisionObject obj,
                btCollisionShape shape,
                int childIndex,
                btVector3 hitPoint,
                btVector3 hitNormal,
                double fraction) {
            this.obj = obj;
            this.shape = shape;
            this.childIndex = childIndex;
            this.hitPoint.set(hitPoint);
            this.hitNormal.set(hitNormal);
            this.fraction = fraction;
        }

        /** Implicit copy constructor ({@code push_back(const Hit&)}). */
        public Hit(Hit o) {
            this(o.obj, o.shape, o.childIndex, o.hitPoint, o.hitNormal, o.fraction);
        }
    }

    /** +0x00 {@code std::vector<Hit>} */
    public final ArrayList<Hit> hits = new ArrayList<>();
}
