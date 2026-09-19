// Port of PZ PZBallistics (PZBallistics.cpp) from decomp @0016baa0 (ctor), @0016b980 (initialize),
// @00169200..@0016d920 (methods)
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShapeChild;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.dynamics.btDynamicsWorld;
import io.pzstorm.storm.bullet.libm.GlibcMath;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;

/**
 * Weapon ballistics probe: a muzzle transform with a ray of {@code range}, debug spheres along it
 * and an aim reticle. Object size 0x1d8.
 *
 * <p>Uninitialised C++ memory is zeroed in Java: the 10 transforms of {@link #m_rayTransforms}
 * ({@code std::vector<btTransform>::resize} default-appends a type with an empty user ctor), and
 * {@link #m_aimTransform} of every instance except the first one constructed in the process ({@link
 * #initialize()} only runs while {@link #aimShape} is null; that first instance is also the only
 * one whose {@link #m_aimTransforms} gets 10 elements -- all others keep it empty).
 */
public class PZBallistics {

    /** +0x00 muzzle transform. */
    public final btTransform m_transform = new btTransform();

    /** +0x80 {@code std::vector<btTransform>} (10): debug spheres along the ray. */
    public final ArrayList<btTransform> m_rayTransforms = new ArrayList<>();

    /** +0x98 {@code new btBoxShape(btVector3One)} */
    public btBoxShape m_shape;

    /** +0xa0 */
    public double m_range;

    /** +0xa8 animated sphere offset along the ray, steps by (double)0.1f. */
    public double m_offset;

    /** +0xb0 */
    public int m_id = -1;

    /** +0xb8 (Cyan) colour stamped on targets hit by getTargets(range, max, out). */
    public final btVector3 m_color = new btVector3();

    /** +0xd8 (Cyan) colour stamped on camera-ray targets. */
    public final btVector3 m_cameraTargetColor = new btVector3();

    /** +0xf8 (Green) colour stamped on getSpreadTargetData targets. */
    public final btVector3 m_spreadTargetColor = new btVector3();

    /** +0x118 aim reticle transform (origin at +0x178). */
    public final btTransform m_aimTransform = new btTransform();

    /** +0x198 {@code new btSphereShape(1.0)} */
    public btSphereShape m_sphereShape;

    /** +0x1a0 (Blue) */
    public final btVector3 m_debugColor = new btVector3();

    /** +0x1c0 {@code std::vector<btTransform>}: reticle copies along the aim ray. */
    public final ArrayList<btTransform> m_aimTransforms = new ArrayList<>();

    /** static {@code btBoxShape* aimShape} (0.2f half extents). */
    public static btBoxShape aimShape;

    /** static {@code btTransform cameraAimTransform} (.bss: all zero, not identity). */
    public static final btTransform cameraAimTransform = new btTransform();

    /** Stale-stack emulation of the uninitialised {@code m_hitNormalWorld}, see raycastAll. */
    private static final btVector3 staleChildNormal = new btVector3();

    /** {@code PZBallistics::PZBallistics(int)} @0016baa0 */
    public PZBallistics(int id) {
        initialize();
        m_id = id;
        m_transform.set(btTransform.getIdentity());
        m_color.set(PZbtVector3.Cyan);
        m_cameraTargetColor.set(PZbtVector3.Cyan);
        m_spreadTargetColor.set(PZbtVector3.Green);
        m_shape = new btBoxShape(PZbtVector3.btVector3One);
        m_sphereShape = new btSphereShape(1.0);
        m_debugColor.set(PZbtVector3.Blue);
        resizeTransforms(m_rayTransforms, 10);
    }

    /** {@code std::vector<btTransform>::resize(n)} (new elements: C++ uninitialised, Java zero). */
    static void resizeTransforms(ArrayList<btTransform> v, int n) {
        while (v.size() < n) {
            v.add(new btTransform());
        }
        while (v.size() > n) {
            v.remove(v.size() - 1);
        }
    }

    /** {@code PZBallistics::initialize()} @0016b980 */
    public void initialize() {
        if (aimShape == null) {
            m_aimTransform.set(btTransform.getIdentity());
            aimShape = new btBoxShape(new btVector3((double) 0.2f, (double) 0.2f, (double) 0.2f));
            resizeTransforms(m_aimTransforms, 10);
        }
    }

    /**
     * @001691e0
     */
    public void setColor(btVector3 color) {
        m_color.set(color);
    }

