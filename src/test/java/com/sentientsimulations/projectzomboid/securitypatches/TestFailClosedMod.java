package com.sentientsimulations.projectzomboid.securitypatches;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.mod.ZomboidMod;
import java.util.List;

/**
 * A test {@link ZomboidMod} that lives in the security mod's package and throws from {@link
 * #getClassTransformers()}, so tests can check that a security mod fails closed on a server.
 */
public class TestFailClosedMod implements ZomboidMod {

    @Override
    public void registerEventHandlers() {
        // no-op for testing
    }

    @Override
    public List<StormClassTransformer> getClassTransformers() {
        throw new NoClassDefFoundError("zombie/test/MissingVanillaClass");
    }
}
