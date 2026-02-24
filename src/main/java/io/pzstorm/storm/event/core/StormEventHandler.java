package io.pzstorm.storm.event.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormBootstrap;
import io.pzstorm.storm.core.StormModLoader;
import io.pzstorm.storm.core.StormVersion;
import io.pzstorm.storm.event.lua.*;
import io.pzstorm.storm.event.zomboid.*;
import io.pzstorm.storm.lua.functions.storm.StormLua;
import io.pzstorm.storm.mod.ActiveMods;
import io.pzstorm.storm.patch.lua.LuaPatchUtils;
import io.pzstorm.storm.wrappers.ui.PersistedBooleanConfigOption;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

/**
 * This class responds to all events needed for Storm to implement custom features. Note that not
 * all functionality implementations that are weaved into game bytecode is handled here. Sometimes
 * subscribing to events is not enough to alter game behavior and more invasive actions need to be
 * preformed, like editing or removing lines from game code. Called in {@link
 * io.pzstorm.storm.core.StormLauncher#main(String[])}
 */
public class StormEventHandler {

    @SubscribeEvent
    public static void handleLuaEventTrigger(OnTriggerLuaEvent event) {
        LOGGER.trace("OnTriggerLuaEvent {}", event.getName());
        LuaEvent luaEvent =
                LuaEventFactory.constructLuaEvent(
                        event.luaEvent.name, event.args.toArray(new Object[0]));
        if (luaEvent != null) {
            luaEvent.registerCallback();
            StormEventDispatcher.dispatchEvent(luaEvent);
        } else if (!event.getName().equals("onTriggerLuaEvent")) {
            LOGGER.debug("Skip handling non-registered event '{}'", event.getName());
        }
    }

    @SubscribeEvent
    public static void handleOnLoadSoundBankEvent(OnLoadSoundBankEvent event) {
        URL soundBankResource =
                StormBootstrap.CLASS_LOADER.getResource(event.soundBankPath.toString());
        if (soundBankResource != null) {
            event.soundBankPath.delete(0, event.soundBankPath.length());
            event.soundBankPath.append(Paths.get(soundBankResource.getPath()).toAbsolutePath());

            LOGGER.info("Loaded override sound bank from '{}'", soundBankResource);
        }
    }

    @SubscribeEvent
    public static void onMainScreenRenderEvent(OnMainScreenRenderEvent event) {
        String text =
                "Storm version %s %s"
                        .formatted(StormVersion.getVersion(), System.getProperty("stormType", ""))
                        .trim();
        TextManager.instance.DrawString(
                UIFont.Small, Core.width - 290.0, Core.height - 70.0, text, 1.0, 1.0, 1.0, 0.7);
    }

    @SubscribeEvent
    public static void onMainScreenEvent(OnMainMenuEnterEvent event) {
        StormLua.setupStormLuaFunctions();
    }

