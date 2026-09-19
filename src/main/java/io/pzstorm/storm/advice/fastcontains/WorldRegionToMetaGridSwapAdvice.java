package io.pzstorm.storm.advice.fastcontains;

import io.pzstorm.storm.util.StormFastContainsList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Constructor-exit advice for {@code WorldRegionToMetaGrid()}: replaces the freshly initialized
 * {@code worldRegions} scratch list with {@link StormFastContainsList}, so the dedupe in {@code
 * DataRoot.getIsoWorldRegionsInCell} ({@code if (!worldRegions.contains(r)) worldRegions.add(r)}
 * for every chunk region of the cell) stops scanning the list it is filling. The patch strips
 * {@code final} from the field so the write verifies.
 *
 * <p>Field bound as {@code Object} with dynamic typing, same as {@link
 * IsoCellProcessListsSwapAdvice}. No lambdas / streams: the body is inlined into the constructor.
 */
public class WorldRegionToMetaGridSwapAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(
                            value = "worldRegions",
                            readOnly = false,
                            typing = Assigner.Typing.DYNAMIC)
                    Object worldRegions) {
        worldRegions = StormFastContainsList.copyOf(worldRegions);
    }
}
