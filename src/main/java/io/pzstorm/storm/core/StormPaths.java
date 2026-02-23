package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.Getter;
import zombie.Lua.LuaManager;

public class StormPaths {

    private static final File luaCacheDir = new File(LuaManager.getLuaCacheDir());

    /** Directory to store persisted global data */
    @Getter private static final File stormDataDirectory = initStormDataDirectory();

    private static File initStormDataDirectory() {
        File dir = new File(luaCacheDir, "storm");

        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    public static Path findFileInParents(String targetFileName) throws RuntimeException {
        Path currentDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

        while (currentDir != null) {
            Path targetPath = currentDir.resolve(targetFileName);

            if (Files.exists(targetPath)) {
                LOGGER.debug("Found file at: {}", targetPath);
                return targetPath;
            }

            currentDir = currentDir.getParent();
        }

        throw new RuntimeException(
                "Could not find '" + targetFileName + "' in any parent directory.");
    }

    public static Path getWorkshopDirectory() {
        Path steamappsDirectory = findFileInParents("steamapps");
        return steamappsDirectory.resolve("workshop").resolve("content").resolve("108600");
    }

    public static Path getModsDirectory() {
        return Path.of(System.getProperty("user.home"), "Zomboid", "mods");
    }

    public static Path getLocalWorkshopDirectory() {
        return Path.of(System.getProperty("user.home"), "Zomboid", "Workshop");
    }
}