    @SubscribeEvent
    public static void onZomboidGlobalsLoad(OnZomboidGlobalsLoadEvent event) {
        LOGGER.debug("OnZomboidGlobalsLoadEvent");
        boolean result =
                LuaPatchUtils.doesLuaFunctionExist("SpawnRegionMgr", "loadSpawnPointsFile");
        if (!result) {
            throw new RuntimeException("Lua was not loaded and did not exist");
        }

        try {
            LuaManager.exposer.setExposed(PersistedBooleanConfigOption.class);

            // Load files from mod jars
            for (JarFile modJar : StormModLoader.getModJars()) {
                Enumeration<JarEntry> entries = modJar.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (name.startsWith("lua/") && !entry.isDirectory() && name.endsWith(".lua")) {
                        LOGGER.debug("Found: {} in {}", name, modJar.getName());
                        try (InputStream is = modJar.getInputStream(entry)) {
                            String luaCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                            LuaPatchUtils.injectLuaCode(luaCode, name);
                        } catch (IOException e) {
                            LOGGER.error("Failed to read entry: {}", name, e);
                        }
                    }
                }
            }

            List<String> luaPaths = discoverLuaResources();
            for (String luaPath : luaPaths) {
                if (luaPath.startsWith("lua/client/") && !GameClient.client) {
                    LOGGER.debug("Skipping client lua (not a client): {}", luaPath);
                    continue;
                }
                if (luaPath.startsWith("lua/server/") && !GameServer.server) {
                    LOGGER.debug("Skipping server lua (not a server): {}", luaPath);
                    continue;
                }
                loadFromResourcePath("/" + luaPath);
            }

            if (GameClient.client
                    && !LuaPatchUtils.doesLuaFunctionExist("StormClient", "present")) {
                throw new RuntimeException("StormClient lua was not loaded correctly.");
            }

            if (GameServer.server
                    && !LuaPatchUtils.doesLuaFunctionExist("StormServer", "present")) {
                throw new RuntimeException("StormServer lua was not loaded correctly.");
            }

            if (!LuaPatchUtils.doesLuaFunctionExist("StormShared", "present")) {
                throw new RuntimeException("StormShared lua was not loaded correctly.");
            }

            LOGGER.info("All Storm lua loaded successfully.");
        } catch (Exception e) {
            LOGGER.error("Unable to load lua files from resources", e);
            throw new RuntimeException(e);
        }
    }

    private static void loadFromResourcePath(String path) {
        try (InputStream is = StormEventHandler.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("File not found at path: " + path);
            }

            String luaCode = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            LuaPatchUtils.injectLuaCode(luaCode, path);
        } catch (Exception e) {
            LOGGER.error("Unable to load path: {}", path, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Discovers all {@code .lua} files under the {@code lua/} resource directory. Works both when
     * running from a JAR and from an exploded classes directory.
     */
    private static final String KNOWN_RESOURCE = "lua/shared/storm/StormShared.lua";

    private static List<String> discoverLuaResources() {
        List<String> paths = new ArrayList<>();
        try {
            // Use a known file (not directory) to reliably locate the jar on all platforms.
            // Directory entries inside JARs are not guaranteed to exist on Windows.
            URL resourceUrl = StormEventHandler.class.getClassLoader().getResource(KNOWN_RESOURCE);
            if (resourceUrl == null) {
                LOGGER.warn("Known resource {} not found on classpath", KNOWN_RESOURCE);
                return paths;
            }

            LOGGER.debug("Located known resource at: {}", resourceUrl);
            String protocol = resourceUrl.getProtocol();

            if ("jar".equals(protocol)) {
                // URL format: jar:file:/path/to/storm.jar!/lua/shared/storm/StormShared.lua
                String jarUrlStr = resourceUrl.getPath();
                String jarFilePath = jarUrlStr.substring(0, jarUrlStr.indexOf('!'));
                Path jarPath = Paths.get(URI.create(jarFilePath));
                try (JarFile jar = new JarFile(jarPath.toFile())) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith("lua/")
                                && !entry.isDirectory()
                                && name.endsWith(".lua")) {
                            paths.add(name);
                        }
                    }
                }
            } else if ("file".equals(protocol)) {
                // Running from exploded classes directory (e.g. during development).
                // Walk up from the known resource to find the lua/ root.
                Path knownPath = Paths.get(resourceUrl.toURI());
                // knownPath is .../classes/lua/shared/storm/StormShared.lua
                // We need to go up to the lua/ directory (3 levels from the known resource)
                Path luaRoot = knownPath.getParent().getParent().getParent();
                if (Files.isDirectory(luaRoot)) {
                    try (var stream = Files.walk(luaRoot)) {
                        stream.filter(p -> p.toString().endsWith(".lua") && Files.isRegularFile(p))
                                .forEach(
                                        p -> {
                                            String relative = luaRoot.relativize(p).toString();
                                            paths.add("lua/" + relative.replace('\\', '/'));
                                        });
                    }
                }
            } else {
                LOGGER.warn("Unsupported URL protocol for lua resources: {}", protocol);
            }

            Collections.sort(paths);
            LOGGER.debug("Discovered {} Storm lua resources: {}", paths.size(), paths);
        } catch (IOException | URISyntaxException e) {
            LOGGER.error("Failed to discover lua resources", e);
        }
        return paths;
    }

    @SubscribeEvent
    public static void onLoadMods(OnLoadModsEvent event) {
        ActiveMods.onLoadMods(event);
    }
}
