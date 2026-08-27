package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.iso.objects.IsoGenerator} by {@code IsoGeneratorElectricityPatch}
 * (the redefinition adds a {@code stormInactiveSwept} boolean plus this accessor pair), so each
 * generator remembers whether its chunk-position bookkeeping has already run a removal sweep while
 * inactive.
 *
 * <p>{@code IsoChunk.chunkLoaded} re-flags {@code updateSurrounding} on every touching generator
 * whenever a nearby chunk loads, which made Storm's fast-path advice re-run the full
 * (2·chunkRange+1)² {@code removeGeneratorPos} loop for inactive generators over and over (~0.8% of
 * server main on ATF, 2026-08-26 profile). Once an inactive generator has swept, chunks loaded
 * afterwards are cleaned by vanilla {@code IsoChunk.checkForMissingGenerators()} at load time (it
 * drops entries whose generator square is loaded and not activated), so the sweep never needs to
 * repeat until the generator is activated again.
 *
 * <p>The flag is cleared whenever the advice runs its loop for an activated generator, and any
 * activation change goes through the unpatched {@code setActivated} → {@code
 * setSurroundingElectricity} path first, so a fresh deactivation always gets exactly one removal
 * sweep.
 */
public interface StormGeneratorSweptFlag {

    boolean isStormInactiveSwept();

    void setStormInactiveSwept(boolean swept);
}