    /**
     * @00169200
     */
    public void setSize(float size) {
        m_shape.setLocalScaling(new btVector3(size, size, size));
        m_sphereShape.setLocalScaling(new btVector3(size, size, size));
    }

    /**
     * @00169290
     */
    public void setRange(float range) {
        m_range = range;
    }

    /**
     * @001692a0
     */
    public void reset(int id) {
        m_id = id;
    }

    /**
     * @001692b0
     */
    public int getId() {
        return m_id;
    }

    /**
     * @001692c0
     */
    public void updateAimReticlePosition(btVector3 position) {
        m_aimTransform.m_origin.set(position);
    }

    /**
     * @001692e0
     */
    public void updateAimReticleRotation(btQuaternion rotation) {
        m_aimTransform.m_basis.setRotation(rotation);
        for (int i = 0; i < m_aimTransforms.size(); i++) {
            m_aimTransforms.get(i).m_basis.set(m_aimTransform.m_basis);
        }
    }

    /**
     * @001694c0
     */
    public void updateAimReticleQuaternion(btQuaternion rotation) {
        cameraAimTransform.m_basis.setRotation(rotation);
        m_aimTransform.m_basis.set(cameraAimTransform.m_basis);
        for (int i = 0; i < m_aimTransforms.size(); i++) {
            m_aimTransforms.get(i).m_basis.set(cameraAimTransform.m_basis);
        }
    }

    /**
     * @0016b340: the reticle is rotated by {@code q}, but the reticle copies get {@code q} alone
     * (not the composed rotation) -- binary quirk.
     */
    public void updateAimReticleRotate(btQuaternion q) {
        btQuaternion r = m_aimTransform.m_basis.getRotation().mul(q);
        m_aimTransform.m_basis.setRotation(r);
        for (int i = 0; i < m_aimTransforms.size(); i++) {
            m_aimTransforms.get(i).m_basis.setRotation(q);
        }
    }

    /**
     * @0016ace0 (acos without clamp).
     */
    public void updateMuzzleAimDirection(float x, float y, float z) {
        btVector3 dir = new btVector3(x, y, z);
        btVector3 d = PZbtVector3.btVector3Forward.negate().sub(dir);
        btQuaternion q;
        if (d.length() < 1e-5) {
            q = new btQuaternion(PZbtVector3.btVector3Up, btScalar.SIMD_PI);
        } else {
            btVector3 axis = PZbtVector3.btVector3Forward.cross(dir);
            axis.normalize();
            double angle = GlibcMath.acos(dir.dot(PZbtVector3.btVector3Forward));
            q = new btQuaternion(axis, angle);
        }
        m_transform.m_basis.setRotation(q);
        for (int i = 0; i < m_rayTransforms.size(); i++) {
            m_rayTransforms.get(i).m_basis.set(m_transform.m_basis);
        }
    }

    /**
     * @0016a360
     */
    public void update(float x, float y, float z) {
        if (m_range > 0.0) {
            m_transform.m_origin.set(new btVector3(x, y, z));
            double step = m_range / (double) m_rayTransforms.size();
            for (int i = 0; i < m_rayTransforms.size(); i++) {
                m_rayTransforms
                        .get(i)
                        .m_origin
                        .set(
                                m_transform.mul(
                                        new btVector3(0.0, 0.0, (double) i * step + m_offset)));
            }
            for (int i = 0; i < m_aimTransforms.size(); i++) {
                m_aimTransforms
                        .get(i)
                        .m_origin
                        .set(
                                m_aimTransform.mul(
                                        new btVector3(0.0, 0.0, (double) i * step + m_offset)));
            }
            if (m_offset + (double) 0.1f <= 1.0) {
                m_offset += (double) 0.1f;
            } else {
                m_offset = 0.0;
            }
            cameraAimTransform.m_origin.set(m_aimTransform.mul(new btVector3(0.0, 0.0, m_range)));
        }
    }

    /**
     * @00169a70
     */
    public static int getBodyPartTargetPriority(int part) {
        return Integer.compareUnsigned(part, 2) > 0 ? 3 : 2 - part;
    }

    /**
     * @00169a90: out holds records of 5 floats {id, x, y, z, part}.
     */
    public static void updateTargetBodyPart(int id, int part, int n, float[] out) {
        for (int i = 0; i < n; i += 5) {
            if ((float) id == out[i]) {
                out[i + 4] = (float) part;
                return;
            }
        }
    }

    private static int offsetX() {
        return WorldSimulation.instance.m_offsetX;
    }

