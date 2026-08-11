package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.PacketEventDispatcher;
import io.pzstorm.storm.mod.ZomboidMod;
import io.pzstorm.storm.patch.client.VehicleModelAttachRetryPatch;
import io.pzstorm.storm.patch.client.VehicleRequestMergeFlagsPatch;
import io.pzstorm.storm.patch.client.experimental.KahluaMetatableCachePatch;
import io.pzstorm.storm.patch.client.experimental.VehicleModDataRequestPatch;
import io.pzstorm.storm.patch.core.CommandBasePatch;
import io.pzstorm.storm.patch.core.ZomboidFileSystemPatch;
import io.pzstorm.storm.patch.core.ZomboidGlobalsPatch;
import io.pzstorm.storm.patch.debugging.DebugLogPatch;
import io.pzstorm.storm.patch.debugging.ThreadPatch;
import io.pzstorm.storm.patch.events.ChatManagerPatch;
import io.pzstorm.storm.patch.events.ChatServerSendMessagePatch;
import io.pzstorm.storm.patch.events.LuaEventManagerPatch;
import io.pzstorm.storm.patch.events.OnDeathTriggerPatch;
import io.pzstorm.storm.patch.fixes.ActionManagerPatch;
import io.pzstorm.storm.patch.fixes.ActionStateContainerPatch;
import io.pzstorm.storm.patch.fixes.BaseVehicleSavePatch;
import io.pzstorm.storm.patch.fixes.BodyDamageSyncPatch;
import io.pzstorm.storm.patch.fixes.BodyDamageUpdatePacketPatch;
import io.pzstorm.storm.patch.fixes.ChatServerProcessWhisperPatch;
import io.pzstorm.storm.patch.fixes.CompressIdenticalItemsPatch;
import io.pzstorm.storm.patch.fixes.GeneralActionPacketPatch;
import io.pzstorm.storm.patch.fixes.IsoAnimalCanClimbStairsNullDefGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoAnimalReattachBackToMomPatch;
import io.pzstorm.storm.patch.fixes.IsoAnimalUpdateNullDefGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoGridSquareGetRoomNullDefGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoObjectIDAllocateFixPatch;
import io.pzstorm.storm.patch.fixes.IsoZombieUpdateFixPatch;
import io.pzstorm.storm.patch.fixes.ItemTransactionPacketPatch;
import io.pzstorm.storm.patch.fixes.NetTimedActionPacketPatch;
import io.pzstorm.storm.patch.fixes.RequestSaveCellSuppressPatch;
import io.pzstorm.storm.patch.fixes.SpriteConfigFixPatch;
import io.pzstorm.storm.patch.fixes.TransactionManagerPatch;
import io.pzstorm.storm.patch.fixes.TranslatorPatch;
import io.pzstorm.storm.patch.lua.LuaExposerDumpPatch;
import io.pzstorm.storm.patch.lua.LuaManagerPatch;
import io.pzstorm.storm.patch.networking.ConnectionManagerLogPatch;
import io.pzstorm.storm.patch.networking.CoopMasterPatch;
import io.pzstorm.storm.patch.networking.GameEntityBroadcastGatePatch;
import io.pzstorm.storm.patch.networking.GameServerConnectionCapPatch;
import io.pzstorm.storm.patch.networking.GameServerLockFpsPatch;
import io.pzstorm.storm.patch.networking.GameServerStalledConnectionReapPatch;
import io.pzstorm.storm.patch.networking.GameServerTickRatePatch;
import io.pzstorm.storm.patch.networking.GameServerWorkshopItemsPatch;
import io.pzstorm.storm.patch.networking.IsoBarricadeSyncGatePatch;
import io.pzstorm.storm.patch.networking.IsoLightSwitchSyncGatePatch;
import io.pzstorm.storm.patch.networking.IsoObjectSyncGatePatch;
import io.pzstorm.storm.patch.networking.IsoWorldInventoryObjectSyncGatePatch;
import io.pzstorm.storm.patch.networking.PacketReceivedPatch;
import io.pzstorm.storm.patch.networking.PlayerDownloadServerChunkActivityPatch;
import io.pzstorm.storm.patch.networking.ReceiveSandboxOptionsPatch;
import io.pzstorm.storm.patch.networking.ServerQueryPatch;
import io.pzstorm.storm.patch.networking.ServerWorldDatabasePatch;
import io.pzstorm.storm.patch.networking.SteamGameServerPlayerListPatch;
import io.pzstorm.storm.patch.networking.UdpConnectionRelevancePatch;
import io.pzstorm.storm.patch.performance.AnimalControllerUpdatePatch;
import io.pzstorm.storm.patch.performance.AnimalPopManRemoveChunkPatch;
import io.pzstorm.storm.patch.performance.AnimalPopManSavePatch;
import io.pzstorm.storm.patch.performance.AnimalSyncManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.AnimalZonesUpdateVirtualAnimalsPatch;
import io.pzstorm.storm.patch.performance.AnimationPlayerRecorderIsActivePatch;
import io.pzstorm.storm.patch.performance.AnimationVariableReferenceGetVariablePatch;
import io.pzstorm.storm.patch.performance.BaseVehicleUpdatePatch;
import io.pzstorm.storm.patch.performance.BitHeaderByteReleasePatch;
import io.pzstorm.storm.patch.performance.BitHeaderGetHeaderPatch;
import io.pzstorm.storm.patch.performance.BitHeaderIntReleasePatch;
import io.pzstorm.storm.patch.performance.BitHeaderLongReleasePatch;
import io.pzstorm.storm.patch.performance.BitHeaderShortReleasePatch;
import io.pzstorm.storm.patch.performance.CalcCountPlayersInRelevantPositionPatch;
import io.pzstorm.storm.patch.performance.ChunkChecksumMetricsPatch;
import io.pzstorm.storm.patch.performance.ChunkStreamWorkerMetricsPatch;
import io.pzstorm.storm.patch.performance.ClientChunkRequestRetryPatch;
import io.pzstorm.storm.patch.performance.ClientServerMapCharacterInPatch;
import io.pzstorm.storm.patch.performance.ClimateManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.CollisionManagerInitUpdatePatch;
import io.pzstorm.storm.patch.performance.CollisionManagerResolveContactsPatch;
import io.pzstorm.storm.patch.performance.CoopSlaveUpdatePatch;
import io.pzstorm.storm.patch.performance.CutawayLevelDataArrayCachePatch;
import io.pzstorm.storm.patch.performance.EcsComponentGetClassMemoPatch;
import io.pzstorm.storm.patch.performance.EcsGetClassCachePatch;
import io.pzstorm.storm.patch.performance.EngineEntityManagerIndexPatch;
import io.pzstorm.storm.patch.performance.EngineUpdatePatch;
import io.pzstorm.storm.patch.performance.EngineUpdateSimulationPatch;
import io.pzstorm.storm.patch.performance.EntityArrayRemoveFastPathPatch;
import io.pzstorm.storm.patch.performance.EntitySimulationUpdatePatch;
import io.pzstorm.storm.patch.performance.ErosionMainLoadGridsquarePatch;
import io.pzstorm.storm.patch.performance.EventTriggerFastPathPatch;
import io.pzstorm.storm.patch.performance.FBORenderLevelsFreeSkipPatch;
import io.pzstorm.storm.patch.performance.FileSystemUpdateAsyncTransactionsPatch;
import io.pzstorm.storm.patch.performance.FishSchoolManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.FluidContainerUpdateSimulationFastPathPatch;
import io.pzstorm.storm.patch.performance.GameEntityManagerSavePatch;
import io.pzstorm.storm.patch.performance.GameEntityManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.GameEntityUsingPlayerTrackingPatch;
import io.pzstorm.storm.patch.performance.GameServerNetDataPatch;
import io.pzstorm.storm.patch.performance.GlobalModDataSavePatch;
import io.pzstorm.storm.patch.performance.ImportantAreaManagerProcessPatch;
import io.pzstorm.storm.patch.performance.IngameStateUpdatePatch;
import io.pzstorm.storm.patch.performance.InventoryItemSweepStridePatch;
import io.pzstorm.storm.patch.performance.IsoAnimalUpdateLOSPatch;
import io.pzstorm.storm.patch.performance.IsoAnimalUpdateTimingPatch;
import io.pzstorm.storm.patch.performance.IsoCellObjectDeletionAdditionPatch;
import io.pzstorm.storm.patch.performance.IsoCellProcessIsoObjectPatch;
import io.pzstorm.storm.patch.performance.IsoCellProcessItemsPatch;
import io.pzstorm.storm.patch.performance.IsoCellProcessObjectsPatch;
import io.pzstorm.storm.patch.performance.IsoCellProcessSpottedRoomsPatch;
import io.pzstorm.storm.patch.performance.IsoCellProcessStaticUpdatersPatch;
import io.pzstorm.storm.patch.performance.IsoCellUpdatePatch;
import io.pzstorm.storm.patch.performance.IsoChunkAddBloodPatch;
import io.pzstorm.storm.patch.performance.IsoChunkAddCorpsesPatch;
import io.pzstorm.storm.patch.performance.IsoChunkAddRanchAnimalsPatch;
import io.pzstorm.storm.patch.performance.IsoChunkAddVehiclesPatch;
import io.pzstorm.storm.patch.performance.IsoChunkAddZombieZoneStoryPatch;
import io.pzstorm.storm.patch.performance.IsoChunkCheckGrassRegrowthPatch;
import io.pzstorm.storm.patch.performance.IsoChunkLoadPatch;
import io.pzstorm.storm.patch.performance.IsoChunkRemoveFromWorldPatch;
import io.pzstorm.storm.patch.performance.IsoChunkSafeReadPatch;
import io.pzstorm.storm.patch.performance.IsoChunkSaveLoadedChunkPatch;
import io.pzstorm.storm.patch.performance.IsoChunkSavePatch;
import io.pzstorm.storm.patch.performance.IsoDeadBodyUpdateBodiesPatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterIsRagdollPatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterRagdollMirrorsPatch;
import io.pzstorm.storm.patch.performance.IsoGeneratorElectricityPatch;
import io.pzstorm.storm.patch.performance.IsoGridSquareLosParallelPatch;
import io.pzstorm.storm.patch.performance.IsoLightSwitchElectricityMemoPatch;
import io.pzstorm.storm.patch.performance.IsoObjectRemoveFromWorldPatch;
import io.pzstorm.storm.patch.performance.IsoPhysicsObjectFpsPatch;
import io.pzstorm.storm.patch.performance.IsoPlayerUpdateLOSFastPathPatch;
import io.pzstorm.storm.patch.performance.IsoPlayerUpdateLOSPatch;
import io.pzstorm.storm.patch.performance.IsoPlayerUpdateRemotePatch;
import io.pzstorm.storm.patch.performance.IsoRegionsUpdatePatch;
import io.pzstorm.storm.patch.performance.IsoRoomOnSeePatch;
import io.pzstorm.storm.patch.performance.IsoWaterFlowMemoPatch;
import io.pzstorm.storm.patch.performance.IsoWorldUpdateBuildingsPatch;
import io.pzstorm.storm.patch.performance.IsoWorldUpdateDBsPatch;
import io.pzstorm.storm.patch.performance.IsoWorldUpdatePatch;
import io.pzstorm.storm.patch.performance.JniLightingCleanSquarePatch;
import io.pzstorm.storm.patch.performance.KahluaTableRawgetPatch;
import io.pzstorm.storm.patch.performance.LightingPreUpdateForkPatch;
import io.pzstorm.storm.patch.performance.LoadedAreasAddPatch;
import io.pzstorm.storm.patch.performance.LoginQueueUpdatePatch;
import io.pzstorm.storm.patch.performance.LuaMainloopPatch;
import io.pzstorm.storm.patch.performance.MainLoopDrainCapPatch;
import io.pzstorm.storm.patch.performance.MapCollisionDataRemoveChunkPatch;
import io.pzstorm.storm.patch.performance.MapCollisionDataSavePatch;
import io.pzstorm.storm.patch.performance.MapCollisionDataUpdateGameStatePatch;
import io.pzstorm.storm.patch.performance.MovingObjectSchedulerBucketAddPatch;
import io.pzstorm.storm.patch.performance.MovingObjectSchedulerPostupdatePatch;
import io.pzstorm.storm.patch.performance.NetworkPlayerManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.NetworkZombieManagerAuthPatch;
import io.pzstorm.storm.patch.performance.NetworkZombiePackerPostUpdatePatch;
import io.pzstorm.storm.patch.performance.ObjectIDManagerCheckSaveDataPatch;
import io.pzstorm.storm.patch.performance.ObjectRenderEffectsUpdateStaticPatch;
import io.pzstorm.storm.patch.performance.PacketLimitMetricsPatch;
import io.pzstorm.storm.patch.performance.PacketValidatorUpdatePatch;
import io.pzstorm.storm.patch.performance.PacketsCacheLimitBypassPatch;
import io.pzstorm.storm.patch.performance.PathfindNativeRemoveChunkPatch;
import io.pzstorm.storm.patch.performance.PlayerDownloadServerRemoveOlderPatch;
import io.pzstorm.storm.patch.performance.PlayerDownloadServerUpdatePatch;
import io.pzstorm.storm.patch.performance.PolygonalMap2RemoveChunkPatch;
import io.pzstorm.storm.patch.performance.PropertyContainerHasIdCachePatch;
import io.pzstorm.storm.patch.performance.PublicServerUtilUpdatePatch;
import io.pzstorm.storm.patch.performance.PublicServerUtilUpdatePlayerCountPatch;
import io.pzstorm.storm.patch.performance.RCONServerUpdatePatch;
import io.pzstorm.storm.patch.performance.RandAdjustForFrameratePatch;
import io.pzstorm.storm.patch.performance.RemoveAnimalsPatch;
import io.pzstorm.storm.patch.performance.RemoveDeadBodiesPatch;
import io.pzstorm.storm.patch.performance.RemoveVehiclesPatch;
import io.pzstorm.storm.patch.performance.RemoveZombiesPatch;
import io.pzstorm.storm.patch.performance.RequestZipListParsePatch;
import io.pzstorm.storm.patch.performance.SafeHouseUpdatePatch;
import io.pzstorm.storm.patch.performance.SendWorldMapPlayerPositionPatch;
import io.pzstorm.storm.patch.performance.ServerCellLoad2Patch;
import io.pzstorm.storm.patch.performance.ServerCellRecalcAll2Patch;
import io.pzstorm.storm.patch.performance.ServerCellUnloadPatch;
import io.pzstorm.storm.patch.performance.ServerCellUpdatePatch;
import io.pzstorm.storm.patch.performance.ServerChunkLoaderUpdateSavedPatch;
import io.pzstorm.storm.patch.performance.ServerGUIUpdatePatch;
import io.pzstorm.storm.patch.performance.ServerLOSFindDataPatch;
import io.pzstorm.storm.patch.performance.ServerLOSIsCouldSeePatch;
import io.pzstorm.storm.patch.performance.ServerLOSRemovePlayerPatch;
import io.pzstorm.storm.patch.performance.ServerLOSRunInnerPatch;
import io.pzstorm.storm.patch.performance.ServerLOSUpdatePatch;
import io.pzstorm.storm.patch.performance.ServerMapCharacterInPatch;
import io.pzstorm.storm.patch.performance.ServerMapPostUpdateBudgetPatch;
import io.pzstorm.storm.patch.performance.ServerMapPostUpdatePatch;
import io.pzstorm.storm.patch.performance.ServerMapPostUpdateWarmPatch;
import io.pzstorm.storm.patch.performance.ServerMapPreUpdatePatch;
import io.pzstorm.storm.patch.performance.ServerMapQueuedSaveAllPatch;
import io.pzstorm.storm.patch.performance.ServerMapSaveAllPatch;
import io.pzstorm.storm.patch.performance.ServerPlayerDBSavePatch;
import io.pzstorm.storm.patch.performance.ServerTickPatch;
import io.pzstorm.storm.patch.performance.StatisticManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.StatsGetPatch;
import io.pzstorm.storm.patch.performance.SteamUtilsRunLoopPatch;
import io.pzstorm.storm.patch.performance.TestZombieSpotPlayerPatch;
import io.pzstorm.storm.patch.performance.TradingManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.TryAddIndoorZombiesPatch;
import io.pzstorm.storm.patch.performance.UsingPlayerSweepFastPathPatch;
import io.pzstorm.storm.patch.performance.UsingPlayerUpdatePatch;
import io.pzstorm.storm.patch.performance.VehicleManagerSendVehiclesPatch;
import io.pzstorm.storm.patch.performance.VehicleManagerServerUpdatePatch;
import io.pzstorm.storm.patch.performance.VirtualAnimalStridePatch;
import io.pzstorm.storm.patch.performance.WarManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.WeatherFxScanSkipPatch;
import io.pzstorm.storm.patch.performance.WorldMapServerWriteSavefilePatch;
import io.pzstorm.storm.patch.performance.WorldMapVisitedServerUpdatePatch;
import io.pzstorm.storm.patch.performance.WorldSimulationUpdatePatch;
import io.pzstorm.storm.patch.performance.ZipBackupOnPeriodPatch;
import io.pzstorm.storm.patch.performance.ZombieAuthStridePatch;
import io.pzstorm.storm.patch.performance.ZombieGroupManagerPreupdatePatch;
import io.pzstorm.storm.patch.performance.ZombiePopManRemoveChunkPatch;
import io.pzstorm.storm.patch.performance.ZombieVehicleOcclusionPatch;
import io.pzstorm.storm.patch.performance.ZomboidRadioSavePatch;
import io.pzstorm.storm.patch.performance.ZomboidRadioUpdatePatch;
import io.pzstorm.storm.patch.rendering.EpilepsyWarningSkipPatch;
import io.pzstorm.storm.patch.rendering.GameLoadingClickToStartSkipPatch;
import io.pzstorm.storm.patch.rendering.MainScreenStatePatch;
import io.pzstorm.storm.patch.rendering.TISLogoStatePatch;
import io.pzstorm.storm.patch.rendering.TISLogoStateSkipPatch;
import io.pzstorm.storm.patch.rendering.TermsOfServiceStateSkipPatch;
import io.pzstorm.storm.patch.rendering.UIWorldMapPatch;
import io.pzstorm.storm.patch.rendering.UIWorldMapV1Patch;
import io.pzstorm.storm.util.StormEnv;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.jetbrains.annotations.Contract;

