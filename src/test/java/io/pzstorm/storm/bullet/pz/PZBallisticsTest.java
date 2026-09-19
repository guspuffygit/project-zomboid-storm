package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import org.junit.jupiter.api.Test;

/** PZBallistics muzzle / aim-reticle math, target part selection and the ballistics pools. */
class PZBallisticsTest implements UnitTest {

    private static void assertVec(btVector3 exp, btVector3 act, double tol) {
        assertEquals(exp.x, act.x, tol, "x");
        assertEquals(exp.y, act.y, tol, "y");
        assertEquals(exp.z, act.z, tol, "z");
    }

    private static void assertBasisBits(btMatrix3x3 exp, btMatrix3x3 act) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(
                        Double.doubleToRawLongBits(exp.getRow(r).get(c)),
                        Double.doubleToRawLongBits(act.getRow(r).get(c)),
                        "basis[" + r + "][" + c + "]");
            }
        }
    }

    @Test
    void bodyPartTargetPriorityIsUnsignedCompare() {
        assertEquals(2, PZBallistics.getBodyPartTargetPriority(0));
        assertEquals(1, PZBallistics.getBodyPartTargetPriority(1));
        assertEquals(0, PZBallistics.getBodyPartTargetPriority(2));
        assertEquals(3, PZBallistics.getBodyPartTargetPriority(3));
        assertEquals(3, PZBallistics.getBodyPartTargetPriority(10));
        // negative parts compare as huge unsigned values
        assertEquals(3, PZBallistics.getBodyPartTargetPriority(-1));
    }

    @Test
    void updateTargetBodyPartWritesFirstMatchingRecordOnly() {
        float[] out = {7, 1, 2, 3, 11, 9, 4, 5, 6, 11, 9, 7, 8, 9, 11};
        PZBallistics.updateTargetBodyPart(9, 4, out.length, out);
        assertEquals(11.0f, out[4]);
        assertEquals(4.0f, out[9]);
        assertEquals(11.0f, out[14]);
        // records at i >= n are not scanned
        PZBallistics.updateTargetBodyPart(7, 2, 5, out);
        assertEquals(2.0f, out[4]);
        float[] out2 = {1, 0, 0, 0, 11, 7, 0, 0, 0, 11};
        PZBallistics.updateTargetBodyPart(7, 3, 5, out2);
        assertEquals(11.0f, out2[9]);
    }

    @Test
    void muzzleAimDirectionRotatesForwardOntoDirection() {
        PZBallistics p = new PZBallistics(1);
        p.updateMuzzleAimDirection(1.0f, 0.0f, 0.0f);
        assertVec(
                new btVector3(1, 0, 0),
                p.m_transform.m_basis.mul(PZbtVector3.btVector3Forward),
                1e-15);
        for (btTransformHolder h : btTransformHolder.of(p)) {
            assertBasisBits(p.m_transform.m_basis, h.basis);
        }
    }

    @Test
    void muzzleAimDirectionOppositeForwardUsesPiAboutUp() {
        PZBallistics p = new PZBallistics(2);
        p.updateMuzzleAimDirection(0.0f, 0.0f, -1.0f);
        btMatrix3x3 exp = new btMatrix3x3();
        exp.setRotation(new btQuaternion(PZbtVector3.btVector3Up, btScalar.SIMD_PI));
        assertBasisBits(exp, p.m_transform.m_basis);
        assertVec(
                new btVector3(0, 0, -1),
                p.m_transform.m_basis.mul(PZbtVector3.btVector3Forward),
                1e-15);
    }

    @Test
    void muzzleAimDirectionAlongForwardGivesNaNBasis() {
        // Forward x Forward = 0; normalising the zero axis yields NaN (no guard in the binary).
        PZBallistics p = new PZBallistics(3);
        p.updateMuzzleAimDirection(0.0f, 0.0f, 1.0f);
        assertTrue(Double.isNaN(p.m_transform.m_basis.getRow(0).x));
    }

    @Test
    void updateSpacesRaysAndCyclesOffsetWithPeriodTen() {
        PZBallistics p = new PZBallistics(4);
        p.setRange(10.0f);
        p.update(1.0f, 2.0f, 3.0f);
        assertEquals(10, p.m_rayTransforms.size());
        for (int i = 0; i < 10; i++) {
            assertVec(new btVector3(1, 2, 3 + i), p.m_rayTransforms.get(i).m_origin, 0.0);
        }
        assertEquals((double) 0.1f, p.m_offset);
        p.update(1.0f, 2.0f, 3.0f);
        assertEquals(3.0 + (double) 0.1f, p.m_rayTransforms.get(0).m_origin.z);
        for (int k = 2; k < 9; k++) {
            p.update(0, 0, 0);
        }
        double nine = 0.0;
        for (int k = 0; k < 9; k++) {
            nine += (double) 0.1f;
        }
        assertEquals(nine, p.m_offset);
        // 9 * 0.1f + 0.1f = 1.0000000149 > 1.0: wraps to 0 on the 10th update
        p.update(0, 0, 0);
        assertEquals(0.0, p.m_offset);
    }

    @Test
    void updateWithZeroRangeDoesNothing() {
        PZBallistics p = new PZBallistics(5);
        p.update(1.0f, 2.0f, 3.0f);
        assertEquals(0.0, p.m_offset);
        assertEquals(0.0, p.m_transform.m_origin.x);
    }

    @Test
    void onlyTheFirstInstanceGetsAimTransforms() {
        new PZBallistics(6);
        PZBallistics second = new PZBallistics(7);
        assertEquals(0, second.m_aimTransforms.size());
        assertTrue(PZBallistics.aimShape != null);
    }

    @Test
    void aimReticleQuaternionCopiesToCameraAimAndCopies() {
        PZBallistics p = new PZBallistics(8);
        PZBallistics.resizeTransforms(p.m_aimTransforms, 10);
        btQuaternion q = new btQuaternion(new btVector3(0, 1, 0), Math.PI / 3);
        p.updateAimReticleQuaternion(q);
        btMatrix3x3 exp = new btMatrix3x3();
        exp.setRotation(q);
        assertBasisBits(exp, PZBallistics.cameraAimTransform.m_basis);
        assertBasisBits(exp, p.m_aimTransform.m_basis);
        for (int i = 0; i < 10; i++) {
            assertBasisBits(exp, p.m_aimTransforms.get(i).m_basis);
        }
    }

    @Test
    void aimReticleRotateComposesButCopiesGetOnlyTheDelta() {
        PZBallistics p = new PZBallistics(9);
        PZBallistics.resizeTransforms(p.m_aimTransforms, 3);
        p.updateAimReticleRotation(new btQuaternion(0, 0, 0, 1));
        btQuaternion q = new btQuaternion(new btVector3(0, 1, 0), Math.PI / 2);
        p.updateAimReticleRotate(q);
        p.updateAimReticleRotate(q);
        // reticle: 180 degrees about y
        assertVec(
                new btVector3(0, 0, -1),
                p.m_aimTransform.m_basis.mul(PZbtVector3.btVector3Forward),
                1e-15);
        btMatrix3x3 exp = new btMatrix3x3();
        exp.setRotation(q);
        for (int i = 0; i < 3; i++) {
            assertBasisBits(exp, p.m_aimTransforms.get(i).m_basis);
        }
    }

    @Test
    void updateMovesCameraAimToReticleAtRange() {
        PZBallistics p = new PZBallistics(10);
        p.updateAimReticleQuaternion(new btQuaternion(0, 0, 0, 1));
        p.updateAimReticlePosition(new btVector3(5, 0, 5));
        p.setRange(10.0f);
        p.update(0, 0, 0);
        assertVec(new btVector3(5, 0, 15), PZBallistics.cameraAimTransform.m_origin, 0.0);
    }

    @Test
    void ballisticsPoolReusesRemovedObjectsFrontFirst() {
        PZBallisticsPool pool = new PZBallisticsPool();
        PZBallistics a = pool.add(1);
        PZBallistics b = pool.add(2);
        assertEquals(2, pool.size());
        pool.remove(1);
        pool.remove(2);
        pool.remove(3);
        assertEquals(0, pool.size());
        // push_front: the last removed is reused first
        PZBallistics c = pool.add(5);
        assertSame(b, c);
        assertEquals(5, c.getId());
        assertSame(a, pool.add(6));
        assertNotSame(a, pool.add(7));
    }

    @Test
    void ballisticsPoolAddOfExistingKeyKeepsMappedObject() {
        PZBallisticsPool pool = new PZBallisticsPool();
        PZBallistics a = pool.add(1);
        PZBallistics b = pool.add(1);
        assertNotSame(a, b);
        assertSame(a, pool.get(1));
        assertEquals(1, pool.size());
    }

    /** Collects the ray-transform bases of a controller. */
    private record btTransformHolder(btMatrix3x3 basis) {
        static java.util.List<btTransformHolder> of(PZBallistics p) {
            java.util.List<btTransformHolder> l = new java.util.ArrayList<>();
            for (int i = 0; i < p.m_rayTransforms.size(); i++) {
                l.add(new btTransformHolder(p.m_rayTransforms.get(i).m_basis));
            }
            return l;
        }
    }
}
