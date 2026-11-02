package io.pzstorm.storm.patch.fixes;

import java.util.Set;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * Replacement body for {@code DesignationZoneAnimal.doMeta(int)}.
 *
 * <p>Vanilla 42.22 wraps the baby catch-up in an extra {@code for (i < size)} loop, so every baby
 * runs {@code updateStatsAway(hours)} once per animal in the zone. This walks the zone once, the
 * way the 42.21 body did, and keeps the rest of the method verbatim.
 *
 * <p>The parameter is typed {@code Object} so the inlined advice call site carries an explicit
 * checkcast and the JVM does not resolve {@code DesignationZoneAnimal} before the transformer
 * registers.
 */
public final class AnimalZoneMetaCatchUp {

    private AnimalZoneMetaCatchUp() {}

    public static void doMeta(Object zoneRef, int hours) {
        DesignationZoneAnimal zone = (DesignationZoneAnimal) zoneRef;
        zone.check();
        Set<IsoAnimal> animals = zone.getAnimalsInZone();

        for (IsoAnimal animal : animals) {
            if (!animal.isBaby()) {
                animal.updateStatsAway(hours);
            }
        }

        for (IsoAnimal animal : animals) {
            if (animal.isBaby()) {
                animal.updateStatsAway(hours);
            }
        }

        for (int i = 0; i < zone.hutchs.size(); i++) {
            zone.hutchs.get(i).doMeta(hours);
        }

        for (IsoAnimal animal : animals) {
            animal.forceWanderNow();
        }
    }
}
