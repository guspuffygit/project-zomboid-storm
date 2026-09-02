package io.pzstorm.storm.patch.performance;

/**
 * {@code Thermoregulator$ThermalNode.calculateInsulation()}: the per-layer {@code getVisual()}
 * (read only for {@code getHole(part)}) now returns the item's existing visual. Runs per body part
 * per worn layer every tick. See {@link io.pzstorm.storm.inventory.StormClothingVisuals}.
 * Server-only by registration gate.
 */
public class ThermalNodeInsulationVisualPatch extends ClothingVisualsSubstitutionPatch {

    public ThermalNodeInsulationVisualPatch() {
        super("zombie.characters.BodyDamage.Thermoregulator$ThermalNode", "calculateInsulation");
    }
}
