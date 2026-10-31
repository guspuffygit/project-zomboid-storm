package io.pzstorm.storm.advice.entityidcollision;

import io.pzstorm.storm.patch.fixes.EntityIdCollisionGuard;
import net.bytebuddy.asm.Advice;

/**
 * Wraps {@code GameEntityManager.UnregisterEntity(GameEntity, boolean)}. See {@link
 * EntityIdCollisionGuard}. Parameters are typed {@code Object} so the inlined advice embeds no
 * checkcasts against game classes.
 */
public class UnregisterEntityCollisionAdvice {

    @Advice.OnMethodEnter
    public static Object onEnter(
            @Advice.Argument(0) Object entity,
            @Advice.Argument(value = 1, readOnly = false) boolean offloadToMeta,
            @Advice.FieldValue("idToEntityMap") Object idToEntityMap) {
        Object displaced = EntityIdCollisionGuard.before(entity, idToEntityMap);
        if (EntityIdCollisionGuard.isOccupied(displaced)) {
            offloadToMeta = false;
        }
        return displaced;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Argument(0) Object entity,
            @Advice.FieldValue("idToEntityMap") Object idToEntityMap,
            @Advice.Enter Object displaced) {
        if (displaced != null) {
            EntityIdCollisionGuard.after(entity, idToEntityMap, displaced);
        }
    }
}
