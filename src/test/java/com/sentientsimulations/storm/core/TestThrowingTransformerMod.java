package com.sentientsimulations.storm.core;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.mod.ZomboidMod;
import java.util.List;

/** A test {@link ZomboidMod} whose {@link #getClassTransformers()} always throws. */
public class TestThrowingTransformerMod implements ZomboidMod {

    @Override
    public void registerEventHandlers() {
        // no-op for testing
    }

    @Override
    public List<StormClassTransformer> getClassTransformers() {
        throw new NoClassDefFoundError("zombie/test/MissingVanillaClass");
    }
}
