package io.pzstorm.storm.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class StormVersion {

    private static final String VERSION = loadVersion();

    private StormVersion() {}

    public static String getVersion() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in =
                StormVersion.class.getResourceAsStream("/storm-version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String version = props.getProperty("version");
                if (version != null && !version.isEmpty()) {
                    return version;
                }
            }
        } catch (IOException ignored) {
        }
        return "dev";
    }
}
