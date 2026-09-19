package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.narrowphase.btManifoldPoint;

/**
 * Port of Bullet 2.82 {@code typedef bool (*ContactAddedCallback)(btManifoldPoint& cp, const
 * btCollisionObjectWrapper* colObj0Wrap, int partId0, int index0, const btCollisionObjectWrapper*
 * colObj1Wrap, int partId1, int index1)} (btManifoldResult.h). The global {@code
 * gContactAddedCallback} lives in {@link io.pzstorm.storm.bullet.linearmath.btGlobals} (typed
 * Object; store an instance of this interface there).
 */
@FunctionalInterface
public interface ContactAddedCallback {
    boolean invoke(
            btManifoldPoint cp,
            btCollisionObjectWrapper colObj0Wrap,
            int partId0,
            int index0,
            btCollisionObjectWrapper colObj1Wrap,
            int partId1,
            int index1);
}
