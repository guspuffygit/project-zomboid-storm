package io.pzstorm.storm.advice.ecsmemo;

import io.pzstorm.storm.entity.StormEcsMemoHolder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import zombie.characters.ecs.ECSComponent;

/**
 * Advice for the {@code ECSEntity.tryGetECSComponent(Class)} default method: consults the
 * per-character memo installed by {@code IsoGameCharacterEcsMemoPatch} before running the vanilla
 * {@code getECSClass} superclass walk plus {@code HashMap} probe. See {@link StormEcsMemoHolder}
 * for layout and the ownership-revalidation invariant that keeps a positive hit exactly equivalent
 * to the vanilla lookup.
 *
 * <p>Null results are memoized as {@link StormEcsMemoHolder#ABSENT}; the memo is dropped wholesale
 * by {@link EcsSetComponentMemoClearAdvice} whenever a component is registered, so a negative hit
 * is only served while the component map is provably unchanged for that key. Entities that do not
 * implement {@link StormEcsMemoHolder} fall through untouched, including when the field patch
 * didn't apply.
 *
 * <p>Known (accepted) divergences: a component that is removed from its requested class key and
 * re-registered on the same entity under a <em>different</em> component class would still validate
 * as owned (no game code does this); and a {@code setECSComponent} racing a {@code
 * tryGetECSComponent} on the same entity from another thread could leave a stale negative entry —
 * vanilla's unsynchronized {@code HashMap} is equally unsafe under that race, and registration only
 * happens on the main thread (constructors, {@code IsoPlayer.setNpc}).
 *
 * <p>No lambdas / streams — advice bodies are inlined into the target method. The pair-slot null
 * check guards racy unsynchronized publication (see {@link StormEcsMemoHolder}).
 */
public class EcsTryGetComponentMemoAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Object self,
            @Advice.Argument(0) Class<?> componentTypeClass,
            @Advice.Local("memoHit") Object memoHit) {
        if (!(self instanceof StormEcsMemoHolder)) {
            return 0;
        }
        Object[] memo = ((StormEcsMemoHolder) self).getStormEcsMemo();
        if (memo == null) {
            return 0;
        }
        Object[] pair = (Object[]) memo[(System.identityHashCode(componentTypeClass) >>> 4) & 7];
        if (pair == null || pair[0] != componentTypeClass) {
            return 0;
        }
        Object component = pair[1];
        if (component == null) {
            return 0;
        }
        if (component == StormEcsMemoHolder.ABSENT) {
            memoHit = null;
            return 1;
        }
        if (((ECSComponent) component).getECSOwnerEntity() != self) {
            return 0;
        }
        memoHit = component;
        return 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object self,
            @Advice.Argument(0) Class<?> componentTypeClass,
            @Advice.Enter int code,
            @Advice.Local("memoHit") Object memoHit,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret) {
        if (code == 1) {
            ret = memoHit;
            return;
        }
        if (!(self instanceof StormEcsMemoHolder)) {
            return;
        }
        StormEcsMemoHolder holder = (StormEcsMemoHolder) self;
        Object[] memo = holder.getStormEcsMemo();
        if (memo == null) {
            memo = new Object[8];
            holder.setStormEcsMemo(memo);
        }
        Object[] pair = new Object[2];
        pair[0] = componentTypeClass;
        pair[1] = ret == null ? StormEcsMemoHolder.ABSENT : ret;
        memo[(System.identityHashCode(componentTypeClass) >>> 4) & 7] = pair;
    }
}
