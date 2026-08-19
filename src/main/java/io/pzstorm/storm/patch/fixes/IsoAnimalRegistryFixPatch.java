package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.IsoObjectIdPoolMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Attaches an exit advice to {@code zombie.characters.animals.IsoAnimal.update()} that
 * re-establishes the invariant {@code AnimalInstanceManager.get(getOnlineID()) == this} after every
 * tick. The actual fix logic lives in {@link IsoAnimalMapInvariant}.
 *
 * <h2>The bug this patches</h2>
 *
 * <p>Animals are registered into {@code AnimalInstanceManager.AnimalMap} only by {@code
 * IsoAnimal.init(AnimalBreed)} — creation and load-from-disk. The real ⇄ virtual round trip is
 * asymmetric around that:
 *
 * <pre>{@code
 * // AnimalPopulationManager.virtualizeAnimal — animal wanders out of the loaded area
 * realAnimal.unloaded();
 * this.n_addAnimal(realAnimal);   // same instance is parked inside a VirtualAnimal
 * realAnimal.delete();            // ❌ AnimalInstanceManager.remove(this) + broadcast delete
 *
 * // AnimalManagerMain.fromWorker — chunk comes back, the same instance is re-realized
 * isoAnimal.addToWorld();
 * IsoWorld.instance.currentCell.getObjectList().add(isoAnimal);
 * // ❌ never calls AnimalInstanceManager.add(...)
 * }</pre>
 *
 * <p>The peer path does not have the problem: {@code AnimalPopulationManager.removeChunkFromWorld}
 * virtualizes with {@code unloaded()} + {@code n_addAnimal(...)} and no {@code delete()}, and
 * {@code IsoChunk} line 3226 only unregisters {@code if (GameClient.client)} — so chunk-unload
 * virtualization leaves the registry entry intact and the animal survives the round trip. Only the
 * {@code virtualizeAnimal} path loses it.
 *
 * <h2>Why it matters operationally</h2>
 *
 * <p>{@code AnimalSynchronizationManager.sendUpdateToClient} resolves each pending id via {@code
 * AnimalInstanceManager.get(onlineID)} and silently skips a {@code null}. An unregistered animal is
 * therefore never sent to any client again while it lives: it ticks, eats, breeds, blocks movement
 * and shows up in {@code /list animals}, but is invisible to every player. Restarting the server
 * masks it — {@code AnimalCell.load} → {@code VirtualAnimal.load} → {@code IsoAnimal.load} → {@code
 * init()} re-registers the whole save — which is exactly the reported "animals come back after a
 * restart, then disappear again".
 *
 * <p>Measured on a live 42.20.3 server ~8 h after restart: 180 of 365 animals in the cell object
 * list were unreachable through {@code AnimalMap}, all 180 with a non-zero {@code
 * removedFromWorldMs}.
 *
 * <h2>Healing</h2>
 *
 * <p>The check is idempotent — an animal already mapped under its own id is a single {@code
 * ConcurrentHashMap.get}, so re-running it every tick is cheap. Recovery is immediate rather than
 * deferred to the next realization, which matters for worlds that are already in the broken state.
 * See {@link IsoAnimalMapInvariant.Action} for the three outcomes; both healing paths are exported
 * as Prometheus counters (see {@link IsoObjectIdPoolMetrics}).
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers}. {@code IsoAnimal} also exists on the
 * client, where {@code AnimalMap} is maintained by {@code AnimalUpdatePacket} instead; {@link
 * IsoAnimalMapInvariant#ensureMapEntry(Object)} additionally short-circuits on {@code
 * !GameServer.server} so a mis-registration cannot corrupt client state.
 */
public class IsoAnimalRegistryFixPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isoanimalregistry.";

    static {
        IsoObjectIdPoolMetrics.ensureStarted();
    }

    public IsoAnimalRegistryFixPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "IsoAnimalRegistryAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.returns(void.class))));
    }
}
