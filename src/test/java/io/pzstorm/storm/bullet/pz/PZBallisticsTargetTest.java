package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Isometric projection and isometric AABB math of PZBallisticsTarget (no ragdoll needed). */
class PZBallisticsTargetTest implements UnitTest {

    /** The math under test does not use the target's state; skip the ragdoll-building ctor. */
    private static PZBallisticsTarget bare() throws ReflectiveOperationException {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        return (PZBallisticsTarget)
                unsafe.getClass()
                        .getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, PZBallisticsTarget.class);
    }

    @AfterEach
    void restoreStatics() {
        PZBallisticsTarget.isometricHeightScale = 1.0;
        PZBallistics.cameraAimTransform.m_basis.setRotation(new btQuaternion(0, 0, 0, 1));
    }

    @Test
    void projectIsometric() throws Exception {
        PZBallisticsTarget t = bare();
        btVector3 r = t.projectIsometric(new btVector3(1, 2, 3));
        assertEquals(-2.0, r.x);
        assertEquals(4.0, r.y);
        assertEquals(0.0, r.z);
        assertEquals(0.0, r.w);
        PZBallisticsTarget.isometricHeightScale = 2.0;
        r = t.projectIsometric(new btVector3(1, 2, 3));
        assertEquals(6.0, r.y);
    }

    @Test
    void isometricAabbFrom3DWithIdentityCamera() throws Exception {
        PZBallisticsTarget t = bare();
        PZBallistics.cameraAimTransform.m_basis.setRotation(new btQuaternion(0, 0, 0, 1));
        btVector3 mn = new btVector3(9, 9, 9);
        btVector3 mx = new btVector3(9, 9, 9);
        mn.w = 7;
        mx.w = 7;
        t.computeIsometricAabbFrom3D(new btVector3(-1, -2, -3), new btVector3(4, 5, 6), mn, mx);
        assertEquals(-1.0, mn.x);
        assertEquals(-2.0, mn.y);
        assertEquals(0.0, mn.z);
        assertEquals(0.0, mn.w);
        assertEquals(4.0, mx.x);
        assertEquals(5.0, mx.y);
        assertEquals(0.0, mx.z);
        assertEquals(0.0, mx.w);
    }

    @Test
    void isometricAabbFrom3DUsesInverseCameraRotationWithoutOrigin() throws Exception {
        PZBallisticsTarget t = bare();
        // camera rotated +90 degrees about y; rows of the inverse: el0 = (0,0,-1), el1 = (0,1,0)
        PZBallistics.cameraAimTransform.m_basis.setRotation(
                new btQuaternion(new btVector3(0, 1, 0), Math.PI / 2));
        PZBallistics.cameraAimTransform.m_origin.setValue(100, 100, 100);
        btVector3 mn = new btVector3();
        btVector3 mx = new btVector3();
        t.computeIsometricAabbFrom3D(new btVector3(-1, -2, -3), new btVector3(4, 5, 6), mn, mx);
        PZBallistics.cameraAimTransform.m_origin.setValue(0, 0, 0);
        assertEquals(-6.0, mn.x, 1e-14);
        assertEquals(3.0, mx.x, 1e-14);
        assertEquals(-2.0, mn.y, 1e-14);
        assertEquals(5.0, mx.y, 1e-14);
    }

    @Test
    void minMaxFollowMinsdMaxsdOperandOrder() {
        // minsd(acc, v) = acc < v ? acc : v: any NaN makes the compare false, so v wins, and
        // a signed-zero tie also returns v.
        assertTrue(Double.isNaN(PZBallisticsTarget.minsd(1.0, Double.NaN)));
        assertEquals(1.0, PZBallisticsTarget.minsd(Double.NaN, 1.0));
        assertTrue(Double.isNaN(PZBallisticsTarget.maxsd(1.0, Double.NaN)));
        assertEquals(-0.0, PZBallisticsTarget.minsd(0.0, -0.0));
        assertEquals(0.0, PZBallisticsTarget.minsd(-0.0, 0.0));
    }

    @Test
    void ignoredBodyPartsAreTheOddLimbSegments() throws Exception {
        PZBallisticsTarget t = bare();
        for (int p = 0; p < 11; p++) {
            boolean odd = p == 1 || p == 3 || p == 5 || p == 7 || p == 9;
            assertEquals(odd, t.ignoreBodyPartAabb(p), "part " + p);
        }
        assertFalse(t.ignoreBodyPartAabb(11));
    }
}
