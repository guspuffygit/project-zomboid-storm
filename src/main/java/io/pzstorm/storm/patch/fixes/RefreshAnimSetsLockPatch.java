package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.advice.animsetlock.AnimSetLockAdvice;
import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Holds {@link AnimSetLockAdvice#LOCK} for the whole body of {@code
 * LuaManager$GlobalObject.refreshAnimSets}.
 *
 * <p>The {@code reload=true} branch (boot at {@code GameServer.main}, or a Lua-triggered anim
 * reload) iterates {@code AnimNodeAssetManager.instance.getAssetTable().values()} and reloads each
 * asset — an unsynchronized traversal of the same {@code THashMap} the SPVThread inserts into
 * through {@code GetAnimationSet} when it loads an animal-carrying vehicle. Method-level monitors
 * on {@code AssetManager} cannot cover a caller-side iteration, so the whole refresh runs under the
 * shared lock instead; every table insert nests inside {@code GetAnimationSet}, which takes the
 * same lock via {@link AnimationSetLockPatch}. {@code ReentrantLock} re-entrancy makes the internal
 * {@code GetAnimationSet}/{@code Reset} calls safe.
 *
 * <p>The refresh can hold the lock for seconds (it parses every animset XML); the SPVThread
 * blocking on it meanwhile is the point — previously that overlap corrupted the map and got
 * vehicle&nbsp;27 permanently deleted from vehicles.db on 2026-08-13.
 */
public class RefreshAnimSetsLockPatch extends StormClassTransformer {

    public RefreshAnimSetsLockPatch() {
        super("zombie.Lua.LuaManager$GlobalObject");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(AnimSetLockAdvice.class).on(ElementMatchers.named("refreshAnimSets")));
    }
}
