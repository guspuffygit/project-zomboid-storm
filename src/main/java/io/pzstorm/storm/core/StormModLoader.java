package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ObjectArrays;
import io.pzstorm.storm.mod.*;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import zombie.core.Core;
import zombie.core.GameVersion;

/**
 * This class is responsible for loading mod components:
 *
 * <ul>
 *   <li>Catalog mod jars by mapping them to mod directory name.
 *   <li>Catalog mod metadata by mapping them to mod directory name.
 *   <li>Catalog mod classes by mapping them to mod directory name.
 *   <li>Load mod classes with {@link StormClassLoader}.
 * </ul>
 */
public class StormModLoader extends URLClassLoader {

    static final Map<String, ImmutableSet<Class<?>>> CLASS_CATALOG = new HashMap<>();

    private static final Map<String, StormMod> STORM_MODS = new HashMap<>();

    /** Matches version folder names like "42", "42.1", "42.1.5" (third component is stripped). */
    private static final Pattern VERSION_FOLDER_PATTERN =
            Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.\\d+)?$");

    StormModLoader(URL[] urls) {
        super(ObjectArrays.concat(urls, getResourcePaths(), URL.class));
    }

    public StormModLoader() {
        super(getResourcePaths());
    }

    /**
     * Returns an array of paths pointing to resources loaded by {@code StormModLoader}. This method
     * will return an empty array if no jars are cataloged.
     */
    private static URL[] getResourcePaths() {
        List<URL> result = new ArrayList<>();
        for (StormMod stormMod : STORM_MODS.values()) {
            for (ModJar modJar : stormMod.getModJars()) {
                result.add(modJar.getResourcePath());
                try {
                    result.add(modJar.getFilePath().getParent().toUri().toURL());
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return result.toArray(new URL[0]);
    }

    public static List<JarFile> getModJars() {
        return STORM_MODS.values().stream()
                .flatMap(mod -> mod.getModJars().stream())
                .collect(Collectors.toList());
    }

    public static void catalogModJars(List<Path> modDirectories) throws IOException {
        STORM_MODS.clear();

        GameVersion gameVersion = Core.getInstance().getGameVersion();

        for (Path modDir : modDirectories) {
            Path commonDir = modDir.resolve("common");
            if (!Files.exists(commonDir) || !Files.isDirectory(commonDir)) {
                LOGGER.debug("Skipping loading, common/ not found: {}", modDir.toAbsolutePath());
                continue;
            }

            // B42: mod.info lives in the best matching version folder; fall back to root.
            Path versionDir = findBestVersionFolder(modDir, gameVersion);

            Path modInfoPath = null;
            if (versionDir != null) {
                Path candidate = versionDir.resolve("mod.info");
                if (Files.isRegularFile(candidate)) {
                    modInfoPath = candidate;
                }
            }
            if (modInfoPath == null) {
                modInfoPath = modDir.resolve("mod.info");
            }

            if (!Files.isRegularFile(modInfoPath)) {
                LOGGER.debug("Skipping loading, mod.info not found: {}", modDir.toAbsolutePath());
                continue;
            }

            ModInfo modInfo = new ModInfo(Files.readString(modInfoPath));
            if (modInfo.getName().isEmpty() || modInfo.getId().isEmpty()) {
                LOGGER.debug("Skipping loading, name or id not found: {}", modDir.toAbsolutePath());
                continue;
            }

            List<ModJar> modJars = new ArrayList<>();
            collectJarsFromDirectory(commonDir, modJars);

            if (versionDir != null) {
                collectJarsFromDirectory(versionDir, modJars);
            }

            if (modJars.isEmpty()) {
                LOGGER.debug("Skipping loading, no jars found in: {}", modDir.toAbsolutePath());
                continue;
            }

            STORM_MODS.put(modInfo.getId().get(), new StormMod(modInfo, modJars));
        }
    }

    /** Collects all {@code .jar} files directly inside {@code dir} into {@code modJars}. */
    private static void collectJarsFromDirectory(Path dir, List<ModJar> modJars) {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(
                            jar -> {
                                try {
                                    File jarFile = jar.toFile();
                                    if (jarFile.getAbsolutePath().endsWith(".jar")) {
                                        modJars.add(new ModJar(jarFile));
                                    }
                                } catch (IOException e) {
                                    LOGGER.error(
                                            "Unable to load ModJar: {}", jar.toAbsolutePath(), e);
                                    throw new RuntimeException(e);
                                }
                            });
        } catch (IOException e) {
            LOGGER.error("Unable to list mod jars: {}", dir.toAbsolutePath(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the subdirectory of {@code modDir} whose version is the highest version less than or
     * equal to {@code gameVersion}, or {@code null} if no such folder exists.
     *
     * <p>Folder naming rules (PZ wiki): {@code build}, {@code build.major}, and {@code
     * build.major.minor} are all accepted; the third component (minor) is ignored.
     */
    private static Path findBestVersionFolder(Path modDir, GameVersion gameVersion)
            throws IOException {
        List<Path> candidates;
        try (Stream<Path> stream = Files.list(modDir)) {
            candidates = stream.filter(Files::isDirectory).collect(Collectors.toList());
        }

        Path bestPath = null;
        GameVersion bestVersion = null;

        for (Path candidate : candidates) {
            GameVersion folderVersion = parseVersionFolder(candidate.getFileName().toString());
            if (folderVersion == null || folderVersion.isGreaterThan(gameVersion)) {
                continue;
            }
            if (bestVersion == null || folderVersion.isGreaterThan(bestVersion)) {
                bestVersion = folderVersion;
                bestPath = candidate;
            }
        }

        return bestPath;
    }

    /**
     * Parses a version folder name into a {@link GameVersion}.
     *
     * <p>Returns {@code null} if the name does not match a version pattern. The third (minor)
     * component is stripped per PZ wiki rules: {@code 42.1.5} is treated as {@code 42.1}.
     */
    private static GameVersion parseVersionFolder(String folderName) {
        Matcher m = VERSION_FOLDER_PATTERN.matcher(folderName);
        if (!m.matches()) {
            return null;
        }
        try {
            int major = Integer.parseInt(m.group(1));
            int minor = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            return new GameVersion(major, minor, "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<Path> listWorkshopDirectories(Path workshopFolder) throws IOException {
        if (!workshopFolder.toFile().exists()) {
            return Collections.emptyList();
        }

        List<Path> targetFolders = new ArrayList<>();

        List<Path> modProjectFolders;
        try (Stream<Path> stream = Files.list(workshopFolder)) {
            modProjectFolders = stream.filter(Files::isDirectory).toList();
        }

        for (Path projectFolder : modProjectFolders) {
            Path contentsMods = projectFolder.resolve("Contents").resolve("mods");

            // For local workshop directories
            if (Files.exists(contentsMods) && Files.isDirectory(contentsMods)) {
                try (Stream<Path> modStream = Files.list(contentsMods)) {
                    List<Path> subMods = modStream.filter(Files::isDirectory).toList();

                    targetFolders.addAll(subMods);
                }
            }

            // For workshop directories
            Path mods = projectFolder.resolve("mods");
            if (Files.exists(mods) && Files.isDirectory(mods)) {
                try (Stream<Path> modStream = Files.list(mods)) {
                    List<Path> subMods = modStream.filter(Files::isDirectory).toList();
                    targetFolders.addAll(subMods);
                }
            }
        }

        return targetFolders;
    }

    private static List<Path> listModDirectories(Path path) {
        if (!path.toFile().exists()) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.list(path)) {
            return stream.filter(Files::isDirectory).collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Called by {@link StormBootstrap#loadAndRegisterMods()} */
    public static void loadStormModClasses() {
        LOGGER.info("{} mod class catalog", STORM_MODS.isEmpty() ? "Building" : "Rebuilding");

        CLASS_CATALOG.clear();

        for (Map.Entry<String, StormMod> entry : STORM_MODS.entrySet()) {
            Set<Class<?>> modClasses = new HashSet<>();
            for (ModJar modJar : entry.getValue().getModJars()) {
                Enumeration<JarEntry> jarEntries = modJar.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry jarEntry = jarEntries.nextElement();
                    if (jarEntry.isDirectory() || !jarEntry.getName().endsWith(".class")) {
                        continue;
                    }
                    String entryName = jarEntry.getName();
                    String className = entryName.substring(0, entryName.length() - 6);
                    try {
                        modClasses.add(
                                StormBootstrap.CLASS_LOADER.loadClass(
                                        className.replace('/', '.'), true));
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            CLASS_CATALOG.put(entry.getKey(), ImmutableSet.copyOf(modClasses));

            LOGGER.debug("Created new metadata catalog entry:");
            LOGGER.debug("Found {} classes in mod directory {}", modClasses.size(), entry.getKey());
        }
    }

    /** Called by {@link StormBootstrap#loadAndRegisterMods()} */
    public static void loadStormMods() {
        try {
            List<Path> workshopDirectories =
                    listWorkshopDirectories(StormPaths.getWorkshopDirectory());
            List<Path> modsDirectories = listModDirectories(StormPaths.getModsDirectory());
            List<Path> localWorkshopDirectories =
                    listWorkshopDirectories(StormPaths.getLocalWorkshopDirectory());

            workshopDirectories.forEach(
                    (dir) -> LOGGER.debug("Workshop directory: {}", dir.toAbsolutePath()));
            modsDirectories.forEach(
                    (dir) -> LOGGER.debug("Mod directory: {}", dir.toAbsolutePath()));
            localWorkshopDirectories.forEach(
                    (dir) -> LOGGER.debug("Local workshop directory: {}", dir.toAbsolutePath()));

            boolean preferLocal = "local".equals(System.getProperty("stormType"));

            catalogModJars(
                    Stream.of(
                                    preferLocal ? workshopDirectories : localWorkshopDirectories,
                                    modsDirectories,
                                    preferLocal ? localWorkshopDirectories : workshopDirectories)
                            .flatMap(List::stream)
                            .collect(Collectors.toList()));
        } catch (Exception e) {
            LOGGER.error("Unable to load storm mods", e);
            throw new RuntimeException(e);
        }
    }
}
