package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82 {@code struct btPerturbedContactResult} (file-local in
 * btConvexConvexAlgorithm.cpp; DEBUG_CONTACTS not defined).
 */
public class btPerturbedContactResult extends btManifoldResult {
    public btManifoldResult m_originalManifoldResult;
    public final btTransform m_transformA;
    public final btTransform m_transformB;
    public final btTransform m_unPerturbedTransform;
    public boolean m_perturbA;
    public btIDebugDraw m_debugDrawer;

    public btPerturbedContactResult(
            btManifoldResult originalResult,
            btTransform transformA,
            btTransform transformB,
            btTransform unPerturbedTransform,
            boolean perturbA,
            btIDebugDraw debugDrawer) {
        m_originalManifoldResult = originalResult;
        m_transformA = new btTransform(transformA);
        m_transformB = new btTransform(transformB);
        m_unPerturbedTransform = new btTransform(unPerturbedTransform);
        m_perturbA = perturbA;
        m_debugDrawer = debugDrawer;
    }

    @Override
    public void destroy() {
        super.destroy();
    }

    @Override
    public void addContactPoint(
            btVector3 normalOnBInWorld, btVector3 pointInWorld, double orgDepth) {
        btVector3 endPt, startPt;
        double newDepth;

        if (m_perturbA) {
            btVector3 endPtOrg = pointInWorld.add(normalOnBInWorld.mul(orgDepth));
            endPt = m_unPerturbedTransform.mul(m_transformA.inverse()).transform(endPtOrg);
            newDepth = (endPt.sub(pointInWorld)).dot(normalOnBInWorld);
            startPt = endPt.add(normalOnBInWorld.mul(newDepth));
        } else {
            endPt = pointInWorld.add(normalOnBInWorld.mul(orgDepth));
            startPt = m_unPerturbedTransform.mul(m_transformB.inverse()).transform(pointInWorld);
            newDepth = (endPt.sub(startPt)).dot(normalOnBInWorld);
        }

        m_originalManifoldResult.addContactPoint(normalOnBInWorld, startPt, newDepth);
    }
}
