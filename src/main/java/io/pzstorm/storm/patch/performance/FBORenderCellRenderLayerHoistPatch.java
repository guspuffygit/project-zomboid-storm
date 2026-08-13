package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.iso.IsoWater;

/**
 * EXPERIMENTAL, CLIENT-SIDE, opt-in via {@code -Dstorm.experimental.clientperf=true}. This is a
 * deliberate, user-approved exception to the no-client-patches rule — do not use it as precedent,
 * and do not register it outside the experimental gate.
 *
 * <p>{@code FBORenderCell.calculateObjectRenderLayer} and its {@code isObjectRenderLayer_*} helpers
 * profiled at 4.4% of MainThread self-time — they run for every object on every non-empty square
 * whenever a chunk-level is dirty, and re-query per object three values that are constant across a
 * render pass: {@code Core.getInstance().getOptionDoWindSpriteEffects()}, {@code
 * IsoPlayer.getInstance().isClimbing()} and {@code IsoWater.getInstance().getShaderEnable()}.
 *
 * <p>This patch hoists those lookups to once per square pass:
 *
 * <ul>
 *   <li>{@link io.pzstorm.storm.advice.fborendercell.FBORenderCellHoistRefreshAdvice} snapshots the
 *       three values at the entry of {@code calculateObjectRenderInfo(IsoGridSquare)} and {@code
 *       renderJoinedRoofTile} — the only two paths from which the helpers are reachable.
 *   <li>{@code MemberSubstitution} scoped to the five {@code isObjectRenderLayer_*} helpers
 *       replaces the per-object calls with {@link FBORenderCellHoistCache} bridges that return the
 *       snapshot only when the receiver is the exact sampled instance and otherwise delegate to the
 *       real call (fail-soft and parity-exact; see the cache class).
 * </ul>
 *
 * <p>The {@code "doorTrans"} string-property lookup in the same helpers is flattened separately by
 * {@link PropertyContainerHasStringIdCachePatch}. The remaining property queries in the helpers —
 * {@code has(IsoFlagType)} and {@code getSlopedSurfaceDirection()} — are already a long-bitfield
 * test and a flag-guarded field read in vanilla, so they are left untouched.
 */
public class FBORenderCellRenderLayerHoistPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.fborendercell.";

    public FBORenderCellRenderLayerHoistPatch() {
        super("zombie.iso.fboRenderChunk.FBORenderCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            ElementMatcher.Junction<MethodDescription> helperMatcher =
                    ElementMatchers.<MethodDescription>named("isObjectRenderLayer_Floor")
                            .or(ElementMatchers.named("isObjectRenderLayer_MinusFloor"))
                            .or(ElementMatchers.named("isObjectRenderLayer_MinusFloorSE"))
                            .or(ElementMatchers.named("isObjectRenderLayer_TranslucentFloor"))
                            .or(ElementMatchers.named("isObjectRenderLayer_Translucent"));
            Advice refreshAdvice =
                    Advice.to(
                            typePool.describe(PKG + "FBORenderCellHoistRefreshAdvice").resolve(),
                            locator);
            return builder.visit(
                            refreshAdvice.on(
                                    ElementMatchers.named("calculateObjectRenderInfo")
                                            .and(ElementMatchers.takesArguments(1))))
                    .visit(
                            refreshAdvice.on(
                                    ElementMatchers.named("renderJoinedRoofTile")
                                            .and(ElementMatchers.takesArguments(4))))
                    .visit(
                            MemberSubstitution.relaxed()
                                    .method(
                                            ElementMatchers.named("getOptionDoWindSpriteEffects")
                                                    .and(ElementMatchers.takesArguments(0)))
                                    .replaceWith(
                                            FBORenderCellHoistCache.class.getDeclaredMethod(
                                                    "getOptionDoWindSpriteEffects", Core.class))
                                    .on(helperMatcher))
                    .visit(
                            MemberSubstitution.relaxed()
                                    .method(
                                            ElementMatchers.named("isClimbing")
                                                    .and(ElementMatchers.takesArguments(0)))
                                    .replaceWith(
                                            FBORenderCellHoistCache.class.getDeclaredMethod(
                                                    "isClimbing", IsoGameCharacter.class))
                                    .on(helperMatcher))
                    .visit(
                            MemberSubstitution.relaxed()
                                    .method(
                                            ElementMatchers.named("getShaderEnable")
                                                    .and(ElementMatchers.takesArguments(0)))
                                    .replaceWith(
                                            FBORenderCellHoistCache.class.getDeclaredMethod(
                                                    "getShaderEnable", IsoWater.class))
                                    .on(helperMatcher));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for FBORenderCell render-layer hoist", e);
        }
    }
}
