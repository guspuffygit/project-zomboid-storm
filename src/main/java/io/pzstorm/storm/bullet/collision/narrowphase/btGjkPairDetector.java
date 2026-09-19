// Port of BulletCollision/NarrowPhaseCollision/btGjkPairDetector.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** btGjkPairDetector uses GJK to implement the btDiscreteCollisionDetectorInterface */
public class btGjkPairDetector extends btDiscreteCollisionDetectorInterface {
    /** must be above the machine epsilon */
    private static final double REL_ERROR2 = 1.0e-6;

    public final btVector3 m_cachedSeparatingAxis = new btVector3();
    public btConvexPenetrationDepthSolver m_penetrationDepthSolver;
    public btSimplexSolverInterface m_simplexSolver;
    public btConvexShape m_minkowskiA;
    public btConvexShape m_minkowskiB;
    public int m_shapeTypeA;
    public int m_shapeTypeB;
    public double m_marginA;
    public double m_marginB;

    public boolean m_ignoreMargin;
    public double m_cachedSeparatingDistance;

    // some debugging to fix degeneracy problems
    public int m_lastUsedMethod;
    public int m_curIter;
    public int m_degenerateSimplex;
    public int m_catchDegeneracies;
    public int m_fixContactNormalDirection;

    public btGjkPairDetector(
            btConvexShape objectA,
            btConvexShape objectB,
            btSimplexSolverInterface simplexSolver,
            btConvexPenetrationDepthSolver penetrationDepthSolver) {
        m_cachedSeparatingAxis.setValue(0.0, 1.0, 0.0);
        m_penetrationDepthSolver = penetrationDepthSolver;
        m_simplexSolver = simplexSolver;
        m_minkowskiA = objectA;
        m_minkowskiB = objectB;
        m_shapeTypeA = objectA.getShapeType();
        m_shapeTypeB = objectB.getShapeType();
        m_marginA = objectA.getMargin();
        m_marginB = objectB.getMargin();
        m_ignoreMargin = false;
        m_lastUsedMethod = -1;
        m_catchDegeneracies = 1;
        m_fixContactNormalDirection = 1;
    }

    public btGjkPairDetector(
            btConvexShape objectA,
            btConvexShape objectB,
            int shapeTypeA,
            int shapeTypeB,
            double marginA,
            double marginB,
            btSimplexSolverInterface simplexSolver,
            btConvexPenetrationDepthSolver penetrationDepthSolver) {
        m_cachedSeparatingAxis.setValue(0.0, 1.0, 0.0);
        m_penetrationDepthSolver = penetrationDepthSolver;
        m_simplexSolver = simplexSolver;
        m_minkowskiA = objectA;
        m_minkowskiB = objectB;
        m_shapeTypeA = shapeTypeA;
        m_shapeTypeB = shapeTypeB;
        m_marginA = marginA;
        m_marginB = marginB;
        m_ignoreMargin = false;
        m_lastUsedMethod = -1;
        m_catchDegeneracies = 1;
        m_fixContactNormalDirection = 1;
    }

    @Override
    public void getClosestPoints(
            ClosestPointInput input, Result output, btIDebugDraw debugDraw, boolean swapResults) {
        getClosestPointsNonVirtual(input, output, debugDraw);
    }

