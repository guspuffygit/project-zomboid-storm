package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Stops the per-frame render crash on a dropped world item whose {@code sprite} field
 * has gone null:
 *
 * <pre>ERROR: FBORenderCell.renderInternal&gt; Exception thrown
 *   NullPointerException: Cannot invoke "zombie.iso.sprite.IsoSprite.hasNoTextures()"
 *   because "this.sprite" is null at IsoWorldInventoryObject.render</pre>
 *
 * <p>{@code IsoWorldInventoryObject.render} dereferences {@code this.sprite} unguarded, while the
 * sibling {@code renderObjectPicker} right below it does test {@code sprite != null} — vanilla
 * already knows the field can be null there. The branch is only reached by items whose script sets
 * {@code isWorldRender()} (a {@code WorldStaticModel} — logs and friends) in the frames where the
 * 3D model render returns neither {@code Loading} nor {@code Ready} and the 2D sprite fallback
 * runs. The object stays on screen, so the NPE repeats every single frame: a field report showed
 * 591 identical stacks over 590 frames, and the resulting 1&nbsp;MB of stack traces evicted an
 * entire 89-minute session from the log tail.
 *
 * <p>Every constructor and load path of {@code IsoWorldInventoryObject} assigns a sprite, and the
 * class is not pooled ({@code getObjectName()} returns {@code "WorldInventoryItem"}, so the {@code
 * reset()} calls in {@code IsoChunk.doReuseGridsquares} and {@code IsoGridSquare.DeleteTileObject}
 * skip it). The advice therefore restores the broken invariant rather than skipping the render body
 * — skipping would also skip {@code WorldItemModelDrawer.renderMain}, which is how these items are
 * normally drawn. See {@code WorldItemSpriteGuard} for the repair and its once-per-item-type
 * logging, which names the offending item so the upstream producer of the null can be identified
 * from the next report.
 *
 * <p>Why a client bytecode patch: the failure is inside the client render pipeline, on a Java
 * method with no Lua event and no server-observable effect, so none of the cheaper tiers reach it.
 * Fail-soft: the guard is a null test on a field the vanilla body is about to read anyway, and any
 * failure inside the repair leaves vanilla to throw exactly as it does today.
 */
public class IsoWorldInventoryObjectRenderSpriteGuardPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.iso.objects.IsoWorldInventoryObject";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.worlditemspriteguard"
                    + ".IsoWorldInventoryObjectRenderSpriteGuardAdvice";

    public IsoWorldInventoryObjectRenderSpriteGuardPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("render").and(ElementMatchers.takesArguments(7)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "IsoWorldInventoryObjectRenderSpriteGuardPatch: IsoWorldInventoryObject no"
                            + " longer declares its 7-arg render override — the name-string hook"
                            + " would silently no-op and reintroduce the per-frame null-sprite NPE"
                            + " wall. Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("render")
                                        .and(ElementMatchers.takesArguments(7))));
    }
}
