package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.util.StormEnv;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import zombie.GameWindow;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaHookManager;
import zombie.Lua.LuaManager;
import zombie.Lua.MapObjects;
import zombie.SandboxOptions;
import zombie.ZomboidFileSystem;
import zombie.ZomboidGlobals;
import zombie.characters.AttachedItems.AttachedLocations;
import zombie.characters.SurvivorFactory;
import zombie.characters.WornItems.BodyLocations;
import zombie.characters.animals.AnimalDefinitions;
import zombie.characters.animals.AnimalZones;
import zombie.characters.animals.MigrationGroupDefinitions;
import zombie.characters.skills.CustomPerks;
import zombie.characters.skills.PerkFactory;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.RenderThread;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.textures.NinePatchTexture;
import zombie.core.textures.Texture;
import zombie.gameStates.ChooseGameInfo;
import zombie.input.GameKeyboard;
import zombie.input.JoypadManager;
import zombie.iso.BentFences;
import zombie.iso.BrokenFences;
import zombie.iso.ContainerOverlays;
import zombie.iso.TileOverlays;
import zombie.network.GameClient;
import zombie.sandbox.CustomSandboxOptions;
import zombie.scripting.ScriptManager;
import zombie.seams.SeamManager;
import zombie.seating.SeatingManager;
import zombie.tileDepth.TileDepthTextureAssignmentManager;
import zombie.tileDepth.TileDepthTextureManager;
import zombie.tileDepth.TileGeometryManager;
import zombie.ui.UIManager;
import zombie.vehicles.VehicleType;
import zombie.worldMap.WorldMap;

/**
 * Connect-time fast path for {@code Core.ResetLua("client", "ConnectedToServer")} — the ~25s step
 * between clicking a server and entering its loading queue.
 *
 * <p>Vanilla must rerun everything at connect because mods branch on {@code isClient()} at Lua file
 * scope and the boot pass ran with {@code client=false}. But when {@link StormJoinPrewarm} already
 * booted this JVM with the target server's exact mod set, only the Lua-side work is stale: every
 * flag-independent Java load (script parse, models, animations, sounds, translations) produced
 * identical state at boot. The lite pass below re-runs precisely the subset of vanilla's body that
 * pairs with the fresh Lua VM, and skips the rest.
 *
 * <p>Kept (vanilla order): the reload render bracket, every registry that Lua repopulates
 * (VehicleType, SurvivorFactory, body/attach locations, overlays, fences, perks, sandbox options,
 * world map, animal definitions/zones/migrations), the Lua VM itself ({@code LuaManager.init} +
 * {@code LoadDirBase}), UI/input resets, the tile geometry/depth/seam/seating manager pairs, {@code
 * AdvancedAnimator.load()} (checksum-only — its whole body is gated on client/server), the Zomboid
 * globals, and the boot/menu/reset Lua events.
 *
 * <p>Skipped, with the pairing that makes each safe: {@code RegistryReset.resetAll} + {@code
 * ModRegistries.init} + {@code ScriptManager.Reset/Load} (the script parse is the cost center;
 * registries are repopulated by it, and {@code Registry.register} throws on duplicates, which also
 * forces the registries.lua veto below); {@code GameSounds.Reset} (rebuilt by the script parse);
 * {@code AnimationSet.Reset} + animation mesh/mod-animation loads; {@code SpriteModelManager}
 * reset/init; {@code ZomboidFileSystem} reset/init/loadMods/loadModPackFiles (the precondition is
 * that the mod set is unchanged); languages/translator; decals/beards/hair/outfits/voices; {@code
 * TextManager.Init}.
 *
 * <p>Checksums: the Lua and animation totals are computed live, exactly as vanilla computes them;
 * the script total — which only falls out of the skipped parse — is installed from {@link
 * StormJoinChecksumCache} and must equal the server-published total before the fast path is even
 * elected. A wrong guess can therefore only reproduce the same checksum-kick the vanilla path would
 * have produced.
 *
 * <p>Failure is soft everywhere: any election miss or throwable mid-lite returns {@code false} and
 * vanilla's full ResetLua runs — that body resets everything from any state, so a half-finished
 * lite pass is simply discarded.
 */
public final class StormFastResetLua {

    /** Any ResetLua call consumes the pristine boot state, whether or not it was fast-pathed. */
    private static boolean resetLuaSeen;

    private StormFastResetLua() {}

    /**
     * Called from Storm's advice on {@code Core.ResetLua(String, String)}. Returns {@code true}
     * when the lite pass ran and vanilla's body must be skipped. Never throws.
     */
    public static boolean tryFastPath(String activeMods, String reason) {
        boolean pristine = !resetLuaSeen;
        resetLuaSeen = true;
        try {
            if (!elect(pristine, activeMods, reason)) {
                return false;
            }
            String cachedScriptChecksum =
                    StormJoinChecksumCache.readScriptChecksum(StormJoinPrewarm.fingerprint());
            long start = System.nanoTime();
            liteResetLua(reason, cachedScriptChecksum);
            LOGGER.info(
                    "Fast ResetLua completed in {} ms (skipped the full script/model reload)",
                    (System.nanoTime() - start) / 1_000_000L);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Fast ResetLua failed — falling back to vanilla ResetLua", t);
            return false;
        }
    }

