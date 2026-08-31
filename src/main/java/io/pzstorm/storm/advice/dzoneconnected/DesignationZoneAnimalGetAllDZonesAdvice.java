package io.pzstorm.storm.advice.dzoneconnected;

import io.pzstorm.storm.patch.performance.DesignationZoneAnimalConnectedZones;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * Routes the static {@code DesignationZoneAnimal.getAllDZones(currentList, zone, previousZone)}
 * through {@link DesignationZoneAnimalConnectedZones#getAllDZones}: a non-null result is the
 * finished list and the vanilla O(n²) body is skipped (the exit advice writes it as the return
 * value); {@code null} (fail-soft latch) leaves the vanilla body to run untouched.
 */
public class DesignationZoneAnimalGetAllDZonesAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static ArrayList<DesignationZoneAnimal> onEnter(
            @Advice.Argument(0) ArrayList<DesignationZoneAnimal> currentList,
            @Advice.Argument(1) DesignationZoneAnimal zone,
            @Advice.Argument(2) DesignationZoneAnimal previousZone) {
        return DesignationZoneAnimalConnectedZones.getAllDZones(currentList, zone, previousZone);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter ArrayList<DesignationZoneAnimal> computed,
            @Advice.Return(readOnly = false) ArrayList<DesignationZoneAnimal> ret) {
        if (computed != null) {
            ret = computed;
        }
    }
}