    private static int offsetY() {
        return WorldSimulation.instance.m_offsetY;
    }

    private static btDynamicsWorld world() {
        return (btDynamicsWorld) btGlobals.gDynamicsWorld;
    }

    /**
     * @0016d920: first-hit-per-object compound ray along the muzzle; records of 4 floats.
     */
    public int getTargets(float range, int max, float[] out) {
        btVector3 from = new btVector3(m_transform.m_origin);
        btVector3 to = m_transform.mul(new btVector3(0.0, 0.0, range));
        AllCompoundHitsRayResult res = new AllCompoundHitsRayResult();
        raycastClosestWithCompoundSupport(world(), from, to, res);
        int count = 0;
        int k = 0;
        for (int h = 0; h < res.hits.size(); h++) {
            AllCompoundHitsRayResult.Hit hit = res.hits.get(h);
            BulletObject bo = (BulletObject) hit.obj.getUserPointer();
            if (bo == null) {
                continue;
            }
            double dx;
            double dz;
            if (bo.type == BulletObjectType.Vehicle) {
                if (hit.childIndex < 0) {
                    dx = offsetX();
                    dz = offsetY();
                } else {
                    BulletObject bo2 = (BulletObject) hit.shape.m_userPointer;
                    if (bo2 == null) {
                        continue;
                    }
                    bo2.flag40 = true;
                    bo2.color.set(m_color);
                    dx = offsetX();
                    bo2.x = hit.hitPoint.x + dx;
                    bo2.y = hit.hitPoint.y;
                    dz = offsetY();
                    bo2.z = hit.hitPoint.z + dz;
                }
            } else if (bo.type == BulletObjectType.Ragdoll) {
                dx = offsetX();
                dz = offsetY();
                bo.flag40 = true;
                bo.color.set(m_color);
                bo.x = hit.hitPoint.x + dx;
                bo.y = hit.hitPoint.y;
                bo.z = hit.hitPoint.z + dz;
            } else {
                continue;
            }
            out[k] = (float) bo.id;
            out[k + 1] = (float) (dx + hit.hitPoint.x);
            out[k + 2] = (float) hit.hitPoint.y;
            out[k + 3] = (float) (dz + hit.hitPoint.z);
            k += 4;
            count++;
            if (count >= max) {
                break;
            }
        }
        return count;
    }

    /** {@code rand()} scaled by the float constant 2^-31 (0x30000000). */
    private static final float RAND_SCALE = Float.intBitsToFloat(0x30000000);