    private static boolean elect(boolean pristine, String activeMods, String reason) {
        if (StormEnv.isStormServer()) {
            return false;
        }
        List<String> bootMods = StormJoinPrewarm.bootModList();
        if (bootMods == null) {
            return false;
        }
        if (!pristine) {
            // a previous ResetLua (menu return after a disconnect, SP game, mods screen) already
            // destroyed the boot-substituted state this pass relies on
            LOGGER.debug("Fast ResetLua: not the first ResetLua of the process");
            return false;
        }
        if (!"client".equalsIgnoreCase(activeMods) || !"ConnectedToServer".equals(reason)) {
            return false;
        }
        if (!GameClient.client || GameClient.instance == null) {
            return false;
        }
        if (!bootMods.equals(GameClient.instance.serverMods)) {
            LOGGER.info(
                    "Fast ResetLua: server mod list changed since the pre-launch query — using"
                            + " the vanilla reload");
            return false;
        }
        if (StormServerModDirs.bootResolutionCorrected()) {
            // a duplicate mod id made the boot walk pick a non-server copy; the boot-loaded
            // content is from the wrong folder even though the mod-id lists match
            LOGGER.info(
                    "Fast ResetLua: a server mod resolved to a different folder at boot — using"
                            + " the vanilla reload with the corrected mod dirs");
            return false;
        }
        String serverScriptChecksum = StormJoinPrewarm.serverScriptChecksum();
        String fingerprint = StormJoinPrewarm.fingerprint();
        if (serverScriptChecksum == null || fingerprint == null) {
            return false;
        }
        String cached = StormJoinChecksumCache.readScriptChecksum(fingerprint);
        if (cached == null || !cached.equals(serverScriptChecksum)) {
            LOGGER.info(
                    "Fast ResetLua: no matching script-checksum cache (first join since a content"
                            + " change) — using the vanilla reload and recording the result");
            return false;
        }
        if (anyModShipsRegistriesLua()) {
            LOGGER.info(
                    "Fast ResetLua: a mod ships media/registries.lua — using the vanilla reload"
                            + " (the fast path skips ModRegistries.init)");
            return false;
        }
        return true;
    }

    /** Mirrors {@code ModRegistries.init}'s own lookup, without running anything. */
    private static boolean anyModShipsRegistriesLua() {
        for (String modId : ZomboidFileSystem.instance.getModIDs()) {
            ChooseGameInfo.Mod mod = ChooseGameInfo.getAvailableModDetails(modId);
            if (mod == null) {
                continue;
            }
            if (new File(mod.getVersionDir() + "/media/registries.lua").isFile()
                    || new File(mod.getCommonDir() + "/media/registries.lua").isFile()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The kept subset of vanilla {@code Core.ResetLua}, in vanilla's order. Unlike vanilla, the
     * {@code AdvancedAnimator.load()}/{@code LoadDirBase()} block does not swallow exceptions —
     * anything thrown here propagates to {@link #tryFastPath} and the vanilla body runs instead.
     */
    private static void liteResetLua(String reason, String cachedScriptChecksum) throws Exception {
        if (SpriteRenderer.instance != null) {
            GameWindow.drawReloadingLua = true;
            GameWindow.render();
            GameWindow.drawReloadingLua = false;
        }
        RenderThread.setWaitForRenderState(false);
        SpriteRenderer.instance.notifyRenderStateQueue();
        VehicleType.Reset();
        LuaEventManager.Reset();
        MapObjects.Reset();
        UIManager.init();
        SurvivorFactory.Reset();
        ChooseGameInfo.Reset();
        AttachedLocations.Reset();
        BodyLocations.reset();
        ContainerOverlays.instance.Reset();
        BentFences.getInstance().Reset();
        BrokenFences.getInstance().Reset();
        TileOverlays.instance.Reset();
        LuaHookManager.Reset();
        CustomPerks.Reset();
        PerkFactory.Reset();
        CustomSandboxOptions.Reset();
        SandboxOptions.Reset();
        WorldMap.Reset();
        AnimalDefinitions.Reset();
        AnimalZones.Reset();
        MigrationGroupDefinitions.Reset();
        LuaManager.init();
        JoypadManager.instance.Reset();
        GameKeyboard.doLuaKeyPressed = true;
        Texture.nullTextures.clear();
        NinePatchTexture.Reset();
        TileGeometryManager.getInstance().Reset();
        TileDepthTextureManager.getInstance().Reset();
        SeamManager.getInstance().Reset();
        SeatingManager.getInstance().Reset();
        CustomPerks.instance.init();
        CustomPerks.instance.initLua();
        CustomSandboxOptions.instance.init();
        CustomSandboxOptions.instance.initInstance(SandboxOptions.instance);
        TileGeometryManager.getInstance().init();
        TileDepthTextureAssignmentManager.getInstance().init();
        TileDepthTextureManager.getInstance().init();
        SeamManager.getInstance().init();
        SeatingManager.getInstance().init();
        AdvancedAnimator.load();
        LuaManager.LoadDirBase();
        installScriptChecksum(cachedScriptChecksum);
        ZomboidGlobals.Load();
        RenderThread.setWaitForRenderState(true);
        LuaEventManager.triggerEvent("OnGameBoot");
        LuaEventManager.triggerEvent("OnMainMenuEnter");
        LuaEventManager.triggerEvent("OnResetLua", reason);
    }

    /**
     * The script total normally falls out of {@code ScriptManager.Load()}'s checksum feeders; the
     * lite pass skips that parse, so the cached (and server-matched) total is installed directly.
     */
    private static void installScriptChecksum(String checksum) throws Exception {
        Field field = ScriptManager.class.getDeclaredField("checksum");
        field.setAccessible(true);
        field.set(ScriptManager.instance, checksum);
    }

    // test hook: election state must reset between tests
    static void resetForTests() {
        resetLuaSeen = false;
    }
}
