// Port of btVehicleRaycaster.h (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.vehicle;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/** btVehicleRaycaster is provides interface for between vehicle simulation and raycasting */
public abstract class btVehicleRaycaster {

    /** {@code virtual ~btVehicleRaycaster()} */
    public void destroy() {}

    public static class btVehicleRaycasterResult {
        public final btVector3 m_hitPointInWorld = new btVector3();
        public final btVector3 m_hitNormalInWorld = new btVector3();
        public double m_distFraction;

        public btVehicleRaycasterResult() {
            m_distFraction = -1.;
        }
    }

    /** Returns the hit object ({@code void*}), or null for no hit. */
    public abstract Object castRay(btVector3 from, btVector3 to, btVehicleRaycasterResult result);
}
