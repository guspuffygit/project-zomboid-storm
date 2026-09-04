package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.PacketEventDispatcher;
import io.pzstorm.storm.mod.ZomboidMod;
import io.pzstorm.storm.patch.client.CombatManagerBallisticsNullGuardPatch;
import io.pzstorm.storm.patch.client.CoreResetLuaPatch;
import io.pzstorm.storm.patch.client.IsoObjectAdminSeeAllTargetAlphaPatch;
import io.pzstorm.storm.patch.client.IsoWorldInventoryObjectRenderSpriteGuardPatch;
import io.pzstorm.storm.patch.client.PlayerDataRequestBackoffPatch;
import io.pzstorm.storm.patch.client.VehicleChunkRehomePatch;
import io.pzstorm.storm.patch.client.VehicleModelAttachRetryPatch;
import io.pzstorm.storm.patch.client.VehicleRequestMergeFlagsPatch;
import io.pzstorm.storm.patch.client.VehicleSoundsClientCreatePatch;
import io.pzstorm.storm.patch.client.VehicleTowConstraintSnapPatch;
import io.pzstorm.storm.patch.client.experimental.KahluaMetatableCachePatch;
import io.pzstorm.storm.patch.client.experimental.VehicleModDataRequestPatch;
import io.pzstorm.storm.patch.core.CommandBasePatch;
import io.pzstorm.storm.patch.core.ZomboidFileSystemPatch;
import io.pzstorm.storm.patch.core.ZomboidGlobalsPatch;
import io.pzstorm.storm.patch.debugging.DebugLogPatch;
import io.pzstorm.storm.patch.debugging.ThreadPatch;
import io.pzstorm.storm.patch.events.AnimalDeathBypassPatch;
import io.pzstorm.storm.patch.events.ChatManagerPatch;
import io.pzstorm.storm.patch.events.ChatServerSendMessagePatch;
import io.pzstorm.storm.patch.events.LuaEventManagerPatch;
import io.pzstorm.storm.patch.events.OnDeathTriggerPatch;
import io.pzstorm.storm.patch.fixes.ActionGroupSyncPatch;
import io.pzstorm.storm.patch.fixes.ActionManagerPatch;
import io.pzstorm.storm.patch.fixes.ActionStateContainerPatch;
import io.pzstorm.storm.patch.fixes.AdvancedAnimatorMissingFolderPatch;
import io.pzstorm.storm.patch.fixes.AnimalZoneContainmentPatch;
import io.pzstorm.storm.patch.fixes.AnimationSetLockPatch;
import io.pzstorm.storm.patch.fixes.AssetManagerSyncPatch;
import io.pzstorm.storm.patch.fixes.BaseVehicleSavePatch;
import io.pzstorm.storm.patch.fixes.BodyDamageSyncPatch;
import io.pzstorm.storm.patch.fixes.BodyDamageUpdatePacketPatch;
import io.pzstorm.storm.patch.fixes.ChatServerDisconnectPatch;
import io.pzstorm.storm.patch.fixes.CompressIdenticalItemsPatch;
import io.pzstorm.storm.patch.fixes.CoopHatchPositionFixPatch;
import io.pzstorm.storm.patch.fixes.GameServerStartPMChatPatch;
import io.pzstorm.storm.patch.fixes.GeneralActionPacketPatch;
import io.pzstorm.storm.patch.fixes.HutchDirtRateFixPatch;
import io.pzstorm.storm.patch.fixes.InventoryItemStoreByteDataPatch;
import io.pzstorm.storm.patch.fixes.IsoAnimalCanClimbStairsNullDefGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoAnimalReattachBackToMomPatch;
import io.pzstorm.storm.patch.fixes.IsoAnimalRegistryFixPatch;
import io.pzstorm.storm.patch.fixes.IsoAnimalUpdateNullDefGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoGridSquareGetRoomNullDefGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoMovingObjectIsPushedByForSeparateNullDefGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoObjectIDAllocateFixPatch;
import io.pzstorm.storm.patch.fixes.IsoObjectTransmitUpdatedSpriteGuardPatch;
import io.pzstorm.storm.patch.fixes.IsoZombieUpdateFixPatch;
import io.pzstorm.storm.patch.fixes.ItemTransactionPacketPatch;
import io.pzstorm.storm.patch.fixes.NetTimedActionPacketPatch;
import io.pzstorm.storm.patch.fixes.RefreshAnimSetsLockPatch;
import io.pzstorm.storm.patch.fixes.RequestDataManagerFixPatch;
import io.pzstorm.storm.patch.fixes.RequestSaveCellSuppressPatch;
import io.pzstorm.storm.patch.fixes.SaveChunkThreadCrcRacePatch;
import io.pzstorm.storm.patch.fixes.SaveLoadedTaskCrcRacePatch;
import io.pzstorm.storm.patch.fixes.ServerCellRecalcCrashGuardPatch;
import io.pzstorm.storm.patch.fixes.SitOnFurnitureBoxedInChairPatch;
import io.pzstorm.storm.patch.fixes.SpriteConfigFixPatch;
import io.pzstorm.storm.patch.fixes.TransactionManagerPatch;
import io.pzstorm.storm.patch.fixes.TranslatorPatch;
import io.pzstorm.storm.patch.fixes.WorldMapVisitedServerAllKnownPatch;
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
import io.pzstorm.storm.patch.networking.ServerOptionsMaxPlayersPatch;
import io.pzstorm.storm.patch.networking.ServerQueryPatch;
import io.pzstorm.storm.patch.networking.ServerWorldDatabasePatch;
import io.pzstorm.storm.patch.networking.SteamGameServerPlayerListPatch;
import io.pzstorm.storm.patch.networking.UdpConnectionRelevancePatch;
import io.pzstorm.storm.patch.performance.AnimalCellLoadMetricsPatch;
import io.pzstorm.storm.patch.performance.AnimalControllerUpdatePatch;
import io.pzstorm.storm.patch.performance.AnimalPopManRemoveChunkPatch;
import io.pzstorm.storm.patch.performance.AnimalPopManSavePatch;
import io.pzstorm.storm.patch.performance.AnimalRealizeMetricsPatch;
import io.pzstorm.storm.patch.performance.AnimalSyncManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.AnimalVirtualRegisterMetricsPatch;
import io.pzstorm.storm.patch.performance.AnimalZoneSpawnMetricsPatch;
import io.pzstorm.storm.patch.performance.AnimalZonesUpdateVirtualAnimalsPatch;
import io.pzstorm.storm.patch.performance.AnimationPlayerRecorderIsActivePatch;
import io.pzstorm.storm.patch.performance.AnimationVariableReferenceGetVariablePatch;
import io.pzstorm.storm.patch.performance.BaseVehicleAlphaCheckSkipPatch;
import io.pzstorm.storm.patch.performance.BaseVehicleBreakingObjectsSkipPatch;
import io.pzstorm.storm.patch.performance.BaseVehicleCropCheckSkipPatch;
import io.pzstorm.storm.patch.performance.BaseVehicleUpdatePatch;
import io.pzstorm.storm.patch.performance.BitHeaderByteReleasePatch;
import io.pzstorm.storm.patch.performance.BitHeaderGetHeaderPatch;
import io.pzstorm.storm.patch.performance.BitHeaderIntReleasePatch;
import io.pzstorm.storm.patch.performance.BitHeaderLongReleasePatch;
import io.pzstorm.storm.patch.performance.BitHeaderShortReleasePatch;
import io.pzstorm.storm.patch.performance.BodyDamageLastStateSkipPatch;
import io.pzstorm.storm.patch.performance.CalcCountPlayersInRelevantPositionPatch;
import io.pzstorm.storm.patch.performance.CharacterStatIndexPatch;
import io.pzstorm.storm.patch.performance.CharacterTraitIndexPatch;
import io.pzstorm.storm.patch.performance.CharacterTraitsIndexedMapPatch;
import io.pzstorm.storm.patch.performance.CharacterVariableLookupAccessorPatch;
import io.pzstorm.storm.patch.performance.CharacterVariableResolveTypedPatch;
import io.pzstorm.storm.patch.performance.ChunkChecksumMetricsPatch;
import io.pzstorm.storm.patch.performance.ChunkStreamWorkerMetricsPatch;
import io.pzstorm.storm.patch.performance.ClientChunkRequestPatch;
import io.pzstorm.storm.patch.performance.ClientServerMapCharacterInPatch;
import io.pzstorm.storm.patch.performance.ClimateManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.ClothingWetnessVisualsPatch;
import io.pzstorm.storm.patch.performance.CollisionManagerInitUpdatePatch;
import io.pzstorm.storm.patch.performance.CollisionManagerResolveContactsPatch;
import io.pzstorm.storm.patch.performance.CoopSlaveUpdatePatch;
import io.pzstorm.storm.patch.performance.CorpseCountZombieIndexPatch;
import io.pzstorm.storm.patch.performance.CutawayLevelDataArrayCachePatch;
import io.pzstorm.storm.patch.performance.CutawayVisitFastPathPatch;
import io.pzstorm.storm.patch.performance.DesignationZoneAnimalFoodFastContainsPatch;
import io.pzstorm.storm.patch.performance.DesignationZoneAnimalGetAllDZonesPatch;
import io.pzstorm.storm.patch.performance.EcsComponentGetClassMemoPatch;
import io.pzstorm.storm.patch.performance.EcsEntityTryGetMemoPatch;
import io.pzstorm.storm.patch.performance.EcsGetClassCachePatch;
import io.pzstorm.storm.patch.performance.EngineEntityManagerIndexPatch;
import io.pzstorm.storm.patch.performance.EngineUpdatePatch;
import io.pzstorm.storm.patch.performance.EngineUpdateSimulationPatch;
import io.pzstorm.storm.patch.performance.EntityArrayRemoveFastPathPatch;
import io.pzstorm.storm.patch.performance.EntityBucketIndexPatch;
import io.pzstorm.storm.patch.performance.EntitySimulationUpdatePatch;
import io.pzstorm.storm.patch.performance.ErosionMainLoadGridsquarePatch;
import io.pzstorm.storm.patch.performance.EventTriggerFastPathPatch;
import io.pzstorm.storm.patch.performance.FBORenderCellRenderLayerHoistPatch;
import io.pzstorm.storm.patch.performance.FBORenderLevelsFreeSkipPatch;
import io.pzstorm.storm.patch.performance.FileSystemUpdateAsyncTransactionsPatch;
import io.pzstorm.storm.patch.performance.FishSchoolManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.FluidContainerInvEpochPatch;
import io.pzstorm.storm.patch.performance.FluidContainerUpdateSimulationFastPathPatch;
import io.pzstorm.storm.patch.performance.FoodInvEpochPatch;
import io.pzstorm.storm.patch.performance.GameEntityManagerSavePatch;
import io.pzstorm.storm.patch.performance.GameEntityManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.GameEntityUsingPlayerTrackingPatch;
import io.pzstorm.storm.patch.performance.GameProfilerGatePatch;
import io.pzstorm.storm.patch.performance.GameServerNetDataPatch;
import io.pzstorm.storm.patch.performance.GlobalModDataSavePatch;
import io.pzstorm.storm.patch.performance.HandWeaponInvEpochPatch;
import io.pzstorm.storm.patch.performance.ImportantAreaManagerProcessPatch;
import io.pzstorm.storm.patch.performance.IngameStateUpdatePatch;
import io.pzstorm.storm.patch.performance.InventoryItemInvEpochPatch;
import io.pzstorm.storm.patch.performance.InventoryItemSweepStridePatch;
import io.pzstorm.storm.patch.performance.InventoryItemVisualFieldPatch;
import io.pzstorm.storm.patch.performance.IsoAnimalReattachBackToMomCellAnimalsMemoPatch;
import io.pzstorm.storm.patch.performance.IsoAnimalUpdateLOSPatch;
import io.pzstorm.storm.patch.performance.IsoAnimalUpdateTimingPatch;
import io.pzstorm.storm.patch.performance.IsoCellGetAnimalsPatch;
import io.pzstorm.storm.patch.performance.IsoCellObjectDeletionAdditionPatch;
import io.pzstorm.storm.patch.performance.IsoCellProcessIsoObjectPatch;
import io.pzstorm.storm.patch.performance.IsoCellProcessItemsPatch;
import io.pzstorm.storm.patch.performance.IsoCellProcessListsFastContainsPatch;
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
import io.pzstorm.storm.patch.performance.IsoGameCharacterCheckIsNearVehiclePatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterEcsMemoPatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterInvWeightMemoPatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterIsRagdollPatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterRagdollMirrorsPatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterStateMachineMemoPatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterUpdateEmitterServerSkipPatch;
import io.pzstorm.storm.patch.performance.IsoGameCharacterWornVisualsMemoPatch;
import io.pzstorm.storm.patch.performance.IsoGeneratorElectricityPatch;
import io.pzstorm.storm.patch.performance.IsoGridSquareFloorFlagsPatch;
import io.pzstorm.storm.patch.performance.IsoGridSquareLosParallelPatch;
import io.pzstorm.storm.patch.performance.IsoLightSwitchElectricityMemoPatch;
import io.pzstorm.storm.patch.performance.IsoObjectRemoveFromWorldPatch;
import io.pzstorm.storm.patch.performance.IsoPhysicsObjectFpsPatch;
import io.pzstorm.storm.patch.performance.IsoPlayerUpdateLOSFastPathPatch;
import io.pzstorm.storm.patch.performance.IsoPlayerUpdateLOSPatch;
import io.pzstorm.storm.patch.performance.IsoPlayerUpdateRemotePatch;
import io.pzstorm.storm.patch.performance.IsoRegionsUpdatePatch;
import io.pzstorm.storm.patch.performance.IsoRoomOnSeePatch;
import io.pzstorm.storm.patch.performance.IsoSpriteFloorFlagsPatch;
import io.pzstorm.storm.patch.performance.IsoThumpableUpdateSkipPatch;
import io.pzstorm.storm.patch.performance.IsoWaterFlowMemoPatch;
import io.pzstorm.storm.patch.performance.IsoWorldUpdateBuildingsPatch;
import io.pzstorm.storm.patch.performance.IsoWorldUpdateDBsPatch;
import io.pzstorm.storm.patch.performance.IsoWorldUpdatePatch;
import io.pzstorm.storm.patch.performance.ItemContainerTrackedListPatch;
import io.pzstorm.storm.patch.performance.ItemTagIndexPatch;
import io.pzstorm.storm.patch.performance.ItemTagMaskPatch;
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
import io.pzstorm.storm.patch.performance.MoodleTypeIndexPatch;
import io.pzstorm.storm.patch.performance.MoodlesIndexedMapPatch;
import io.pzstorm.storm.patch.performance.MovingObjectSchedulerBucketAddPatch;
import io.pzstorm.storm.patch.performance.MovingObjectSchedulerPostupdatePatch;
import io.pzstorm.storm.patch.performance.MovingObjectSchedulerStartFramePatch;
import io.pzstorm.storm.patch.performance.NetworkPlayerManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.NetworkZombieManagerAuthPatch;
import io.pzstorm.storm.patch.performance.NetworkZombiePackerAuthPassPatch;
import io.pzstorm.storm.patch.performance.NetworkZombiePackerPostUpdatePatch;
import io.pzstorm.storm.patch.performance.ObjectIDManagerCheckSaveDataPatch;
import io.pzstorm.storm.patch.performance.ObjectRenderEffectsUpdateStaticPatch;
import io.pzstorm.storm.patch.performance.PacketLimitMetricsPatch;
import io.pzstorm.storm.patch.performance.PacketValidatorUpdatePatch;
import io.pzstorm.storm.patch.performance.PacketsCacheLimitBypassPatch;
import io.pzstorm.storm.patch.performance.PathfindNativeRemoveChunkPatch;
import io.pzstorm.storm.patch.performance.PerformanceProbeGatePatch;
import io.pzstorm.storm.patch.performance.PlayerDownloadServerRemoveOlderPatch;
import io.pzstorm.storm.patch.performance.PlayerDownloadServerUpdatePatch;
import io.pzstorm.storm.patch.performance.PolygonalMap2RemoveChunkPatch;
import io.pzstorm.storm.patch.performance.PropertyContainerHasIdCachePatch;
import io.pzstorm.storm.patch.performance.PropertyContainerHasStringIdCachePatch;
import io.pzstorm.storm.patch.performance.PublicServerUtilUpdatePatch;
import io.pzstorm.storm.patch.performance.PublicServerUtilUpdatePlayerCountPatch;
import io.pzstorm.storm.patch.performance.RCONServerUpdatePatch;
import io.pzstorm.storm.patch.performance.RanchAnimalSpawnMetricsPatch;
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
import io.pzstorm.storm.patch.performance.ServerMapReleventNowFastContainsPatch;
import io.pzstorm.storm.patch.performance.ServerMapSaveAllPatch;
import io.pzstorm.storm.patch.performance.ServerPlayerDBSavePatch;
import io.pzstorm.storm.patch.performance.ServerTickPatch;
import io.pzstorm.storm.patch.performance.StatisticManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.StatsGetPatch;
import io.pzstorm.storm.patch.performance.SteamUtilsRunLoopPatch;
import io.pzstorm.storm.patch.performance.TestZombieSpotPlayerPatch;
import io.pzstorm.storm.patch.performance.ThermalNodeInsulationVisualPatch;
import io.pzstorm.storm.patch.performance.ThermoregulatorClothingVisualsPatch;
import io.pzstorm.storm.patch.performance.TradingManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.TryAddIndoorZombiesPatch;
import io.pzstorm.storm.patch.performance.UsingPlayerSweepFastPathPatch;
import io.pzstorm.storm.patch.performance.UsingPlayerUpdatePatch;
import io.pzstorm.storm.patch.performance.VehicleManagerSendVehiclesPatch;
import io.pzstorm.storm.patch.performance.VehicleManagerServerUpdatePatch;
import io.pzstorm.storm.patch.performance.VehicleSoundRelevancePatch;
import io.pzstorm.storm.patch.performance.VirtualAnimalStridePatch;
import io.pzstorm.storm.patch.performance.WarManagerUpdatePatch;
import io.pzstorm.storm.patch.performance.WeatherFxScanSkipPatch;
import io.pzstorm.storm.patch.performance.WorldMapServerWriteSavefilePatch;
import io.pzstorm.storm.patch.performance.WorldMapVisitedServerUpdatePatch;
import io.pzstorm.storm.patch.performance.WorldSimulationUpdatePatch;
import io.pzstorm.storm.patch.performance.WorldSoundServerChunkIndexPatch;
import io.pzstorm.storm.patch.performance.WornItemsMutationEpochPatch;
import io.pzstorm.storm.patch.performance.ZipBackupOnPeriodPatch;
import io.pzstorm.storm.patch.performance.ZombieAuthScanFastPathPatch;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.jetbrains.annotations.Contract;

