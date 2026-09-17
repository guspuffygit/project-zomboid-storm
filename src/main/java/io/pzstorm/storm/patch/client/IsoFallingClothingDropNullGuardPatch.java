package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Guards the null current-square dereference in {@code IsoFallingClothing.drop()}:
 *
 * <pre>NullPointerException: Cannot invoke "zombie.iso.IsoGridSquare.getApparentZ(float, float)"
 *   because the return value of "zombie.iso.objects.IsoFallingClothing.getCurrentSquare()" is null
 *   at IsoFallingClothing.drop ... collideWall ... IsoPhysicsObject.update
 *   at IsoCell.ProcessObjects ... IsoWorld.updateWorld ... IngameState.updateInternal</pre>
 *
 * <p>The throw escapes the world tick, {@code IngameState} catches it, saves a {@code _crash} copy
 * of the world and sends the player to the main menu ({@code force-disconnect "crash"}). One throw
 * is enough; there is no wall to spot in the log.
 *
 * <p>{@code drop()} reads {@code getCurrentSquare()} twice with no null test, while the sibling
 * {@code render()} and {@code IsoPhysicsObject.update()} both guard the same field. {@code
 * IsoMovingObject.update} nulls it on a first-frame collision ({@code current = last} with {@code
 * last} not yet assigned) and on an unloaded square; {@code IsoPhysicsObject.update} then calls
 * {@code collideWall()} → {@code drop()} in the same frame. Both creation sites — a hat knocked off
 * the local character and the {@code ZombieHelmetFalling} replay for other characters — are
 * affected.
 *
 * <p>The advice resolves the square from the item's position (the server-sent target when present)
 * and lets the vanilla body run when one exists; when none is loaded it destroys the item and skips
 * the body. See {@code FallingClothingDropGuard} for the recovery and its once-per-outcome logging.
 *
 * <p>Why a client bytecode patch: the failure is inside the client's per-frame physics update, a
 * package-private Java method with no Lua event and no server-observable state, so none of the
 * cheaper tiers reach it. Fail-soft: the advice is a null test on the field the vanilla body is
 * about to read, {@code suppress = Throwable.class} resolves any advice failure to "run vanilla",
 * and a missing target method fails the transform loudly at weave time (logged, class left vanilla)
 * rather than silently no-opping.
 */
public class IsoFallingClothingDropNullGuardPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.objects.IsoFallingClothing";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.fallingclothingguard.IsoFallingClothingDropGuardAdvice";

    public IsoFallingClothingDropNullGuardPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> drop =
                ElementMatchers.named("drop").and(ElementMatchers.takesArguments(0));
        if (target.getDeclaredMethods().filter(drop).isEmpty()) {
            throw new IllegalStateException(
                    "IsoFallingClothingDropNullGuardPatch: IsoFallingClothing no longer declares"
                            + " a no-arg drop() — the hook would silently no-op and reintroduce"
                            + " the null-square NPE that disconnects the client. Re-verify the"
                            + " patch against the current game source.");
        }
        return builder.visit(Advice.to(typePool.describe(ADVICE).resolve(), locator).on(drop));
    }
}
