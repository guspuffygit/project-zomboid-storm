package com.sentientsimulations.storm.core;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.mod.ZomboidMod;
import java.util.List;

/**
 * A test {@link ZomboidMod} that provides a {@link TestModTransformer} via
 * {@link #getClassTransformers()}, used to verify the transformer collection pipeline.
 */
public class TestTransformerMod implements ZomboidMod {

    @Override
    public void registerEventHandlers() {
        // no-op for testing
    }

    @Override
    public List<StormClassTransformer> getClassTransformers() {
        return List.of(new TestModTransformer());
    }
}
