package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Attaches advice to the three {@code zombie.characters.animals.IsoAnimal} methods that let
 * livestock leave a player's animal zone: the two obstacle-breaking predicates and the path-target
 * entry point. The actual fix logic lives in {@link AnimalZoneContainment}.
 *
 * <h2>The bug this patches</h2>
 *
 * <p>A player-placed animal zone ({@code DesignationZoneAnimal}) is advisory in vanilla — it biases
 * the random wander target in {@code BaseAnimalBehavior.wanderIdle()} and nothing else, and even
 * that bias drops out at hunger/thirst 0.9, falls through after 100 failed re-rolls, and accepts
 * any zone rather than the animal's own. Meanwhile a hungry or thirsty animal ({@code
 * shouldBreakObstaclesDuringPathfinding()}, &gt; 0.8) gets {@code PathFindRequest.canThump}, and
 * {@code VGAStar.canAnimalBreakObstacle} then treats any edge carrying both a collide bit and a
 * can-path bit as passable. {@code SquareUpdateTask} sets exactly that bit pair for player-built
 * {@code IsoThumpable} walls, so the pathfinder routes the animal <i>through</i> the pen wall, and
 * {@code animalShouldThump()} + {@code AnimalAttackState} then break the wall down and repath
 * through the hole. Vanilla map walls carry no can-path bits, which is why only player-built pens
 * leak.
 *
 * <h2>Why these three methods</h2>
 *
 * <p>They are the narrowest boundaries that cover the whole chain. The two predicates are whole
 * methods returning a single boolean, so exit advice reverses the permission without touching the
 * pathfinder, the collision code, or the attack state — a contained animal simply never asks to
 * break anything. {@code pathToLocation(int, int, int)} is the single entry point every wander,
 * flee, follow and forage target passes through, so clamping its arguments closes all three holes
 * in {@code wanderIdle()}'s bias (and every other out-of-zone target) without an in-body rewrite of
 * vanilla's re-roll loop.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers} — animal AI, pathfinding and zone
 * membership are server-authoritative in MP ({@code IsoAnimal.updateInternal} runs the behavior,
 * data and {@code checkZone} passes only when {@code !GameClient.client}), and clients receive the
 * resulting movement over the wire. Containment and the stray radius are tunable live via the
 * {@code Storm.AnimalZoneContainment} and {@code Storm.AnimalZoneLeashDistance} sandbox options.
 */
public class AnimalZoneContainmentPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalzonecontainment.";

    public AnimalZoneContainmentPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "AnimalBreakObstacleAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named(
                                                        "shouldBreakObstaclesDuringPathfinding")
                                                .or(ElementMatchers.named("animalShouldThump"))
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "AnimalPathToLocationAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("pathToLocation")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                int.class, int.class, int.class))));
    }
}
