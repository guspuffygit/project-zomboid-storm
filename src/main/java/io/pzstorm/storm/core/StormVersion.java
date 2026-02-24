package io.pzstorm.storm.core;

public class StormVersion {

    private StormVersion() {}

    public static String getVersion() {
        Package pkg = StormVersion.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null ? version : "dev";
    }
}
