package io.pzstorm.storm.advice.animalupdatetiming;

import io.pzstorm.storm.los.PlayerLOSAuthorityManager;
import io.pzstorm.storm.metrics.AnimalSyncMetrics;
import io.pzstorm.storm.metrics.AnimalUpdateLOSMetrics;
import io.pzstorm.storm.metrics.AnimalUpdateMetrics;
import io.pzstorm.storm.metrics.BaseVehicleUpdateMetrics;
import io.pzstorm.storm.metrics.CellObjectAddMetrics;
import io.pzstorm.storm.metrics.CellObjectRemoveMetrics;
import io.pzstorm.storm.metrics.ChunkLoadMetrics;
import io.pzstorm.storm.metrics.ChunkRemoveMetrics;
import io.pzstorm.storm.metrics.ChunkSaveMetrics;
import io.pzstorm.storm.metrics.EntityManagerUpdateMetrics;
import io.pzstorm.storm.metrics.LuaMainloopMetrics;
import io.pzstorm.storm.metrics.NetDataMetrics;
import io.pzstorm.storm.metrics.ObjectRemoveFromWorldMetrics;
import io.pzstorm.storm.metrics.PlayerUpdateLOSMetrics;
import io.pzstorm.storm.metrics.RemotePlayerUpdateMetrics;
import io.pzstorm.storm.metrics.ServerCellUnloadMetrics;
import io.pzstorm.storm.metrics.ServerLOSUpdateMetrics;
import io.pzstorm.storm.metrics.ServerMapPostUpdateMetrics;
import io.pzstorm.storm.metrics.UsingPlayerUpdateMetrics;
import io.pzstorm.storm.metrics.VehicleSendMetrics;
import io.pzstorm.storm.metrics.VehicleServerUpdateMetrics;
import io.pzstorm.storm.metrics.ZombieManagerAuthMetrics;
import io.pzstorm.storm.metrics.ZombieSpotPlayerMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

public class MovingObjectUpdateSchedulerStartFrameAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        if (!GameServer.server) {
            return;
        }
        AnimalUpdateMetrics.recordTick();
        ChunkRemoveMetrics.recordTick();
        CellObjectRemoveMetrics.recordTick();
        CellObjectAddMetrics.recordTick();
        ObjectRemoveFromWorldMetrics.recordTick();
        ServerCellUnloadMetrics.recordTick();
        AnimalUpdateLOSMetrics.recordTick();
        PlayerUpdateLOSMetrics.recordTick();
        ServerLOSUpdateMetrics.recordTick();
        RemotePlayerUpdateMetrics.recordTick();
        ZombieSpotPlayerMetrics.recordTick();
        VehicleServerUpdateMetrics.recordTick();
        VehicleSendMetrics.recordTick();
        BaseVehicleUpdateMetrics.recordTick();
        NetDataMetrics.recordTick();
        ChunkLoadMetrics.recordTick();
        ChunkSaveMetrics.recordTick();
        ServerMapPostUpdateMetrics.recordTick();
        UsingPlayerUpdateMetrics.recordTick();
        EntityManagerUpdateMetrics.recordTick();
        ZombieManagerAuthMetrics.recordTick();
        AnimalSyncMetrics.recordTick();
        LuaMainloopMetrics.recordTick();
        PlayerLOSAuthorityManager.INSTANCE.tick();
    }
}
