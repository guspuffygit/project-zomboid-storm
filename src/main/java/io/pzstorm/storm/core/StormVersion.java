package io.pzstorm.storm.core;

public class StormVersion {

    private StormVersion() {}

    public static String getVersion() {
        String stormVersionFromEnv = System.getenv("STORM_VERSION");

        return stormVersionFromEnv == null
                ? System.getProperty("STORM_VERSION", "0.2.1")
                : stormVersionFromEnv;
    }
}
