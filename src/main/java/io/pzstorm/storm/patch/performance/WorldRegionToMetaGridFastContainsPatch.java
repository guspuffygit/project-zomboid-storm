package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Swaps {@code WorldRegionToMetaGrid.worldRegions} for {@link
 * io.pzstorm.storm.util.StormFastContainsList} at construction time.
 *
 * <p>When a client's region worker swaps in new region data, {@code IsoRegions.update} runs {@code
 * WorldRegionToMetaGrid.clientProcessBuildings}, which calls {@code
 * IsoRegions.getIsoWorldRegionsInCell} twice (once from {@code removeUserDefinedBuildingsFromCell},
 * once from {@code createBuildingsFromRegions}) for every changed cell and each of its 8
 * neighbours, always passing this field. {@code DataRoot.getIsoWorldRegionsInCell} walks every
 * chunk region on all 32 levels of the cell and dedupes with {@code ArrayList.contains} on the list
 * it is filling, so each call is chunk regions x distinct world regions. On a join the whole loaded
 * area arrives at once: a client JFR of one join (2026-09-14) had the main thread stalled twice for
 * about 2.9 s, 535 samples under {@code IsoRegions.update}, and 279 of them were that {@code
 * contains}.
 *
 * <p>Two visitor steps, the same as {@code IsoCellProcessListsFastContainsPatch}: a {@link
 * ModifierAdjustment} strips {@code final} from the field, then constructor-exit advice replaces
 * the list. {@code IsoWorldRegion} overrides neither {@code equals} nor {@code hashCode}, so the
 * mirror's lookups match the vanilla scan exactly, and {@code getIsoWorldRegionsInCell} is the only
 * code that fills the field (both callers only iterate it afterwards). {@code allWorldRegions} is
 * left alone: its hot operation is {@code remove} of an element that is present, which the mirror
 * does not make cheaper.
 *
 * <p>Client JVMs only by registration: {@code IsoRegions.update} skips the rebuild when {@code
 * GameServer.server} is set.
 */
public class WorldRegionToMetaGridFastContainsPatch extends StormClassTransformer {

    private static final String TARGET =
            "zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid";
    private static final String PKG = "io.pzstorm.storm.advice.fastcontains.";

    public WorldRegionToMetaGridFastContainsPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredFields().filter(ElementMatchers.named("worldRegions")).isEmpty()) {
            throw new IllegalStateException(
                    "WorldRegionToMetaGridFastContainsPatch: WorldRegionToMetaGrid no longer"
                            + " declares worldRegions — the constructor swap would silently leave"
                            + " the vanilla list in place. Re-verify against the current game"
                            + " source.");
        }
        return builder.visit(
                        new ModifierAdjustment()
                                .withFieldModifiers(
                                        ElementMatchers.named("worldRegions"),
                                        FieldManifestation.PLAIN))
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "WorldRegionToMetaGridSwapAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.isConstructor()));
    }
}