    /**
     * @00169700 {@code getTargets(float range, float spread, int n, int max, float*)}: n random
     * closest-hit rays in a disc of radius spread*sqrt(u); only ragdoll hits are recorded.
     */
    public int getTargets(float range, float spread, int n, int max, float[] out) {
        btVector3 from = new btVector3(m_transform.m_origin);
        int count = 0;
        int k = 0;
        btDynamicsWorld world = world();
        for (int i = 0; i < n; i++) {
            int r1 = GlibcRand.rand();
            int r2 = GlibcRand.rand();
            float f = GlibcMath.sqrtf((float) r2 * RAND_SCALE);
            double[] sc = new double[2];
            GlibcMath.sincos(((double) (float) r1 * 6.283185307179586) / 2147483647.0, sc);
            double s = sc[0];
            double c = sc[1];
            double a = f * spread;
            btVector3 v = new btVector3(c * a, a * s, range);
            btVector3 to = m_transform.mul(v);
            btCollisionWorld.ClosestRayResultCallback cb =
                    new btCollisionWorld.ClosestRayResultCallback(from, to);
            world.rayTest(from, to, cb);
            if (cb.m_collisionObject != null) {
                BulletObject bo = (BulletObject) cb.m_collisionObject.getUserPointer();
                if (bo != null) {
                    bo.flag40 = true;
                    bo.color.set(m_color);
                    if (bo.type == BulletObjectType.Ragdoll) {
                        out[k] = (float) bo.id;
                        out[k + 1] = (float) ((double) offsetX() + cb.m_hitPointWorld.x);
                        out[k + 2] = (float) cb.m_hitPointWorld.y;
                        out[k + 3] = (float) ((double) offsetY() + cb.m_hitPointWorld.z);
                        k += 4;
                        count++;
                        if (count >= max) {
                            return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * @00169ad0: all-hits ray through the aim reticle (+-150 along its -Z). Records of 5 floats
     * {id, x, y, z, part}. Quirks kept: every record uses hit point 0 of the callback; the distance
     * test is {@code !(dist > range)}; with {@code addParts} the same callback is reused (its
     * arrays grow) for the body-part pass.
     */
    public int getCameraTargets(float range, int max, float[] out, boolean addParts) {
        btVector3 muzzle = new btVector3(m_transform.m_origin);
        btVector3 dir = m_aimTransform.m_basis.mul(new btVector3(0.0, 0.0, -1.0));
        btVector3 d = dir.normalized().mul(150.0);
        btVector3 from = m_aimTransform.m_origin.sub(d);
        btVector3 to = m_aimTransform.m_origin.add(d);
        if (to.sub(from).length() == 0.0) {
            return 0;
        }
        btDynamicsWorld world = world();
        btCollisionWorld.AllHitsRayResultCallback cb =
                new btCollisionWorld.AllHitsRayResultCallback(from, to);
        world.rayTest(from, to, cb);
        int count = 0;
        int k = 0;
        for (int i = 0; i < cb.m_collisionObjects.size(); i++) {
            btCollisionObject obj = cb.m_collisionObjects.get(i);
            BulletObject bo = (BulletObject) obj.getUserPointer();
            if (bo == null || bo.type != BulletObjectType.Ragdoll) {
                continue;
            }
            double dist = muzzle.sub(obj.m_worldTransform.m_origin).length();
            if (!(dist > (double) range)) {
                bo.flag40 = true;
                count++;
                bo.color.set(m_cameraTargetColor);
                out[k] = (float) bo.id;
                btVector3 hp0 = cb.m_hitPointWorld.get(0);
                out[k + 1] = (float) ((double) offsetX() + hp0.x);
                out[k + 2] = (float) hp0.y;
                out[k + 4] = 11.0f;
                out[k + 3] = (float) ((double) offsetY() + hp0.z);
                k += 5;
                if (count >= max) {
                    break;
                }
            }
        }
        if (addParts && count > 0) {
            PZBallisticsTargetPool targets = WorldSimulation.instance.m_ballisticsTargets;
            for (int j = 0; j < count; j++) {
                PZBallisticsTarget t = targets.get(btScalar.cvttsd2si((double) out[5 * j]));
                if (t != null) {
                    t.addBodyPartCollision();
                }
            }
            world.rayTest(from, to, cb);
            int best = -1;
            for (int i = 0; i < cb.m_collisionObjects.size(); i++) {
                BulletObject bo = (BulletObject) cb.m_collisionObjects.get(i).getUserPointer();
                if (bo == null || bo.type != BulletObjectType.RagdollPart) {
                    continue;
                }
                int p = getBodyPartTargetPriority(bo.partIndex);
                if (best == -1 || p < getBodyPartTargetPriority(best)) {
                    updateTargetBodyPart(bo.id, bo.partIndex, count * 5, out);
                    best = bo.partIndex;
                }
                bo.flag40 = true;
                bo.color.set(m_cameraTargetColor);
            }
            for (int j = 0; j < count; j++) {
                PZBallisticsTarget t = targets.get(btScalar.cvttsd2si((double) out[5 * j]));
                if (t != null) {
                    t.removeBodyPartCollision();
                }
            }
        }
        return count;
    }

    /**
     * @0016d530: n random rays with radius spread*u^exp; each writes a 4-float record {id or 0, x,
     * y, z} (ray end when nothing is hit). Returns n unconditionally.
     */
    public int getSpreadTargetData(
            float range, float spread, float exponent, int n, int unused, float[] out) {
        btVector3 from = new btVector3(m_transform.m_origin);
        btDynamicsWorld world = world();
        for (int i = 0; i < n; i++) {
            int base = 4 * i;
            int r1 = GlibcRand.rand();
            double[] sc = new double[2];
            GlibcMath.sincos(((double) (float) r1 * 6.283185307179586) / 2147483647.0, sc);
            double s = sc[0];
            double c = sc[1];
            int r2 = GlibcRand.rand();
            float f = GlibcMath.powf((float) r2 * RAND_SCALE, exponent);
            double a = spread * f;
            btVector3 v = new btVector3(c * a, a * s, range);
            btVector3 to = m_transform.mul(v);
            out[base] = 0.0f;
            out[base + 2] = (float) to.y;
            out[base + 1] = (float) ((double) offsetX() + to.x);
            out[base + 3] = (float) ((double) offsetY() + to.z);
            AllCompoundHitsRayResult res = new AllCompoundHitsRayResult();
            raycastClosestWithCompoundSupport(world, from, to, res);
            if (res.hits.isEmpty()) {
                continue;
            }
            AllCompoundHitsRayResult.Hit h = res.hits.get(0);
            BulletObject bo = (BulletObject) h.obj.getUserPointer();
            if (bo == null) {
                continue;
            }
            BulletObject tgt;
            if (bo.type == BulletObjectType.Vehicle) {
                if (h.childIndex < 0) {
                    continue;
                }
                tgt = (BulletObject) h.shape.m_userPointer;
                if (tgt == null) {
                    continue;
                }
            } else if (bo.type == BulletObjectType.Ragdoll) {
                tgt = bo;
            } else {
                continue;
            }
            tgt.flag40 = true;
            tgt.color.set(m_spreadTargetColor);
            tgt.y = h.hitPoint.y;
            tgt.x = (double) offsetX() + h.hitPoint.x;
            tgt.z = h.hitPoint.z + (double) offsetY();
            out[base] = (float) bo.id;
            out[base + 1] = (float) tgt.x;
            out[base + 2] = (float) h.hitPoint.y;
            out[base + 3] = (float) tgt.z;
        }
        return n;
    }

    /** {@code btTransform t; t.setIdentity(); t.setOrigin(v);} */
    private static btTransform originTransform(btVector3 v) {
        btTransform t = new btTransform();
        t.setIdentity();
        t.m_origin.set(v);
        return t;
    }

    /** Hit point {@code (to - from) * f + from} per component, w = 0 (PZ-authored lerp). */
    private static btVector3 lerpPoint(btVector3 from, btVector3 to, double f) {
        return new btVector3(
                (to.x - from.x) * f + from.x,
                (to.y - from.y) * f + from.y,
                (to.z - from.z) * f + from.z);
    }

    /**
     * @0016bc80: every object hit by an all-hits world ray; compound objects are expanded into one
     * Hit per child whose world AABB the ray crosses. Quirks: each child callback is created with
     * {@code m_collisionObject} preset to the parent, so a child whose AABB is crossed but whose
     * shape is missed is still pushed with fraction 1.0 and point {@code to}; its normal is then
     * the uninitialised stack slot, emulated by {@link #staleChildNormal} (value left by the
     * previous child callback, zero at first use). The temporary object does not copy collision
     * flags.
     */
    public static void raycastAllWithCompoundSupport(
            btCollisionWorld world, btVector3 from, btVector3 to, AllCompoundHitsRayResult result) {
        btCollisionWorld.AllHitsRayResultCallback cb =
                new btCollisionWorld.AllHitsRayResultCallback(from, to);
        world.rayTest(from, to, cb);
        btCollisionObject tmp = new btCollisionObject();
        btTransform fromT = originTransform(from);
        btTransform toT = originTransform(to);
        for (int i = 0; i < cb.m_collisionObjects.size(); i++) {
            btCollisionObject obj = cb.m_collisionObjects.get(i);
            btCollisionShape shape = obj.m_collisionShape;
            if (shape.getShapeType() == 31) {
                btCompoundShape compound = (btCompoundShape) shape;
                for (int j = 0; j < compound.getNumChildShapes(); j++) {
                    btCompoundShapeChild child = compound.m_children.get(j);
                    btCollisionShape childShape = child.m_childShape;
                    btTransform childWorld = obj.m_worldTransform.mul(child.m_transform);
                    btVector3 aabbMin = new btVector3();
                    btVector3 aabbMax = new btVector3();
                    childShape.getAabb(childWorld, aabbMin, aabbMax);
                    double[] param = {1.0};
                    btVector3 normal = new btVector3();
                    if (!btAabbUtil2.btRayAabb(from, to, aabbMin, aabbMax, param, normal)) {
                        continue;
                    }
                    tmp.setWorldTransform(childWorld);
                    tmp.setCollisionShape(childShape);
                    btCollisionWorld.ClosestRayResultCallback ccb =
                            new btCollisionWorld.ClosestRayResultCallback(from, to);
                    ccb.m_hitNormalWorld.set(staleChildNormal);
                    ccb.m_collisionObject = obj;
                    btCollisionWorld.rayTestSingle(fromT, toT, tmp, childShape, childWorld, ccb);
                    staleChildNormal.set(ccb.m_hitNormalWorld);
                    if (ccb.m_collisionObject != null) {
                        result.hits.add(
                                new AllCompoundHitsRayResult.Hit(
                                        obj,
                                        childShape,
                                        j,
                                        lerpPoint(from, to, ccb.m_closestHitFraction),
                                        ccb.m_hitNormalWorld,
                                        ccb.m_closestHitFraction));
                    }
                }
            } else {
                result.hits.add(
                        new AllCompoundHitsRayResult.Hit(
                                obj,
                                shape,
                                -1,
                                cb.m_hitPointWorld.get(i),
                                cb.m_hitNormalWorld.get(i),
                                cb.m_hitFractions.get(i)));
            }
        }
    }

    /**
     * @0016c9b0: closest world hit; a compound hit is refined to the nearest child (strictly
     * smaller fraction than the running best, which starts at 1.0). One Hit is pushed only when the
     * best fraction is below 1.0. The temporary object copies the parent's collision flags.
     */
    public static void raycastClosestWithCompoundSupport(
            btCollisionWorld world, btVector3 from, btVector3 to, AllCompoundHitsRayResult result) {
        btCollisionWorld.ClosestRayResultCallback cb =
                new btCollisionWorld.ClosestRayResultCallback(from, to);
        world.rayTest(from, to, cb);
        btCollisionObject obj = cb.m_collisionObject;
        if (obj == null) {
            return;
        }
        btCollisionShape shape = obj.m_collisionShape;
        AllCompoundHitsRayResult.Hit best = new AllCompoundHitsRayResult.Hit();
        best.childIndex = -1;
        best.fraction = 1.0;
        if (shape.getShapeType() == 31) {
            btTransform fromT = originTransform(from);
            btTransform toT = originTransform(to);
            btCollisionObject tmp = new btCollisionObject();
            tmp.m_collisionFlags = obj.m_collisionFlags;
            btCompoundShape compound = (btCompoundShape) shape;
            for (int j = 0; j < compound.getNumChildShapes(); j++) {
                btCompoundShapeChild child = compound.m_children.get(j);
                btCollisionShape childShape = child.m_childShape;
                btTransform childWorld = obj.m_worldTransform.mul(child.m_transform);
                btVector3 aabbMin = new btVector3();
                btVector3 aabbMax = new btVector3();
                childShape.getAabb(childWorld, aabbMin, aabbMax);
                double[] param = {1.0};
                btVector3 normal = new btVector3();
                if (!btAabbUtil2.btRayAabb(from, to, aabbMin, aabbMax, param, normal)) {
                    continue;
                }
                tmp.setWorldTransform(childWorld);
                tmp.setCollisionShape(childShape);
                btCollisionWorld.ClosestRayResultCallback ccb =
                        new btCollisionWorld.ClosestRayResultCallback(from, to);
                ccb.m_collisionObject = obj;
                btCollisionWorld.rayTestSingle(fromT, toT, tmp, childShape, childWorld, ccb);
                if (ccb.m_collisionObject == null || best.fraction <= ccb.m_closestHitFraction) {
                    continue;
                }
                best.obj = obj;
                best.shape = childShape;
                best.childIndex = j;
                best.hitPoint.set(lerpPoint(from, to, ccb.m_closestHitFraction));
                best.hitNormal.set(ccb.m_hitNormalWorld);
                best.fraction = ccb.m_closestHitFraction;
            }
        } else {
            best.obj = obj;
            best.shape = shape;
            best.hitPoint.set(cb.m_hitPointWorld);
            best.hitNormal.set(cb.m_hitNormalWorld);
            best.fraction = cb.m_closestHitFraction;
        }
        if (best.fraction < 1.0) {
            result.hits.add(new AllCompoundHitsRayResult.Hit(best));
        }
    }

    /**
     * @0016b210
     */
    public void debugDraw(btCollisionWorld world) {
        world.debugDrawObject(m_transform, m_shape, m_debugColor);
        for (int i = 0; i < m_rayTransforms.size(); i++) {
            world.debugDrawObject(m_rayTransforms.get(i), m_sphereShape, m_debugColor);
        }
        world.debugDrawObject(m_aimTransform, aimShape, PZbtVector3.Purple);
        for (int i = 0; i < m_aimTransforms.size(); i++) {
            world.debugDrawObject(m_aimTransforms.get(i), aimShape, PZbtVector3.Yellow);
        }
        world.debugDrawObject(cameraAimTransform, aimShape, PZbtVector3.Pink);
    }

    /**
     * @0016b330 {@code debugDraw(gDynamicsWorld)}
     */
    public void render() {
        debugDraw(world());
    }
}
