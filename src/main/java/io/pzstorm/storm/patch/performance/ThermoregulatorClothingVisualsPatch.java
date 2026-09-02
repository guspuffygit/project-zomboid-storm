package io.pzstorm.storm.patch.performance;

/**
 * {@code Thermoregulator.updateClothing()}: the per-tick {@code getItemVisuals} rebuild (whose
 * result is only identity-diffed against the previous tick's) now comes from the per-character
 * memo. See {@link io.pzstorm.storm.inventory.StormClothingVisuals}. Server-only by registration
 * gate.
 */
public class ThermoregulatorClothingVisualsPatch extends ClothingVisualsSubstitutionPatch {

    public ThermoregulatorClothingVisualsPatch() {
        super("zombie.characters.BodyDamage.Thermoregulator", "updateClothing");
    }
}
