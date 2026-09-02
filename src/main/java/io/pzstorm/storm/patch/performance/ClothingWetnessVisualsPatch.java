package io.pzstorm.storm.patch.performance;

/**
 * {@code ClothingWetness.updateWetness(float, float)}: the leading {@code getItemVisuals} rebuild
 * and the two per-layer {@code getVisual()} hole checks now go through the memo / existing visual.
 * See {@link io.pzstorm.storm.inventory.StormClothingVisuals}. Server-only by registration gate.
 */
public class ClothingWetnessVisualsPatch extends ClothingVisualsSubstitutionPatch {

    public ClothingWetnessVisualsPatch() {
        super("zombie.characters.ClothingWetness", "updateWetness");
    }
}
