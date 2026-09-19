package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtBroadphase;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionDispatcher;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.collision.dispatch.btDefaultCollisionConfiguration;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import org.junit.jupiter.api.Test;

/**
 * PZBallistics compound raycasts: a compound of a box child (x = -2, half extent 0.5) and a sphere
 * child (x = +2, radius 0.5), plus a plain box elsewhere.
 */
class PZBallisticsRaycastTest implements UnitTest {

    private static final double TOL = 1e-6;

    private final btCollisionWorld world;
    private final btCollisionObject compoundObj;
    private final btCollisionObject boxObj;

    PZBallisticsRaycastTest() {
        btDefaultCollisionConfiguration cfg = new btDefaultCollisionConfiguration();
        world = new btCollisionWorld(new btCollisionDispatcher(cfg), new btDbvtBroadphase(), cfg);
        btCompoundShape compound = new btCompoundShape();
        compound.addChildShape(at(-2, 0, 0), new btBoxShape(new btVector3(0.5, 0.5, 0.5)));
        compound.addChildShape(at(2, 0, 0), new btSphereShape(0.5));
        compoundObj = new btCollisionObject();
        compoundObj.setCollisionShape(compound);
        compoundObj.setWorldTransform(at(0, 0, 0));
        world.addCollisionObject(compoundObj);
        boxObj = new btCollisionObject();
        boxObj.setCollisionShape(new btBoxShape(new btVector3(1, 1, 1)));
        boxObj.setWorldTransform(at(0, 0, 50));
        world.addCollisionObject(boxObj);
        world.updateAabbs();
    }

    private static btTransform at(double x, double y, double z) {
        return new btTransform(new btQuaternion(0, 0, 0, 1), new btVector3(x, y, z));
    }

    private static void assertVec(double x, double y, double z, btVector3 v, double tol) {
        assertEquals(x, v.x, tol, "x");
        assertEquals(y, v.y, tol, "y");
        assertEquals(z, v.z, tol, "z");
    }

    @Test
    void allHitsExpandsCompoundIntoChildHits() {
        AllCompoundHitsRayResult r = new AllCompoundHitsRayResult();
        PZBallistics.raycastAllWithCompoundSupport(
                world, new btVector3(-10, 0, 0), new btVector3(10, 0, 0), r);
        // the world all-hits callback lists the compound once per child it hit (2), and each
        // entry is expanded into every child whose AABB the ray crosses: c0, c1, c0, c1
        assertEquals(4, r.hits.size());
        for (int k = 0; k < 2; k++) {
            assertEquals(r.hits.get(k).fraction, r.hits.get(k + 2).fraction);
            assertEquals(k, r.hits.get(k + 2).childIndex);
        }
        AllCompoundHitsRayResult.Hit h0 = r.hits.get(0);
        assertSame(compoundObj, h0.obj);
        assertEquals(0, h0.childIndex);
        assertEquals(0.375, h0.fraction, TOL);
        assertVec(-2.5, 0, 0, h0.hitPoint, 1e-5);
        assertVec(-1, 0, 0, h0.hitNormal, TOL);
        AllCompoundHitsRayResult.Hit h1 = r.hits.get(1);
        assertEquals(1, h1.childIndex);
        assertEquals(0.575, h1.fraction, TOL);
        assertVec(1.5, 0, 0, h1.hitPoint, 1e-5);
        assertVec(-1, 0, 0, h1.hitNormal, 1e-4);
    }

    @Test
    void allHitsKeepsChildWhoseAabbIsCrossedButShapeMissed() {
        // y = z = 0.45: hits the box child, crosses the sphere child's AABB but misses the sphere
        AllCompoundHitsRayResult r = new AllCompoundHitsRayResult();
        btVector3 to = new btVector3(10, 0.45, 0.45);
        PZBallistics.raycastAllWithCompoundSupport(world, new btVector3(-10, 0.45, 0.45), to, r);
        assertEquals(2, r.hits.size());
        AllCompoundHitsRayResult.Hit miss = r.hits.get(1);
        assertEquals(1, miss.childIndex);
        assertEquals(1.0, miss.fraction);
        assertEquals(to.x, miss.hitPoint.x);
        assertEquals(to.y, miss.hitPoint.y);
        assertEquals(to.z, miss.hitPoint.z);
        // normal is the stale value left by the previous child's callback
        assertEquals(r.hits.get(0).hitNormal.x, miss.hitNormal.x);
        assertEquals(r.hits.get(0).hitNormal.y, miss.hitNormal.y);
        assertEquals(r.hits.get(0).hitNormal.z, miss.hitNormal.z);
    }

    @Test
    void allHitsReportsPlainObjectWithChildIndexMinusOne() {
        AllCompoundHitsRayResult r = new AllCompoundHitsRayResult();
        PZBallistics.raycastAllWithCompoundSupport(
                world, new btVector3(0, 0, 40), new btVector3(0, 0, 60), r);
        assertEquals(1, r.hits.size());
        AllCompoundHitsRayResult.Hit h = r.hits.get(0);
        assertSame(boxObj, h.obj);
        assertEquals(-1, h.childIndex);
        assertEquals(0.45, h.fraction, TOL);
        assertVec(0, 0, 49, h.hitPoint, 1e-5);
        assertVec(0, 0, -1, h.hitNormal, TOL);
    }

    @Test
    void closestPicksNearestChildInEitherDirection() {
        AllCompoundHitsRayResult r = new AllCompoundHitsRayResult();
        PZBallistics.raycastClosestWithCompoundSupport(
                world, new btVector3(-10, 0, 0), new btVector3(10, 0, 0), r);
        assertEquals(1, r.hits.size());
        assertEquals(0, r.hits.get(0).childIndex);
        assertEquals(0.375, r.hits.get(0).fraction, TOL);

        r = new AllCompoundHitsRayResult();
        PZBallistics.raycastClosestWithCompoundSupport(
                world, new btVector3(10, 0, 0), new btVector3(-10, 0, 0), r);
        assertEquals(1, r.hits.size());
        AllCompoundHitsRayResult.Hit h = r.hits.get(0);
        assertEquals(1, h.childIndex);
        assertEquals(0.375, h.fraction, TOL);
        assertVec(2.5, 0, 0, h.hitPoint, 1e-5);
        assertVec(1, 0, 0, h.hitNormal, 1e-4);
    }

    @Test
    void closestIgnoresMissedChildAndMissesNothing() {
        AllCompoundHitsRayResult r = new AllCompoundHitsRayResult();
        PZBallistics.raycastClosestWithCompoundSupport(
                world, new btVector3(10, 0.45, 0.45), new btVector3(-10, 0.45, 0.45), r);
        assertEquals(1, r.hits.size());
        assertEquals(0, r.hits.get(0).childIndex);
        assertEquals(0.575, r.hits.get(0).fraction, TOL);

        r = new AllCompoundHitsRayResult();
        PZBallistics.raycastClosestWithCompoundSupport(
                world, new btVector3(0, 10, 20), new btVector3(0, -10, 20), r);
        assertEquals(0, r.hits.size());
    }

    @Test
    void closestReportsPlainObject() {
        AllCompoundHitsRayResult r = new AllCompoundHitsRayResult();
        PZBallistics.raycastClosestWithCompoundSupport(
                world, new btVector3(0, 0, 60), new btVector3(0, 0, 40), r);
        assertEquals(1, r.hits.size());
        assertSame(boxObj, r.hits.get(0).obj);
        assertEquals(-1, r.hits.get(0).childIndex);
        assertEquals(0.45, r.hits.get(0).fraction, TOL);
    }
}