    public void getClosestPointsNonVirtual(
            ClosestPointInput input, Result output, btIDebugDraw debugDraw) {
        m_cachedSeparatingDistance = (double) 0.f;

        double distance = 0.0;
        btVector3 normalInB = new btVector3(0.0, 0.0, 0.0);
        btVector3 pointOnA = new btVector3(), pointOnB = new btVector3();
        btTransform localTransA = new btTransform(input.m_transformA);
        btTransform localTransB = new btTransform(input.m_transformB);
        btVector3 positionOffset = (localTransA.getOrigin().add(localTransB.getOrigin())).mul(0.5);
        localTransA.getOrigin().subLocal(positionOffset);
        localTransB.getOrigin().subLocal(positionOffset);

        boolean check2d = m_minkowskiA.isConvex2d() && m_minkowskiB.isConvex2d();

        double marginA = m_marginA;
        double marginB = m_marginB;

        btGlobals.gNumGjkChecks++;

        // for CCD we don't use margins
        if (m_ignoreMargin) {
            marginA = 0.0;
            marginB = 0.0;
        }

        m_curIter = 0;
        int gGjkMaxIter = 1000; // this is to catch invalid input, perhaps check for #NaN?
        m_cachedSeparatingAxis.setValue(0, 1, 0);

        boolean isValid = false;
        boolean checkSimplex = false;
        boolean checkPenetration = true;
        m_degenerateSimplex = 0;

        m_lastUsedMethod = -1;

        {
            double squaredDistance = btScalar.BT_LARGE_FLOAT;
            double delta = 0.0;

            double margin = marginA + marginB;

            m_simplexSolver.reset();

            for (; ; ) {
                btVector3 seperatingAxisInA =
                        btMatrix3x3.mul(
                                m_cachedSeparatingAxis.negate(), input.m_transformA.getBasis());
                btVector3 seperatingAxisInB =
                        btMatrix3x3.mul(m_cachedSeparatingAxis, input.m_transformB.getBasis());

                btVector3 pInA =
                        m_minkowskiA.localGetSupportVertexWithoutMarginNonVirtual(
                                seperatingAxisInA);
                btVector3 qInB =
                        m_minkowskiB.localGetSupportVertexWithoutMarginNonVirtual(
                                seperatingAxisInB);

                btVector3 pWorld = localTransA.transform(pInA);
                btVector3 qWorld = localTransB.transform(qInB);

                if (check2d) {
                    pWorld.set(2, (double) 0.f);
                    qWorld.set(2, (double) 0.f);
                }

                btVector3 w = pWorld.sub(qWorld);
                delta = m_cachedSeparatingAxis.dot(w);

                // potential exit, they don't overlap
                if ((delta > 0.0)
                        && (delta * delta > squaredDistance * input.m_maximumDistanceSquared)) {
                    m_degenerateSimplex = 10;
                    checkSimplex = true;
                    // checkPenetration = false;
                    break;
                }

                // exit 0: the new point is already in the simplex, or we didn't come any closer
                if (m_simplexSolver.inSimplex(w)) {
                    m_degenerateSimplex = 1;
                    checkSimplex = true;
                    break;
                }
                // are we getting any closer ?
                double f0 = squaredDistance - delta;
                double f1 = squaredDistance * REL_ERROR2;

                if (f0 <= f1) {
                    if (f0 <= 0.0) {
                        m_degenerateSimplex = 2;
                    } else {
                        m_degenerateSimplex = 11;
                    }
                    checkSimplex = true;
                    break;
                }

                // add current vertex to simplex
                m_simplexSolver.addVertex(w, pWorld, qWorld);
                btVector3 newCachedSeparatingAxis = new btVector3();

                // calculate the closest point to the origin (update vector v)
                if (!m_simplexSolver.closest(newCachedSeparatingAxis)) {
                    m_degenerateSimplex = 3;
                    checkSimplex = true;
                    break;
                }

                if (newCachedSeparatingAxis.length2() < REL_ERROR2) {
                    m_cachedSeparatingAxis.set(newCachedSeparatingAxis);
                    m_degenerateSimplex = 6;
                    checkSimplex = true;
                    break;
                }

                double previousSquaredDistance = squaredDistance;
                squaredDistance = newCachedSeparatingAxis.length2();

                // are we getting any closer ?
                if (previousSquaredDistance - squaredDistance
                        <= btScalar.SIMD_EPSILON * previousSquaredDistance) {
                    checkSimplex = true;
                    m_degenerateSimplex = 12;

                    break;
                }

                m_cachedSeparatingAxis.set(newCachedSeparatingAxis);

                // degeneracy, this is typically due to invalid/uninitialized worldtransforms for a
                // btCollisionObject
                if (m_curIter++ > gGjkMaxIter) {
                    break;
                }

                boolean check = (!m_simplexSolver.fullSimplex());

                if (!check) {
                    // do we need this backup_closest here ?
                    m_degenerateSimplex = 13;
                    break;
                }
            }

            if (checkSimplex) {
                m_simplexSolver.compute_points(pointOnA, pointOnB);
                normalInB.set(m_cachedSeparatingAxis);
                double lenSqr = m_cachedSeparatingAxis.length2();

                // valid normal
                if (lenSqr < 0.0001) {
                    m_degenerateSimplex = 5;
                }
                if (lenSqr > btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON) {
                    double rlen = 1.0 / btScalar.btSqrt(lenSqr);
                    normalInB.mulLocal(rlen); // normalize
                    double s = btScalar.btSqrt(squaredDistance);

                    pointOnA.subLocal(m_cachedSeparatingAxis.mul(marginA / s));
                    pointOnB.addLocal(m_cachedSeparatingAxis.mul(marginB / s));
                    distance = ((1.0 / rlen) - margin);
                    isValid = true;

                    m_lastUsedMethod = 1;
                } else {
                    m_lastUsedMethod = 2;
                }
            }

            boolean catchDegeneratePenetrationCase =
                    (m_catchDegeneracies != 0
                            && m_penetrationDepthSolver != null
                            && m_degenerateSimplex != 0
                            && ((distance + margin) < 0.01));

            if (checkPenetration && (!isValid || catchDegeneratePenetrationCase)) {
                // penetration case

                // if there is no way to handle penetrations, bail out
                if (m_penetrationDepthSolver != null) {
                    // Penetration depth case.
                    btVector3 tmpPointOnA = new btVector3(), tmpPointOnB = new btVector3();

                    btGlobals.gNumDeepPenetrationChecks++;
                    m_cachedSeparatingAxis.setZero();

                    boolean isValid2 =
                            m_penetrationDepthSolver.calcPenDepth(
                                    m_simplexSolver,
                                    m_minkowskiA,
                                    m_minkowskiB,
                                    localTransA,
                                    localTransB,
                                    m_cachedSeparatingAxis,
                                    tmpPointOnA,
                                    tmpPointOnB,
                                    debugDraw);

                    if (isValid2) {
                        btVector3 tmpNormalInB = tmpPointOnB.sub(tmpPointOnA);
                        double lenSqr = tmpNormalInB.length2();
                        if (lenSqr <= (btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON)) {
                            tmpNormalInB.set(m_cachedSeparatingAxis);
                            lenSqr = m_cachedSeparatingAxis.length2();
                        }

                        if (lenSqr > (btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON)) {
                            tmpNormalInB.divLocal(btScalar.btSqrt(lenSqr));
                            double distance2 = -(tmpPointOnA.sub(tmpPointOnB)).length();
                            // only replace valid penetrations when the result is deeper (check)
                            if (!isValid || (distance2 < distance)) {
                                distance = distance2;
                                pointOnA.set(tmpPointOnA);
                                pointOnB.set(tmpPointOnB);
                                normalInB.set(tmpNormalInB);
                                isValid = true;
                                m_lastUsedMethod = 3;
                            } else {
                                m_lastUsedMethod = 8;
                            }
                        } else {
                            m_lastUsedMethod = 9;
                        }
                    } else {
                        // this is another degenerate case, where the initial GJK calculation
                        // reports a degenerate case
                        // EPA reports no penetration, and the second GJK (using the supporting
                        // vector without margin)
                        // reports a valid positive distance. Use the results of the second GJK
                        // instead of failing.
                        if (m_cachedSeparatingAxis.length2() > 0.0) {
                            double distance2 = (tmpPointOnA.sub(tmpPointOnB)).length() - margin;
                            // only replace valid distances when the distance is less
                            if (!isValid || (distance2 < distance)) {
                                distance = distance2;
                                pointOnA.set(tmpPointOnA);
                                pointOnB.set(tmpPointOnB);
                                pointOnA.subLocal(m_cachedSeparatingAxis.mul(marginA));
                                pointOnB.addLocal(m_cachedSeparatingAxis.mul(marginB));
                                normalInB.set(m_cachedSeparatingAxis);
                                normalInB.normalize();
                                isValid = true;
                                m_lastUsedMethod = 6;
                            } else {
                                m_lastUsedMethod = 5;
                            }
                        }
                    }
                }
            }
        }

        if (isValid && ((distance < 0) || (distance * distance < input.m_maximumDistanceSquared))) {
            if (m_fixContactNormalDirection != 0) {
                // @workaround for sticky convex collisions
                btVector3 aabbMin = new btVector3(), aabbMax = new btVector3();
                m_minkowskiA.getAabb(localTransA, aabbMin, aabbMax);
                btVector3 posA = (aabbMax.add(aabbMin)).mul(0.5);

                m_minkowskiB.getAabb(localTransB, aabbMin, aabbMax);
                btVector3 posB = (aabbMin.add(aabbMax)).mul(0.5);

                btVector3 diff = posA.sub(posB);
                if (diff.dot(normalInB) < (double) 0.f) normalInB.mulLocal((double) -1.f);
            }
            m_cachedSeparatingAxis.set(normalInB);
            m_cachedSeparatingDistance = distance;

            output.addContactPoint(normalInB, pointOnB.add(positionOffset), distance);
        }
    }

    public void setMinkowskiA(btConvexShape minkA) {
        m_minkowskiA = minkA;
    }

    public void setMinkowskiB(btConvexShape minkB) {
        m_minkowskiB = minkB;
    }

    public void setCachedSeperatingAxis(btVector3 seperatingAxis) {
        m_cachedSeparatingAxis.set(seperatingAxis);
    }

    public btVector3 getCachedSeparatingAxis() {
        return m_cachedSeparatingAxis;
    }

    public double getCachedSeparatingDistance() {
        return m_cachedSeparatingDistance;
    }

    public void setPenetrationDepthSolver(btConvexPenetrationDepthSolver penetrationDepthSolver) {
        m_penetrationDepthSolver = penetrationDepthSolver;
    }

    /** don't use setIgnoreMargin, it's for Bullet's internal use */
    public void setIgnoreMargin(boolean ignoreMargin) {
        m_ignoreMargin = ignoreMargin;
    }
}
