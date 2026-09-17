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
 * Client-only. Guards the null config dereference in {@code IsoBulletTracerEffects.createEffect}:
 *
 * <pre>NullPointerException: Cannot read field "projectileRed"
 *   because "isoBulletTracerEffectsConfigOption" is null
 *   at IsoBulletTracerEffects.createEffect ... addEffect
 *   at TracerInfo.process ... PlayerHitZombie ... GameClient.mainLoopDealWithNetData</pre>
 *
 * <p>{@code createEffect} reads the per-ammo config with a plain {@code HashMap.get} and
 * dereferences it on the next line; the sibling {@code getOrCreate} in the same class creates the
 * entry on a miss. The map is populated only by {@code Item.resolveItemTypes()}, which silently
 * skips a weapon script whose ammo type is unregistered, whose ammo item is not installed, or whose
 * item key does not match the module-qualified name — so a modded gun can fire with no entry. The
 * throw lands inside the per-packet catch of {@code GameClient.mainLoopDealWithNetData}, so it
 * drops that one hit packet's tracer and leaks one pooled {@code Effect} rather than latching.
 *
 * <p>The advice seeds a missing entry through the public {@code load(AmmoType)} and lets the
 * vanilla body run; when no weapon or ammo type can be named it skips the body so the method
 * returns null. Once every created effect has an entry, the same unguarded read in {@code
 * updateSettings} (debug-mode render only) cannot miss either. See {@code BulletTracerConfigGuard}
 * for the recovery and its once-per-outcome logging, which names the weapon's full type.
 *
 * <p>Why a client bytecode patch: the miss is inside a private client-only Java method reached from
 * the packet loop, with no Lua event and no server-observable state, so none of the cheaper tiers
 * reach it. Fail-soft: {@code suppress = Throwable.class} resolves any advice failure to "run
 * vanilla", the helper catches its own failures and skips the one tracer, and a missing target
 * method fails the transform loudly at weave time (logged, class left vanilla) rather than silently
 * no-opping.
 */
public class IsoBulletTracerEffectsConfigNullGuardPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.objects.IsoBulletTracerEffects";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.tracerconfigguard."
                    + "IsoBulletTracerEffectsCreateEffectAdvice";

    public IsoBulletTracerEffectsConfigNullGuardPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        ElementMatcher.Junction<MethodDescription> createEffect =
                ElementMatchers.named("createEffect").and(ElementMatchers.takesArguments(1));
        if (target.getDeclaredMethods().filter(createEffect).isEmpty()) {
            throw new IllegalStateException(
                    "IsoBulletTracerEffectsConfigNullGuardPatch: IsoBulletTracerEffects no longer"
                            + " declares a 1-arg createEffect — the hook would silently no-op and"
                            + " reintroduce the null tracer-config NPE. Re-verify the patch"
                            + " against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator).on(createEffect));
    }
}
