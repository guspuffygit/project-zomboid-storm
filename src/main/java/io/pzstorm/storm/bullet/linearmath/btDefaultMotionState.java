// Port of LinearMath/btDefaultMotionState.h (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/** Default motion state with a centre-of-mass offset. */
public class btDefaultMotionState extends btMotionState {
    public final btTransform m_graphicsWorldTrans = new btTransform();
    public final btTransform m_centerOfMassOffset = new btTransform();
    public final btTransform m_startWorldTrans = new btTransform();
    public Object m_userPointer;

    public btDefaultMotionState() {
        this(btTransform.getIdentity(), btTransform.getIdentity());
    }

    public btDefaultMotionState(btTransform startTrans) {
        this(startTrans, btTransform.getIdentity());
    }

    public btDefaultMotionState(btTransform startTrans, btTransform centerOfMassOffset) {
        m_graphicsWorldTrans.set(startTrans);
        m_centerOfMassOffset.set(centerOfMassOffset);
        m_startWorldTrans.set(startTrans);
        m_userPointer = null;
    }

    /** {@code centerOfMassWorldTrans = m_centerOfMassOffset.inverse() * m_graphicsWorldTrans;} */
    @Override
    public void getWorldTransform(btTransform centerOfMassWorldTrans) {
        centerOfMassWorldTrans.set(m_centerOfMassOffset.inverse().mul(m_graphicsWorldTrans));
    }

    /** {@code m_graphicsWorldTrans = centerOfMassWorldTrans * m_centerOfMassOffset;} */
    @Override
    public void setWorldTransform(btTransform centerOfMassWorldTrans) {
        m_graphicsWorldTrans.set(centerOfMassWorldTrans.mul(m_centerOfMassOffset));
    }
}
