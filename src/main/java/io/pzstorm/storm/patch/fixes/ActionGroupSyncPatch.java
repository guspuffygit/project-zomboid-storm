package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.SynchronizationState;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Flags {@code ActionGroup.getActionGroup} and {@code ActionGroup.reloadAll} as {@code
 * synchronized} (static → {@code ActionGroup.class} monitor).
 *
 * <p>{@code getActionGroup} is get-then-put on the static {@code s_actionGroupMap} {@code HashMap}.
 * The SPVThread reaches it through {@code IsoAnimal.init → initType} while loading animal-carrying
 * vehicles; the main thread calls it per zombie per tick from {@code IsoZombie.updateInternal} and
 * at every character creation — the same unsynchronized-map race that corrupted the animation asset
 * table (see {@link AnimationSetLockPatch}).
 *
 * <p>This is the one hot method in the racing set, so the cost was measured on the server's JVM
 * (Zulu&nbsp;25): an uncontended synchronized static lookup costs ~3.2&nbsp;ns over the plain call,
 * ~25&nbsp;µs/tick at 5&nbsp;000 loaded zombies — noise against a 70&nbsp;ms tick. Contention is
 * effectively boot-only: post-boot the map is fully populated and SPVThread lookups are cache hits
 * holding the monitor for nanoseconds.
 */
public class ActionGroupSyncPatch extends StormClassTransformer {

    public ActionGroupSyncPatch() {
        super("zombie.characters.action.ActionGroup");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                new ModifierAdjustment()
                        .withMethodModifiers(
                                ElementMatchers.namedOneOf("getActionGroup", "reloadAll"),
                                SynchronizationState.SYNCHRONIZED));
    }
}
