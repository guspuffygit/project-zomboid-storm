package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes a vanilla bug where a chair boxed in on all three of its seating sides can never be sat in
 * — "Rest" silently degrades to sitting on the floor.
 *
 * <p>Chair sprites carry {@code solidtrans}, so {@code PolygonalMap2} treats the chair's own square
 * as non-standable. {@code PathFindBehavior2.pathToSitOnFurnitureNoSpriteGrid} therefore nudges
 * each seating position out onto the Front/Left/Right neighbour tile and adds it as a pathfinding
 * target <em>without</em> checking that anything can stand there. Put a table in front of a chair
 * and chairs on both sides — a perfectly ordinary dining set — and all three targets are
 * unreachable, the A* fails, and {@code ISWorldObjectContextMenu.onRestPathFailed} falls back to
 * {@code ISSitOnGround}.
 *
 * <p>The advice appends the chair's remaining walkable orthogonal neighbours as additional {@code
 * targetXyz} alternates, and only when every vanilla-produced position is unreachable, so a chair
 * that already works keeps its existing approach order.
 *
 * <p>This is a client-side patch. Cheaper tiers can't reach it: the broken target list is built and
 * consumed entirely inside {@code PathFindBehavior2}, which exposes neither the list nor a
 * post-gather hook to Lua, and the whole seating decision happens client-side before any packet is
 * sent. It fails soft — {@link
 * io.pzstorm.storm.advice.pathfindbehavior2.SitOnFurnitureFallbackAdvice} catches every throwable,
 * logs it, and leaves the vanilla target list untouched. Because it hooks a private method,
 * re-validate the method name and signature on every game update.
 */
public class SitOnFurnitureBoxedInChairPatch extends StormClassTransformer {

    public SitOnFurnitureBoxedInChairPatch() {
        super("zombie.pathfind.PathFindBehavior2");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                "io.pzstorm.storm.advice.pathfindbehavior2.SitOnFurnitureFallbackAdvice")
                                        .resolve(),
                                locator)
                        .on(ElementMatchers.named("pathToSitOnFurnitureNoSpriteGrid")));
    }
}
