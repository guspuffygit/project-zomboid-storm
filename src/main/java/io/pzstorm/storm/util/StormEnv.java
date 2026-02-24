package io.pzstorm.storm.util;

public class StormEnv {

    public static boolean isStormLocal() {
        return "local".equals(System.getProperty("stormType"));
    }

    public static boolean isStormServer() {
        return Boolean.getBoolean("storm.server");
    }
}