@SuppressWarnings({"WeakerAccess", "unused"})
public class StormClassTransformers {

    private static final Map<String, List<StormClassTransformer>> TRANSFORMERS = new HashMap<>();

    /** Class names for which {@link #applyAll} has actually applied at least one transformer. */
    private static final Set<String> TRANSFORMED_CLASSES = ConcurrentHashMap.newKeySet();

    static {
        registerTransformer(new MainScreenStatePatch());
        registerTransformer(new TISLogoStatePatch());
        if (Boolean.getBoolean("storm.skipmenus")) {
            registerTransformer(new EpilepsyWarningSkipPatch());
            registerTransformer(new TISLogoStateSkipPatch());
            registerTransformer(new TermsOfServiceStateSkipPatch());
        }
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
        registerTransformer(new IsoGridSquareGetRoomNullDefGuardPatch());
        registerTransformer(new BaseVehicleSavePatch());
        registerTransformer(new SitOnFurnitureBoxedInChairPatch());
        registerTransformer(new InventoryItemStoreByteDataPatch());
        registerTransformer(new KahluaTableRawgetPatch());
        if (StormEnv.isStormServer() || Boolean.getBoolean("storm.experimental.clientperf")) {
            registerTransformer(new AnimationPlayerRecorderIsActivePatch());
        }
        if (Boolean.getBoolean("storm.experimental.clientperf")) {
            registerTransformer(new EcsComponentGetClassMemoPatch());
            registerTransformer(new IsoLightSwitchElectricityMemoPatch());
            registerTransformer(new JniLightingCleanSquarePatch());
            registerTransformer(new IsoWaterFlowMemoPatch());
            registerTransformer(new PropertyContainerHasIdCachePatch());
            registerTransformer(new PropertyContainerHasStringIdCachePatch());
            registerTransformer(new WeatherFxScanSkipPatch());
            registerTransformer(new LightingPreUpdateForkPatch());
            registerTransformer(new AnimationVariableReferenceGetVariablePatch());
            registerTransformer(new IsoGameCharacterIsRagdollPatch());
            registerTransformer(new IsoGameCharacterRagdollMirrorsPatch());
            registerTransformer(new EventTriggerFastPathPatch());
            registerTransformer(new CutawayLevelDataArrayCachePatch());
            registerTransformer(new FBORenderLevelsFreeSkipPatch());
            registerTransformer(new CutawayVisitFastPathPatch());
            registerTransformer(new FBORenderCellRenderLayerHoistPatch());
        }
        if (StormEnv.isStormServer()) {
            registerTransformer(new IsoGeneratorElectricityPatch());
            registerTransformer(new IsoAnimalUpdateTimingPatch());
            registerTransformer(new IsoChunkRemoveFromWorldPatch());
            registerTransformer(new IsoObjectRemoveFromWorldPatch());
            registerTransformer(new OnDeathTriggerPatch("zombie.characters.IsoGameCharacter"));
            registerTransformer(new OnDeathTriggerPatch("zombie.characters.animals.IsoAnimal"));
            registerTransformer(new AnimalDeathBypassPatch("zombie.iso.objects.IsoHutch"));
            registerTransformer(new AnimalDeathBypassPatch("zombie.characters.IsoGameCharacter"));
            registerTransformer(new AnimalDeathBypassPatch("zombie.characters.animals.IsoAnimal"));
            // SPVThread vs main-thread animset-load race; a THashMap.rehash AIOOBE during
            // vehicle load permanently deletes the vehicle from vehicles.db.
            registerTransformer(new AnimationSetLockPatch());
            registerTransformer(new RefreshAnimSetsLockPatch());
            registerTransformer(new AdvancedAnimatorMissingFolderPatch());
            registerTransformer(new ActionGroupSyncPatch());
            registerTransformer(new AssetManagerSyncPatch());
        }
        registerTransformer(new ServerCellUnloadPatch());
        registerTransformer(new ServerLOSUpdatePatch());
        registerTransformer(new ServerLOSIsCouldSeePatch());
        if (StormEnv.isStormServer()) {
            registerTransformer(new StatsGetPatch());
            registerTransformer(new MovingObjectSchedulerStartFramePatch());
            registerTransformer(new IsoPlayerUpdateRemotePatch());
            registerTransformer(new IsoPlayerUpdateLOSFastPathPatch());
            registerTransformer(new IsoPlayerUpdateLOSPatch());
            registerTransformer(new IsoAnimalUpdateLOSPatch());
            registerTransformer(new TestZombieSpotPlayerPatch());
            registerTransformer(new ZombieVehicleOcclusionPatch());
            registerTransformer(new VehicleManagerServerUpdatePatch());
            registerTransformer(new VehicleManagerSendVehiclesPatch());
            registerTransformer(new BaseVehicleUpdatePatch());
            registerTransformer(new BaseVehicleAlphaCheckSkipPatch());
            registerTransformer(new BaseVehicleBreakingObjectsSkipPatch());
            registerTransformer(new BaseVehicleCropCheckSkipPatch());
            registerTransformer(new WorldSoundServerChunkIndexPatch());
            registerTransformer(new IsoGameCharacterCheckIsNearVehiclePatch());
            registerTransformer(new IsoThumpableUpdateSkipPatch());
            registerTransformer(new CorpseCountZombieIndexPatch());
            registerTransformer(new VehicleSoundRelevancePatch());
            registerTransformer(new GameServerNetDataPatch());
            registerTransformer(new IsoChunkLoadPatch());
            registerTransformer(new IsoChunkSavePatch());
            registerTransformer(new BitHeaderGetHeaderPatch());
            registerTransformer(new BitHeaderByteReleasePatch());
            registerTransformer(new BitHeaderShortReleasePatch());
            registerTransformer(new BitHeaderIntReleasePatch());
            registerTransformer(new BitHeaderLongReleasePatch());
            registerTransformer(new ServerMapPostUpdateBudgetPatch());
            registerTransformer(new ServerMapPostUpdatePatch());
            registerTransformer(new GameEntityUsingPlayerTrackingPatch());
            registerTransformer(new UsingPlayerSweepFastPathPatch());
            registerTransformer(new UsingPlayerUpdatePatch());
            registerTransformer(new FluidContainerUpdateSimulationFastPathPatch());
            registerTransformer(new EcsGetClassCachePatch());
            registerTransformer(new EcsEntityTryGetMemoPatch());
            registerTransformer(new IsoGameCharacterEcsMemoPatch());
            registerTransformer(new IsoGameCharacterInvWeightMemoPatch());
            registerTransformer(new BodyDamageLastStateSkipPatch());
            registerTransformer(new CharacterStatIndexPatch());
            registerTransformer(new CharacterTraitIndexPatch());
            registerTransformer(new CharacterTraitsIndexedMapPatch());
            registerTransformer(new MoodleTypeIndexPatch());
            registerTransformer(new MoodlesIndexedMapPatch());
            registerTransformer(new ItemContainerTrackedListPatch());
            registerTransformer(new WornItemsMutationEpochPatch());
            registerTransformer(new InventoryItemInvEpochPatch());
            registerTransformer(new FoodInvEpochPatch());
            registerTransformer(new HandWeaponInvEpochPatch());
            registerTransformer(new FluidContainerInvEpochPatch());
            registerTransformer(new IsoGameCharacterStateMachineMemoPatch());
            registerTransformer(new CharacterVariableLookupAccessorPatch());
            registerTransformer(new CharacterVariableResolveTypedPatch());
            registerTransformer(new InventoryItemVisualFieldPatch());
            registerTransformer(new IsoGameCharacterWornVisualsMemoPatch());
            registerTransformer(new ThermoregulatorClothingVisualsPatch());
            registerTransformer(new ThermalNodeInsulationVisualPatch());
            registerTransformer(new ClothingWetnessVisualsPatch());
            registerTransformer(new ItemTagIndexPatch());
            registerTransformer(new ItemTagMaskPatch());
            registerTransformer(new IsoSpriteFloorFlagsPatch());
            registerTransformer(new IsoGridSquareFloorFlagsPatch());
            registerTransformer(new GameProfilerGatePatch());
            registerTransformer(new PerformanceProbeGatePatch());
            registerTransformer(new EngineEntityManagerIndexPatch());
            registerTransformer(new EntityBucketIndexPatch());
            registerTransformer(new EntityArrayRemoveFastPathPatch());
            registerTransformer(new GameEntityManagerUpdatePatch());
            registerTransformer(new NetworkZombieManagerAuthPatch());
            registerTransformer(new ZombieAuthScanFastPathPatch());
            registerTransformer(new NetworkZombiePackerAuthPassPatch());
            registerTransformer(new AnimalSyncManagerUpdatePatch());
            registerTransformer(new LuaMainloopPatch());
            registerTransformer(new IsoWorldUpdatePatch());
            registerTransformer(new AnimalControllerUpdatePatch());
            registerTransformer(new ZomboidRadioUpdatePatch());
        }
        registerTransformer(new PacketsCacheLimitBypassPatch());
        registerTransformer(new GameServerStartPMChatPatch());
        registerTransformer(new ChatServerDisconnectPatch());
        if (StormEnv.isStormServer()) {
            registerTransformer(new ChatServerSendMessagePatch());
        }
        registerTransformer(new KahluaMetatableCachePatch());

        if (StormEnv.isStormServer()) {
            registerTransformer(new ServerLOSFindDataPatch());
            registerTransformer(new ServerLOSRemovePlayerPatch());
            registerTransformer(new ServerLOSRunInnerPatch());
            registerTransformer(new IsoGridSquareLosParallelPatch());
            registerTransformer(new IsoRoomOnSeePatch());
        }

        if (!StormEnv.isStormServer()) {
            registerTransformer(new CoreResetLuaPatch());
            registerTransformer(new VehicleModDataRequestPatch());
            registerTransformer(new VehicleRequestMergeFlagsPatch());
            registerTransformer(new VehicleModelAttachRetryPatch());
            registerTransformer(new VehicleTowConstraintSnapPatch());
            registerTransformer(new VehicleChunkRehomePatch());
            registerTransformer(new VehicleSoundsClientCreatePatch());
            registerTransformer(new PacketLimitMetricsPatch());
            registerTransformer(new PlayerDataRequestBackoffPatch());
            registerTransformer(new IsoObjectAdminSeeAllTargetAlphaPatch());
            registerTransformer(new IsoWorldInventoryObjectRenderSpriteGuardPatch());
            registerTransformer(new CombatManagerBallisticsNullGuardPatch());
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
            registerTransformer(new IsoAnimalRegistryFixPatch());
            registerTransformer(new CoopHatchPositionFixPatch());
            registerTransformer(new HutchDirtRateFixPatch());
            registerTransformer(new AnimalZoneContainmentPatch());
            registerTransformer(new IsoObjectTransmitUpdatedSpriteGuardPatch());

            registerTransformer(new BodyDamageUpdatePacketPatch());
            registerTransformer(new BodyDamageSyncPatch());

            registerTransformer(new UdpConnectionRelevancePatch());

            registerTransformer(new IsoObjectSyncGatePatch());
            registerTransformer(new IsoWorldInventoryObjectSyncGatePatch());
            registerTransformer(new IsoBarricadeSyncGatePatch());
            registerTransformer(new IsoLightSwitchSyncGatePatch());

            registerTransformer(new GameEntityBroadcastGatePatch());
            registerTransformer(new GameServerWorkshopItemsPatch());
            registerTransformer(new GameServerStalledConnectionReapPatch());
            registerTransformer(new RequestDataManagerFixPatch());
            registerTransformer(new PlayerDownloadServerChunkActivityPatch());
            registerTransformer(new GameServerConnectionCapPatch());
            registerTransformer(new ServerOptionsMaxPlayersPatch());
            registerTransformer(new ConnectionManagerLogPatch());
            registerTransformer(new SteamGameServerPlayerListPatch());

            registerTransformer(new ServerQueryPatch());

            registerTransformer(new ServerMapPreUpdatePatch());
            registerTransformer(new ServerCellLoad2Patch());
            registerTransformer(new ServerCellRecalcAll2Patch());
            registerTransformer(new ServerCellRecalcCrashGuardPatch());
            registerTransformer(new ServerCellUpdatePatch());
            registerTransformer(new NetworkZombiePackerPostUpdatePatch());
            registerTransformer(new ServerChunkLoaderUpdateSavedPatch());
            registerTransformer(new SaveChunkThreadCrcRacePatch());
            registerTransformer(new SaveLoadedTaskCrcRacePatch());
            registerTransformer(new ServerMapQueuedSaveAllPatch());
            registerTransformer(new ServerMapPostUpdateWarmPatch());
            registerTransformer(new MovingObjectSchedulerBucketAddPatch());
            registerTransformer(new LoadedAreasAddPatch());
            registerTransformer(new GameEntityManagerSavePatch());
            registerTransformer(new PlayerDownloadServerRemoveOlderPatch());
            registerTransformer(new IsoChunkSaveLoadedChunkPatch());

            registerTransformer(new IsoCellUpdatePatch());
            registerTransformer(new IsoCellProcessIsoObjectPatch());
            registerTransformer(new IsoCellProcessObjectsPatch());
            registerTransformer(new IsoCellProcessStaticUpdatersPatch());
            registerTransformer(new IsoCellProcessSpottedRoomsPatch());
            registerTransformer(new IsoCellProcessItemsPatch());
            registerTransformer(new IsoCellProcessListsFastContainsPatch());
            registerTransformer(new ServerMapReleventNowFastContainsPatch());
            registerTransformer(new DesignationZoneAnimalFoodFastContainsPatch());
            registerTransformer(new DesignationZoneAnimalGetAllDZonesPatch());
            registerTransformer(new IsoCellObjectDeletionAdditionPatch());
            registerTransformer(new IsoCellGetAnimalsPatch());
            registerTransformer(new IsoAnimalReattachBackToMomCellAnimalsMemoPatch());
            registerTransformer(new IsoGameCharacterUpdateEmitterServerSkipPatch());
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

            registerTransformer(new MapCollisionDataRemoveChunkPatch());
            registerTransformer(new PolygonalMap2RemoveChunkPatch());
            registerTransformer(new PathfindNativeRemoveChunkPatch());
            registerTransformer(new ZombiePopManRemoveChunkPatch());
            registerTransformer(new AnimalPopManRemoveChunkPatch());
            registerTransformer(new IsoChunkAddVehiclesPatch());
            registerTransformer(new IsoChunkAddZombieZoneStoryPatch());
            registerTransformer(new IsoChunkAddRanchAnimalsPatch());
            registerTransformer(new AnimalZoneSpawnMetricsPatch());
            registerTransformer(new AnimalVirtualRegisterMetricsPatch());
            registerTransformer(new AnimalCellLoadMetricsPatch());
            registerTransformer(new AnimalRealizeMetricsPatch());
            registerTransformer(new RanchAnimalSpawnMetricsPatch());
            registerTransformer(new IsoChunkAddCorpsesPatch());
            registerTransformer(new IsoChunkAddBloodPatch());
            registerTransformer(new IsoChunkCheckGrassRegrowthPatch());
            registerTransformer(new ErosionMainLoadGridsquarePatch());

            registerTransformer(new ServerMapSaveAllPatch());
            registerTransformer(new ServerPlayerDBSavePatch());
            registerTransformer(new AnimalPopManSavePatch());
            registerTransformer(new MapCollisionDataSavePatch());
            registerTransformer(new ZomboidRadioSavePatch());
            registerTransformer(new GlobalModDataSavePatch());
            registerTransformer(new WorldMapServerWriteSavefilePatch());

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
            registerTransformer(new WorldMapVisitedServerAllKnownPatch());
            registerTransformer(new PlayerDownloadServerUpdatePatch());
            registerTransformer(new ChunkStreamWorkerMetricsPatch());
            registerTransformer(new RequestZipListParsePatch());
            registerTransformer(new ClientChunkRequestPatch());
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

        for (String packetClass : PacketEventDispatcher.SUPPORTED_PACKETS) {
            registerTransformer(new PacketReceivedPatch(packetClass));
        }
        errorIfTargetsAlreadyLoaded();
    }

    private static void registerTransformer(StormClassTransformer transformer) {
        TRANSFORMERS
                .computeIfAbsent(transformer.getClassName(), k -> new ArrayList<>())
                .add(transformer);
    }

    public static void collectTransformers() {
        for (ZomboidMod mod : StormModRegistry.getRegisteredMods()) {
            List<StormClassTransformer> transformers = mod.getClassTransformers();
            if (transformers != null) {
                for (StormClassTransformer transformer : transformers) {
                    registerTransformer(transformer);
                }
            }
        }
        errorIfTargetsAlreadyLoaded();
    }

    /**
     * Returns registered target classes that {@link StormClassLoader} has already defined without
     * any transformer applied. A non-empty result means those patches are silently dead: a class
     * gets exactly one shot at transformation, at define time, so any target loaded before its
     * transformers registered (e.g. dragged in by the bytecode verifier while linking a patch class
     * inside the registration block) stays untransformed for the lifetime of the JVM.
     *
     * <p>Blacklisted and JDK-runtime targets are excluded — those are retransformed by {@link
     * #applyAgentTransformers} instead of load-time weaving. Returns an empty list when this class
     * was not defined by {@code StormClassLoader} (unit tests, the app-loader copy used by {@code
     * StormLauncher}).
     */
    public static List<String> getLoadedUntransformedTargets() {
        ClassLoader loader = StormClassTransformers.class.getClassLoader();
        if (!(loader instanceof StormClassLoader stormLoader)) {
            return Collections.emptyList();
        }
        List<String> loaded = new ArrayList<>();
        for (String target : TRANSFORMERS.keySet()) {
            if (TRANSFORMED_CLASSES.contains(target) || !stormLoader.isClassLoaded(target)) {
                continue;
            }
            if (StormClassLoader.isBlacklistedClass(target)
                    || StormClassLoader.isJdkRuntimeClass(target)) {
                continue;
            }
            loaded.add(target);
        }
        return loaded;
    }

    /** Class names for which {@link #applyAll} has applied at least one transformer. */
    public static Set<String> getTransformedClasses() {
        return Collections.unmodifiableSet(TRANSFORMED_CLASSES);
    }

    private static void errorIfTargetsAlreadyLoaded() {
        for (String target : getLoadedUntransformedTargets()) {
            LOGGER.error(
                    "Transformer target {} was already loaded before its transformers were"
                            + " registered; its patches will NEVER apply. A patch class linked"
                            + " during registration must not reference the target class"
                            + " (move game-type logic to a separate class, see"
                            + " NetTimedActionPacketFix).",
                    target);
        }
    }

    @Contract(pure = true)
    public static List<StormClassTransformer> getRegistered(String className) {
        return TRANSFORMERS.getOrDefault(className, Collections.emptyList());
    }

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
        if (!transformers.isEmpty()) {
            TRANSFORMED_CLASSES.add(className);
        }
        return rawClass;
    }

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