/**
 * This class defines, initializes and stores {@link StormClassTransformer} instances. To retrieve a
 * mapped instance of registered transformer call {@link #getRegistered(String)}.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class StormClassTransformers {

    /**
     * Internal registry of created transformers. This map is checked for entries by {@link
     * StormClassLoader} when loading classes and invokes the transformation chain of methods to
     * transform the class before defining it via JVM.
     */
    private static final Map<String, List<StormClassTransformer>> TRANSFORMERS = new HashMap<>();

    static {
        registerTransformer(new MainScreenStatePatch());
        registerTransformer(new TISLogoStatePatch());
        // Launcher "Skip menus" toggle (default on): straight to the main menu. Keep the
        // property name in sync with io.pzstorm.launcher.GameLaunch#SKIP_MENUS_PROPERTY.
        if (Boolean.getBoolean("storm.skipmenus")) {
            registerTransformer(new EpilepsyWarningSkipPatch());
            registerTransformer(new TISLogoStateSkipPatch());
            registerTransformer(new TermsOfServiceStateSkipPatch());
        }
        // Dev/testing-only (never passed by the launcher): auto-press the pre-spawn
        // click-to-start screen. Normal players keep the vanilla click.
        if (Boolean.getBoolean("storm.skipclickstart")) {
            registerTransformer(new GameLoadingClickToStartSkipPatch());
        }
        registerTransformer(new LuaEventManagerPatch());
        registerTransformer(new LuaManagerPatch());
        registerTransformer(new LuaExposerDumpPatch());
        registerTransformer(new ZomboidGlobalsPatch());
        registerTransformer(new UIWorldMapPatch());
        registerTransformer(new ChatManagerPatch());
        registerTransformer(new UIWorldMapV1Patch());
        registerTransformer(new DebugLogPatch());
        registerTransformer(new ZomboidFileSystemPatch());
        registerTransformer(new CommandBasePatch());
        registerTransformer(new ThreadPatch());
        registerTransformer(new SpriteConfigFixPatch());
        registerTransformer(new TranslatorPatch());
        registerTransformer(new CoopMasterPatch());
        registerTransformer(new ServerWorldDatabasePatch());
        registerTransformer(new NetTimedActionPacketPatch());
        registerTransformer(new ActionManagerPatch());
        registerTransformer(new GeneralActionPacketPatch());
        registerTransformer(new ActionStateContainerPatch());
        registerTransformer(new ItemTransactionPacketPatch());
        registerTransformer(new TransactionManagerPatch());
        registerTransformer(new CompressIdenticalItemsPatch());
        registerTransformer(new IsoAnimalReattachBackToMomPatch());
        registerTransformer(new IsoAnimalUpdateNullDefGuardPatch());
        registerTransformer(new IsoAnimalCanClimbStairsNullDefGuardPatch());
        registerTransformer(new IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch());
        // Squares can keep a stale reference to an IsoRoom gutted (def = null) by the
        // player-built-room rebuild; consumers without vanilla's hasRoomDef()-style check NPE
        // (seen live: FirearmRoomSize FMOD parameter -> IngameState.updateInternal kick-to-menu).
        // Guarding getRoom() routes all of them onto their vanilla no-room fallbacks.
        registerTransformer(new IsoGridSquareGetRoomNullDefGuardPatch());
        registerTransformer(new BaseVehicleSavePatch());
        // EXPERIMENTAL client-side perf patches. Deliberate, user-approved exception to the
        // no-client-patches HARD RULE, strictly opt-in via -Dstorm.experimental.clientperf=true:
        // default launches (no flag) keep vanilla bytecode on the client. Do not add entries here
        // without the same explicit approval, and do not cite this block as precedent.
        if (Boolean.getBoolean("storm.experimental.clientperf")) {
            registerTransformer(new EcsComponentGetClassMemoPatch());
            registerTransformer(new IsoLightSwitchElectricityMemoPatch());
            registerTransformer(new JniLightingCleanSquarePatch());
            registerTransformer(new IsoWaterFlowMemoPatch());
            registerTransformer(new PropertyContainerHasIdCachePatch());
            registerTransformer(new WeatherFxScanSkipPatch());
            registerTransformer(new LightingPreUpdateForkPatch());
            registerTransformer(new AnimationVariableReferenceGetVariablePatch());
            registerTransformer(new IsoGameCharacterIsRagdollPatch());
            registerTransformer(new IsoGameCharacterRagdollMirrorsPatch());
            registerTransformer(new AnimationPlayerRecorderIsActivePatch());
            registerTransformer(new KahluaTableRawgetPatch());
            registerTransformer(new EventTriggerFastPathPatch());
            registerTransformer(new CutawayLevelDataArrayCachePatch());
            registerTransformer(new FBORenderLevelsFreeSkipPatch());
        }
        // Server-only: IsoGenerator also executes on the client JVM (HARD RULE). The advice
        // gates on GameServer.server, so gating registration is behavior-preserving.
        if (StormEnv.isStormServer()) {
            registerTransformer(new IsoGeneratorElectricityPatch());
            registerTransformer(new IsoAnimalUpdateTimingPatch());
            registerTransformer(new IsoChunkRemoveFromWorldPatch());
            registerTransformer(new IsoObjectRemoveFromWorldPatch());
            registerTransformer(new OnDeathTriggerPatch("zombie.characters.IsoGameCharacter"));
            registerTransformer(new OnDeathTriggerPatch("zombie.characters.animals.IsoAnimal"));
        }
        registerTransformer(new ServerCellUnloadPatch());
        registerTransformer(new ServerLOSUpdatePatch());
        registerTransformer(new ServerLOSIsCouldSeePatch());
        // Server-only: Stats executes on the client JVM (HARD RULE). Unlike most perf advice,
        // StatsGetAdvice replaces the method body unconditionally (no GameServer.server gate),
        // so this registration gate is the only thing keeping the client on vanilla bytecode.
        if (StormEnv.isStormServer()) {
            registerTransformer(new StatsGetPatch());
            registerTransformer(new IsoPlayerUpdateRemotePatch());
            // Must precede IsoPlayerUpdateLOSPatch: transformers apply in registration order,
            // so the stopwatch advice registered second wraps the fast-path skip and keeps
            // pz_player_update_los_call_duration_seconds timing both paths.
            registerTransformer(new IsoPlayerUpdateLOSFastPathPatch());
            registerTransformer(new IsoPlayerUpdateLOSPatch());
            registerTransformer(new IsoAnimalUpdateLOSPatch());
            registerTransformer(new TestZombieSpotPlayerPatch());
            registerTransformer(new ZombieVehicleOcclusionPatch());
            registerTransformer(new VehicleManagerServerUpdatePatch());
            registerTransformer(new VehicleManagerSendVehiclesPatch());
            registerTransformer(new BaseVehicleUpdatePatch());
            registerTransformer(new GameServerNetDataPatch());
            registerTransformer(new IsoChunkLoadPatch());
            registerTransformer(new IsoChunkSavePatch());
            registerTransformer(new BitHeaderGetHeaderPatch());
            registerTransformer(new BitHeaderByteReleasePatch());
            registerTransformer(new BitHeaderShortReleasePatch());
            registerTransformer(new BitHeaderIntReleasePatch());
            registerTransformer(new BitHeaderLongReleasePatch());
            // Must precede ServerMapPostUpdatePatch: transformers apply in registration order,
            // so the stopwatch advice registered second wraps the budget skip and keeps
            // pz_server_map_post_update_call_duration_seconds timing both paths. The
            // ServerMapPostUpdateWarmPatch registered much later stays outermost: with cell
            // warming enabled its advice owns the whole postupdate body and the budget helper
            // defers to it via the StormCellWarmingConfig gate.
            registerTransformer(new ServerMapPostUpdateBudgetPatch());
            registerTransformer(new ServerMapPostUpdatePatch());
            registerTransformer(new GameEntityUsingPlayerTrackingPatch());
            // Must precede UsingPlayerUpdatePatch: transformers apply in registration order,
            // so the stopwatch advice registered second wraps the registry-sweep skip and keeps
            // pz_using_player_update_call_duration_seconds timing both paths.
            registerTransformer(new UsingPlayerSweepFastPathPatch());
            registerTransformer(new UsingPlayerUpdatePatch());
            // No stopwatch patch exists on FluidContainerUpdateSystem.updateSimulation (the
            // Engine.updateSimulation timing patch targets a different class), so this fast
            // path has no ordering constraint.
            registerTransformer(new FluidContainerUpdateSimulationFastPathPatch());
            // Server-side ECSComponent.getECSClass memoization — distinct from the
            // EXPERIMENTAL client-side EcsComponentGetClassMemoPatch registered above.
            registerTransformer(new EcsGetClassCachePatch());
            // O(1) removal from the engine's global entity array: the constructor hook tracks
            // each new EngineEntityManager's array, the Array patch maintains/consults the
            // index. No other transformer targets either class, so ordering is unconstrained.
            registerTransformer(new EngineEntityManagerIndexPatch());
            registerTransformer(new EntityArrayRemoveFastPathPatch());
            registerTransformer(new GameEntityManagerUpdatePatch());
            registerTransformer(new NetworkZombieManagerAuthPatch());
            registerTransformer(new AnimalSyncManagerUpdatePatch());
            registerTransformer(new LuaMainloopPatch());
            registerTransformer(new IsoWorldUpdatePatch());
            registerTransformer(new AnimalControllerUpdatePatch());
            registerTransformer(new ZomboidRadioUpdatePatch());
        }
        registerTransformer(new PacketsCacheLimitBypassPatch());
        registerTransformer(new ChatServerProcessWhisperPatch());
        if (StormEnv.isStormServer()) {
            registerTransformer(new ChatServerSendMessagePatch());
        }
        registerTransformer(new KahluaMetatableCachePatch());

        if (StormEnv.isStormServer()) {
            // FindData/RemovePlayer advise different ServerLOS methods than the transformers
            // registered above, so moving them into this gate does not reorder any shared
            // advice chain. Their advices already gate on GameServer.server; the registration
            // gate just makes the server-only intent explicit.
            registerTransformer(new ServerLOSFindDataPatch());
            registerTransformer(new ServerLOSRemovePlayerPatch());
            registerTransformer(new ServerLOSRunInnerPatch());
            registerTransformer(new IsoGridSquareLosParallelPatch());
            registerTransformer(new IsoRoomOnSeePatch());
        }

        // Client-only: reacts to a client-side packet-ordering race by asking the
        // server to resend full vehicle state. The advice already gates on
        // GameClient.client, and the remedy (sendVehicleRequest) is a client->server
        // RPC with no server-side analogue.
        if (!StormEnv.isStormServer()) {
            registerTransformer(new VehicleModDataRequestPatch());
            // Invisible-vehicle recovery pair. MergeFlags stops clientUpdate's 1 Hz Passengers
            // request from clobbering a queued Full request inside the same 100 ms flush window;
            // ModelAttachRetry re-runs ModelManager.addVehicle for vehicles that got physics but
            // no model slot (collide-but-invisible). Both fail soft: MergeFlags degrades to the
            // vanilla put(), ModelAttachRetry disables itself on first error.
            registerTransformer(new VehicleRequestMergeFlagsPatch());
            registerTransformer(new VehicleModelAttachRetryPatch());
            // Client-only because only the client acts on isLimitExceeded — PacketType.send
            // cancels the packet there, while the server's call site logs it and sends anyway.
            registerTransformer(new PacketLimitMetricsPatch());
        }

        if (StormEnv.isStormServer()) {
            registerTransformer(new GameServerTickRatePatch());
            registerTransformer(new GameServerLockFpsPatch());
            registerTransformer(new IsoPhysicsObjectFpsPatch());
            registerTransformer(new RandAdjustForFrameratePatch());
            registerTransformer(new ServerTickPatch());
            registerTransformer(new MainLoopDrainCapPatch());
            registerTransformer(new IsoObjectIDAllocateFixPatch());
            registerTransformer(new RequestSaveCellSuppressPatch());
            registerTransformer(new ReceiveSandboxOptionsPatch());
            registerTransformer(new IsoZombieUpdateFixPatch());

            // First-aid subscription race: the vanilla client sends BodyDamageUpdatePacket with
            // its own online id still -1 during the connect/respawn/reconnect window. The parse
            // patch repairs the id from the connection-resolved player; the sync patch refuses to
            // register updaters for unresolvable ids (vanilla NPE / dead-updater leak).
            registerTransformer(new BodyDamageUpdatePacketPatch());
            registerTransformer(new BodyDamageSyncPatch());

            registerTransformer(new UdpConnectionRelevancePatch());

            // Relevancy gate on the SyncIsoObject broadcast loops (base IsoObject method plus
            // the three reproducible overrides). IsoObjectRemoveFromWorldPatch also transforms
            // zombie.iso.IsoObject but advises removeFromWorld, so ordering between them is
            // unconstrained. The gate leans on UdpConnectionRelevancePatch's hardening of
            // isRelevantTo (false while not fully connected), registered just above.
            registerTransformer(new IsoObjectSyncGatePatch());
            registerTransformer(new IsoWorldInventoryObjectSyncGatePatch());
            registerTransformer(new IsoBarricadeSyncGatePatch());
            registerTransformer(new IsoLightSwitchSyncGatePatch());

            // Relevancy gate on the GameEntity broadcast branch (craft-progress ticks, component
            // dumps, using-player changes — vanilla sendToAll's only filter is isFullyConnected).
            // Same isRelevantTo precedent and fail-soft latch as the SyncIsoObject gates above,
            // and leans on UdpConnectionRelevancePatch's hardening the same way.
            registerTransformer(new GameEntityBroadcastGatePatch());
            registerTransformer(new GameServerWorkshopItemsPatch());
            registerTransformer(new GameServerStalledConnectionReapPatch());
            registerTransformer(new PlayerDownloadServerChunkActivityPatch());
            registerTransformer(new GameServerConnectionCapPatch());
            registerTransformer(new ConnectionManagerLogPatch());
            registerTransformer(new SteamGameServerPlayerListPatch());

            // Answers the Storm Launcher's pre-login workshop/mod query over the game's own
            // UDP port, so players can pre-update mods without the Storm HTTP port being
            // reachable. Server-only: addIncoming exists on the client too, but the client
            // must never serve queries.
            registerTransformer(new ServerQueryPatch());

            // Per-step timing breakdown of GameServer.main(). Each patch wraps one method
            // called from the server's frame-step block and records elapsed nanos into
            // MainLoopStepTimings, which prints a per-tick line when
            // -Dstorm.mainloop.timings=true. ServerMap.preupdate doubles as the tick
            // boundary trigger (its advice calls MainLoopStepTimings.beginTick()).
            registerTransformer(new ServerMapPreUpdatePatch());
            registerTransformer(new ServerCellLoad2Patch());
            registerTransformer(new ServerCellRecalcAll2Patch());
            registerTransformer(new ServerCellUpdatePatch());
            registerTransformer(new NetworkZombiePackerPostUpdatePatch());
            registerTransformer(new ServerChunkLoaderUpdateSavedPatch());
            registerTransformer(new ServerMapQueuedSaveAllPatch());
            registerTransformer(new ServerMapPostUpdateWarmPatch());
            registerTransformer(new MovingObjectSchedulerBucketAddPatch());
            registerTransformer(new LoadedAreasAddPatch());
            registerTransformer(new GameEntityManagerSavePatch());
            registerTransformer(new PlayerDownloadServerRemoveOlderPatch());
            registerTransformer(new IsoChunkSaveLoadedChunkPatch());

            // IsoWorld.update / IsoCell.update internals: closes the steady-state gap
            // inside IsoWorld.update (~10ms uninstrumented body).
            registerTransformer(new IsoCellUpdatePatch());
            registerTransformer(new IsoCellProcessIsoObjectPatch());
            registerTransformer(new IsoCellProcessObjectsPatch());
            registerTransformer(new IsoCellProcessStaticUpdatersPatch());
            registerTransformer(new IsoCellProcessSpottedRoomsPatch());
            registerTransformer(new IsoCellProcessItemsPatch());
            registerTransformer(new IsoCellObjectDeletionAdditionPatch());
            registerTransformer(new IsoDeadBodyUpdateBodiesPatch());
            registerTransformer(new FishSchoolManagerUpdatePatch());
            registerTransformer(new WorldSimulationUpdatePatch());
            registerTransformer(new ZombieGroupManagerPreupdatePatch());
            registerTransformer(new ClimateManagerUpdatePatch());
            registerTransformer(new IsoRegionsUpdatePatch());
            registerTransformer(new CollisionManagerInitUpdatePatch());
            registerTransformer(new CollisionManagerResolveContactsPatch());
            registerTransformer(new MovingObjectSchedulerPostupdatePatch());
            registerTransformer(new IsoWorldUpdateBuildingsPatch());
            registerTransformer(new IsoWorldUpdateDBsPatch());
            registerTransformer(new ObjectRenderEffectsUpdateStaticPatch());
            registerTransformer(new AnimalZonesUpdateVirtualAnimalsPatch());

            // Spike-source internals: chunk load / unload / save fanout.
            registerTransformer(new MapCollisionDataRemoveChunkPatch());
            registerTransformer(new PolygonalMap2RemoveChunkPatch());
            registerTransformer(new PathfindNativeRemoveChunkPatch());
            registerTransformer(new ZombiePopManRemoveChunkPatch());
            registerTransformer(new AnimalPopManRemoveChunkPatch());
            registerTransformer(new IsoChunkAddVehiclesPatch());
            registerTransformer(new IsoChunkAddZombieZoneStoryPatch());
            registerTransformer(new IsoChunkAddRanchAnimalsPatch());
            registerTransformer(new IsoChunkAddCorpsesPatch());
            registerTransformer(new IsoChunkAddBloodPatch());
            registerTransformer(new IsoChunkCheckGrassRegrowthPatch());
            registerTransformer(new ErosionMainLoadGridsquarePatch());

            // ServerMap.QueuedSaveAll components (rare but large spikes).
            registerTransformer(new ServerMapSaveAllPatch());
            registerTransformer(new ServerPlayerDBSavePatch());
            registerTransformer(new AnimalPopManSavePatch());
            registerTransformer(new MapCollisionDataSavePatch());
            registerTransformer(new ZomboidRadioSavePatch());
            registerTransformer(new GlobalModDataSavePatch());
            registerTransformer(new WorldMapServerWriteSavefilePatch());

            // GameEntityManager.Update internals (steady-state ~3.4ms).
            registerTransformer(new EngineUpdatePatch());
            registerTransformer(new EngineUpdateSimulationPatch());
            registerTransformer(new EntitySimulationUpdatePatch());
            registerTransformer(new TryAddIndoorZombiesPatch());
            registerTransformer(new MapCollisionDataUpdateGameStatePatch());
            registerTransformer(new IngameStateUpdatePatch());
            registerTransformer(new RCONServerUpdatePatch());
            registerTransformer(new ObjectIDManagerCheckSaveDataPatch());
            registerTransformer(new ImportantAreaManagerProcessPatch());
            registerTransformer(new ServerGUIUpdatePatch());
            registerTransformer(new PublicServerUtilUpdatePatch());
            registerTransformer(new SendWorldMapPlayerPositionPatch());
            registerTransformer(new LoginQueueUpdatePatch());
            registerTransformer(new ZipBackupOnPeriodPatch());
            registerTransformer(new SteamUtilsRunLoopPatch());
            registerTransformer(new TradingManagerUpdatePatch());
            registerTransformer(new WarManagerUpdatePatch());
            registerTransformer(new SafeHouseUpdatePatch());
            registerTransformer(new NetworkPlayerManagerUpdatePatch());
            registerTransformer(new FileSystemUpdateAsyncTransactionsPatch());
            registerTransformer(new WorldMapVisitedServerUpdatePatch());
            registerTransformer(new PlayerDownloadServerUpdatePatch());
            registerTransformer(new ChunkStreamWorkerMetricsPatch());
            registerTransformer(new RequestZipListParsePatch());
            registerTransformer(new ClientChunkRequestRetryPatch());
            registerTransformer(new IsoChunkSafeReadPatch());
            registerTransformer(new ChunkChecksumMetricsPatch());
            registerTransformer(new CalcCountPlayersInRelevantPositionPatch());
            registerTransformer(new ServerMapCharacterInPatch());
            registerTransformer(new ClientServerMapCharacterInPatch());
            registerTransformer(new PacketValidatorUpdatePatch());
            registerTransformer(new RemoveZombiesPatch());
            registerTransformer(new RemoveAnimalsPatch());
            registerTransformer(new RemoveDeadBodiesPatch());
            registerTransformer(new RemoveVehiclesPatch());
            registerTransformer(new StatisticManagerUpdatePatch());
            registerTransformer(new PublicServerUtilUpdatePlayerCountPatch());
            registerTransformer(new CoopSlaveUpdatePatch());
            registerTransformer(new VirtualAnimalStridePatch());
            registerTransformer(new ZombieAuthStridePatch());
            registerTransformer(new InventoryItemSweepStridePatch());
        }

        // Register generic packet event dispatching for all supported packet types
        for (String packetClass : PacketEventDispatcher.SUPPORTED_PACKETS) {
            registerTransformer(new PacketReceivedPatch(packetClass));
        }
    }

    private static void registerTransformer(StormClassTransformer transformer) {
        TRANSFORMERS
                .computeIfAbsent(transformer.getClassName(), k -> new ArrayList<>())
                .add(transformer);
    }

    /**
     * Called by {@link StormBootstrap#loadAndRegisterMods()} to collect mod-provided transformers.
     */
    public static void collectTransformers() {
        for (ZomboidMod mod : StormModRegistry.getRegisteredMods()) {
            List<StormClassTransformer> transformers = mod.getClassTransformers();
            if (transformers != null) {
                for (StormClassTransformer transformer : transformers) {
                    registerTransformer(transformer);
                }
            }
        }
    }

    /**
     * Returns all registered {@link StormClassTransformer} instances that target the given class.
     *
     * @return list of transformers (empty if none registered).
     */
    @Contract(pure = true)
    public static List<StormClassTransformer> getRegistered(String className) {
        return TRANSFORMERS.getOrDefault(className, Collections.emptyList());
    }

    /**
     * Applies all registered transformers for the given class name sequentially. Each transformer
     * independently redefines the class bytes produced by the previous transformer.
     *
     * @param className the binary name of the class to transform.
     * @param rawClass byte array representing the class.
     * @return transformed byte array, or the original if no transformers are registered.
     */
    public static byte[] applyAll(String className, byte[] rawClass) {
        List<StormClassTransformer> transformers = getRegistered(className);
        for (StormClassTransformer transformer : transformers) {
            LOGGER.info(
                    "Applying transformer {} to class {}",
                    transformer.getClass().getSimpleName(),
                    className);
            try {
                rawClass = transformer.transform(rawClass);
                LOGGER.info(
                        "Successfully applied transformer {} to class {}",
                        transformer.getClass().getSimpleName(),
                        className);
            } catch (Exception e) {
                LOGGER.error(
                        "Failed to apply transformer {} to class {}: {}",
                        transformer.getClass().getSimpleName(),
                        className,
                        e.getMessage(),
                        e);
                throw e;
            }
        }
        return rawClass;
    }

    /**
     * Applies transformers that target classes blacklisted by {@link StormClassLoader} (e.g. {@code
     * java.lang.*}) using the {@link Instrumentation} retransformation API. The {@code
     * Instrumentation} instance is provided by the bootstrap agent's {@code premain()}.
     */
    public static void applyAgentTransformers(Instrumentation instrumentation) {
        for (String className : TRANSFORMERS.keySet()) {
            if (!StormClassLoader.isBlacklistedClass(className)
                    && !StormClassLoader.isJdkRuntimeClass(className)) {
                continue;
            }

            List<StormClassTransformer> transformers =
                    TRANSFORMERS.getOrDefault(className, Collections.emptyList());
            LOGGER.debug("Applying agent-based transformer for blacklisted class: {}", className);

            ResettableClassFileTransformer agent =
                    new AgentBuilder.Default()
                            .disableClassFormatChanges()
                            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                            .ignore(ElementMatchers.none())
                            .with(
                                    new AgentBuilder.Listener.Adapter() {
                                        @Override
                                        public void onError(
                                                String typeName,
                                                ClassLoader classLoader,
                                                net.bytebuddy.utility.JavaModule module,
                                                boolean loaded,
                                                Throwable throwable) {
                                            LOGGER.error(
                                                    "Agent transformer failed for {}: {}",
                                                    typeName,
                                                    throwable.getMessage(),
                                                    throwable);
                                        }

                                        @Override
                                        public void onTransformation(
                                                net.bytebuddy.description.type.TypeDescription
                                                        typeDescription,
                                                ClassLoader classLoader,
                                                net.bytebuddy.utility.JavaModule module,
                                                boolean loaded,
                                                DynamicType dynamicType) {
                                            LOGGER.debug(
                                                    "Successfully retransformed: {}",
                                                    typeDescription.getName());
                                        }
                                    })
                            .type(ElementMatchers.named(className))
                            .transform(
                                    (builder, typeDescription, classLoader, module, domain) -> {
                                        @SuppressWarnings("unchecked")
                                        DynamicType.Builder<Object> castedBuilder =
                                                (DynamicType.Builder<Object>)
                                                        (DynamicType.Builder<?>) builder;
                                        for (StormClassTransformer transformer : transformers) {
                                            ClassFileLocator locator =
                                                    new ClassFileLocator.Compound(
                                                            ClassFileLocator.ForClassLoader.of(
                                                                    transformer
                                                                            .getClass()
                                                                            .getClassLoader()),
                                                            ClassFileLocator.ForClassLoader
                                                                    .ofSystemLoader());
                                            TypePool typePool = TypePool.Default.of(locator);
                                            castedBuilder =
                                                    transformer.dynamicType(
                                                            locator, typePool, castedBuilder);
                                        }
                                        return castedBuilder;
                                    })
                            .installOn(instrumentation);

            LOGGER.debug("Installed agent transformer for: {}", className);
        }
    }
}
