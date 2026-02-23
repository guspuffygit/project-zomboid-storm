package io.pzstorm.storm.jna.fmod;

import java.io.File;

public class FmodUtils {
    public static String getFmodPath() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win") || os.contains("mac")) {
            return "fmod";
        }

        String libName = "libfmod.so.13.6";
        String path = findLibraryAbsolutePath(libName);

        return (path != null) ? path : libName;
    }

    private static String findLibraryAbsolutePath(String fileName) {
        String libraryPath = System.getProperty("java.library.path", "");
        String[] paths = libraryPath.split(File.pathSeparator);

        for (String path : paths) {
            File file = new File(path + "/" + fileName);
            if (file.exists()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }
}
