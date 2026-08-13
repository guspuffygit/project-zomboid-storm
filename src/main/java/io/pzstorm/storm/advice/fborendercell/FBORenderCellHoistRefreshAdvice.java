package io.pzstorm.storm.advice.fborendercell;

import io.pzstorm.storm.patch.performance.FBORenderCellHoistCache;
import net.bytebuddy.asm.Advice;

/**
 * Entry advice for {@code FBORenderCell.calculateObjectRenderInfo(IsoGridSquare)} and {@code
 * FBORenderCell.renderJoinedRoofTile} — the only two methods through which the {@code
 * isObjectRenderLayer_*} helpers are reachable. Snapshots the hoisted frame-constants once per
 * square pass instead of once per object; see {@link FBORenderCellHoistCache} for the parity
 * argument. {@code refresh()} never throws, so no suppress handling is needed here.
 */
public class FBORenderCellHoistRefreshAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        FBORenderCellHoistCache.refresh();
    }
}
