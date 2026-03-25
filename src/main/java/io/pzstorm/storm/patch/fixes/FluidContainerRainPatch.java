package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.entity.Component;
import zombie.inventory.types.Moveable;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.sprite.IsoSpriteGrid;

/**
 * Fixes the vanilla bug where single-tile moveable containers (buckets, barrels) placed on the
 * ground as {@code IsoWorldInventoryObject} never collect rainwater.
 *
 * <p>The vanilla {@code FluidContainer.getRainCatcher()} and {@code isMultiTileMoveable()} check
 * only {@code getSpriteGrid() != null} to decide whether to block rain collection. This is overly
 * broad — single-tile moveables can have a non-null {@code SpriteGrid} with dimensions 1x1. The fix
 * adds a dimension check so only genuinely multi-tile moveables (width &gt; 1 or height &gt; 1) are
 * excluded.
 */
public class FluidContainerRainPatch extends StormClassTransformer {

    public FluidContainerRainPatch() {
        super("zombie.entity.components.fluids.FluidContainer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(GetRainCatcherAdvice.class)
                                .on(
                                        ElementMatchers.named("getRainCatcher")
                                                .and(ElementMatchers.takesArguments(0))))
                .visit(
                        Advice.to(IsMultiTileMoveableAdvice.class)
                                .on(
                                        ElementMatchers.named("isMultiTileMoveable")
                                                .and(ElementMatchers.takesArguments(0))));
    }

    /**
     * Fixes {@code getRainCatcher()} to only return 0 for genuinely multi-tile moveables.
     *
     * <p>The vanilla method returns 0 when the owner is an {@code IsoWorldInventoryObject} whose
     * item is a {@code Moveable} with any non-null {@code SpriteGrid}. This patch overrides the
     * return value back to the actual {@code rainCatcher} field when the grid is 1x1.
     */
    public static class GetRainCatcherAdvice {

        @Advice.OnMethodExit
        public static void onExit(
                @Advice.This Component self,
                @Advice.FieldValue("rainCatcher") float rainCatcher,
                @Advice.Return(readOnly = false) float returned) {

            if (returned == 0.0F && rainCatcher > 0.0F) {
                if (self.getOwner() instanceof IsoWorldInventoryObject obj
                        && obj.getItem() instanceof Moveable mov) {
                    IsoSpriteGrid grid = mov.getSpriteGrid();
                    if (grid != null && grid.getWidth() <= 1 && grid.getHeight() <= 1) {
                        returned = rainCatcher;
                    }
                }
            }
        }
    }

    /**
     * Fixes {@code isMultiTileMoveable()} to return false for single-tile moveables.
     *
     * <p>The vanilla method returns true for any {@code Moveable} with a non-null {@code
     * SpriteGrid}, even 1x1 grids. This patch corrects the return to false when the grid dimensions
     * are both &lt;= 1.
     */
    public static class IsMultiTileMoveableAdvice {

        @Advice.OnMethodExit
        public static void onExit(
                @Advice.This Component self, @Advice.Return(readOnly = false) boolean returned) {

            if (!returned) {
                return;
            }

            if (self.getOwner() instanceof IsoWorldInventoryObject obj
                    && obj.getItem() instanceof Moveable mov) {
                IsoSpriteGrid grid = mov.getSpriteGrid();
                if (grid != null && grid.getWidth() <= 1 && grid.getHeight() <= 1) {
                    returned = false;

                    return;
                }
            }

            if (self.getOwner() instanceof Moveable mov2) {
                IsoSpriteGrid grid = mov2.getSpriteGrid();
                if (grid != null && grid.getWidth() <= 1 && grid.getHeight() <= 1) {
                    returned = false;
                }
            }
        }
    }
}
