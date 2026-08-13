package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.SynchronizationState;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Flags every {@code AssetManager} method that touches the private {@code assets} table ({@code
 * load}, {@code get}, {@code destroy}, {@code removeUnreferenced}, {@code enableUnload}) as {@code
 * synchronized} (instance monitor, so each manager — {@code AnimNodeAssetManager}, {@code
 * PhysicsShapeAssetManager}, … — locks independently).
 *
 * <p>The table is a plain {@code THashMap} and {@code load} is get-then-put; concurrent puts from
 * the SPVThread (animal-in-vehicle load) and the main thread (boot {@code refreshAnimSets}) are
 * what blew up in {@code THashMap.rehash} and cost a vehicle its vehicles.db row. With {@link
 * AnimationSetLockPatch} and {@link RefreshAnimSetsLockPatch} every {@code AnimNodeAssetManager}
 * access already nests inside the shared animset lock, so these monitors are defense in depth: they
 * keep the get-then-put atomic for every other {@code AssetManager} subclass reached off the main
 * thread. No subclass overrides any flagged method, so the flags cover all managers.
 *
 * <p>Server-only. On a dedicated server these methods run at asset-load frequency (boot and first
 * use), never per-tick; direct reads through {@code getAssetTable()} (e.g. {@code Bullet}) bypass
 * the monitors and are out of scope.
 */
public class AssetManagerSyncPatch extends StormClassTransformer {

    public AssetManagerSyncPatch() {
        super("zombie.asset.AssetManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                new ModifierAdjustment()
                        .withMethodModifiers(
                                ElementMatchers.namedOneOf(
                                        "load",
                                        "get",
                                        "destroy",
                                        "removeUnreferenced",
                                        "enableUnload"),
                                SynchronizationState.SYNCHRONIZED));
    }
}
