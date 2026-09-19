package io.pzstorm.storm.patch.bullet;

import io.pzstorm.storm.bullet.StormPhysicsDebugRenderer;
import io.pzstorm.storm.patch.popman.NativeFacadePatch;

/**
 * Binds {@code zombie.core.physics.PhysicsDebugRenderer}'s five instance natives (client-only debug
 * drawing of the physics world) to {@link StormPhysicsDebugRenderer}. The facade takes the renderer
 * as {@code @This Object} and draws back through its {@code drawLine}/{@code drawSphere}/...
 * methods exactly as the native library does.
 */
public class PhysicsDebugRendererNativePatch extends NativeFacadePatch {

    static final String[] NATIVES = {
        "n_debugDrawWorld",
        "renderRagdoll",
        "renderBallistics",
        "renderBallisticsTarget",
        "renderVehicle",
    };

    public PhysicsDebugRendererNativePatch() {
        super("zombie.core.physics.PhysicsDebugRenderer", StormPhysicsDebugRenderer.class, NATIVES);
    }
}
