package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.logging.StormLogger;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoWater;

/**
 * Per-render-pass snapshot of the frame-constant lookups {@link FBORenderCellRenderLayerHoistPatch}
 * hoists out of the {@code FBORenderCell.isObjectRenderLayer_*} helpers: {@code
 * Core.getOptionDoWindSpriteEffects()}, {@code IsoPlayer.isClimbing()} and {@code
 * IsoWater.getShaderEnable()}, which vanilla re-queries for every object on every dirty square.
 *
 * <p>{@link #refresh()} samples the values at the two entry points every helper invocation flows
 * through ({@code calculateObjectRenderInfo(IsoGridSquare)} and {@code renderJoinedRoofTile}), so
 * the snapshot is at most one square pass old. The bridge methods substituted into the helpers
 * return the snapshot only when the receiver is the exact instance that was sampled; any other
 * receiver — null, a different {@code IsoPlayer} after a splitscreen instance switch, a refresh
 * that failed — delegates to the real call, so behavior (including the NPE vanilla throws on a null
 * receiver) is structurally identical to vanilla.
 *
 * <p>No synchronization: the refresh points and the helpers all run on the render-prep pass of the
 * main thread.
 */
public final class FBORenderCellHoistCache {

    private static Core windCore;
    private static boolean windValue;
    private static IsoPlayer climbingPlayer;
    private static boolean climbingValue;
    private static IsoWater shaderWater;
    private static boolean shaderValue;
    private static boolean disabled;

    private FBORenderCellHoistCache() {}

    /**
     * Called from {@link io.pzstorm.storm.advice.fborendercell.FBORenderCellHoistRefreshAdvice}.
     * Never throws: a failure logs once, clears the snapshot and permanently disables the fast path
     * for the session, leaving the bridges on their vanilla-delegation branch.
     */
    public static void refresh() {
        if (disabled) {
            return;
        }
        try {
            Core core = Core.getInstance();
            windCore = null;
            if (core != null) {
                windValue = core.getOptionDoWindSpriteEffects();
                windCore = core;
            }
            IsoPlayer player = IsoPlayer.getInstance();
            climbingPlayer = null;
            if (player != null) {
                climbingValue = player.isClimbing();
                climbingPlayer = player;
            }
            IsoWater water = IsoWater.getInstance();
            shaderWater = null;
            if (water != null) {
                shaderValue = water.getShaderEnable();
                shaderWater = water;
            }
        } catch (Throwable t) {
            disabled = true;
            windCore = null;
            climbingPlayer = null;
            shaderWater = null;
            StormLogger.LOGGER.error(
                    "FBORenderCell hoist cache refresh failed - falling back to vanilla"
                            + " lookups for this session",
                    t);
        }
    }

    public static boolean getOptionDoWindSpriteEffects(Core core) {
        if (core != null && core == windCore) {
            return windValue;
        }
        return core.getOptionDoWindSpriteEffects();
    }

    public static boolean isClimbing(IsoGameCharacter character) {
        if (character != null && character == climbingPlayer) {
            return climbingValue;
        }
        return character.isClimbing();
    }

    public static boolean getShaderEnable(IsoWater water) {
        if (water != null && water == shaderWater) {
            return shaderValue;
        }
        return water.getShaderEnable();
    }
}
