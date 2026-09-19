package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;

/**
 * Port of Bullet 2.82 {@code typedef bool (*btShapePairCallback)(const btCollisionShape* pShape0,
 * const btCollisionShape* pShape1)} (btCompoundCollisionAlgorithm.h, also used by
 * btCompoundCompoundCollisionAlgorithm). Globals {@code gCompoundChildShapePairCallback} and {@code
 * gCompoundCompoundChildShapePairCallback} live in {@link
 * io.pzstorm.storm.bullet.linearmath.btGlobals} (typed Object; store an instance of this
 * interface).
 */
@FunctionalInterface
public interface btShapePairCallback {
    boolean invoke(btCollisionShape pShape0, btCollisionShape pShape1);
}
