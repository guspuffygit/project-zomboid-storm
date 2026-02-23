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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.network.GameClient;
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
                new LuaPatchUtils().doesLuaFunctionExist("SpawnRegionMgr", "loadSpawnPointsFile");
        if (!result) {
            throw new RuntimeException("Lua was not loaded and did not exist");
        }

        try {
            LuaManager.exposer.setExposed(PersistedBooleanConfigOption.class);

            LuaPatchUtils luaPatchUtils = new LuaPatchUtils();
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

                            luaPatchUtils.injectLuaCode(luaCode, name);
                        } catch (IOException e) {
                            LOGGER.error("Failed to read entry: {}", name, e);
                        }
                    }
                }
            }

            // TODO: Load these automatically
            if (GameClient.client) {
                loadFromResourcePath("/lua/client/ISUI/Maps/ISWorldMapPatch.lua");
            }
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
            new LuaPatchUtils().injectLuaCode(luaCode, path);
        } catch (Exception e) {
            LOGGER.error("Unable to load path: {}", path, e);
            throw new RuntimeException(e);
        }
    }

    @SubscribeEvent
    public static void onLoadMods(OnLoadModsEvent event) {
        ActiveMods.onLoadMods(event);
    }
}
