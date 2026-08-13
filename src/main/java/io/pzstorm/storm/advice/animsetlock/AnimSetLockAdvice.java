package io.pzstorm.storm.advice.animsetlock;

import java.util.concurrent.locks.ReentrantLock;
import net.bytebuddy.asm.Advice;

/**
 * Shared lock serializing every animation-set load path against the {@code ServerPlayersVehicles}
 * worker thread ({@code SPVThread}).
 *
 * <p>{@code GameServer.main} starts the SPVThread <i>before</i> the boot-time {@code
 * refreshAnimSets(true)} pass. When the thread streams in a vehicle carrying an animal, {@code
 * IsoAnimal.init → AnimationSet.GetAnimationSet → AnimState.Parse → AnimNodeAssetManager.load} puts
 * into the same unsynchronized {@code HashMap}/{@code THashMap}s the main thread is filling. The
 * observed failure is an {@code ArrayIndexOutOfBoundsException} out of {@code THashMap.rehash} —
 * and {@code VehiclesDB2$QueueLoadChunk.vehicleLoaded} reacts to <i>any</i> load exception by
 * permanently deleting the vehicle from vehicles.db.
 *
 * <p>A {@code ReentrantLock} (rather than {@code ACC_SYNCHRONIZED} monitors) is used so one lock
 * can span methods on different classes: {@code AnimationSet.GetAnimationSet}/{@code Reset} and the
 * whole body of {@code LuaManager$GlobalObject.refreshAnimSets}, whose unsynchronized iteration
 * over {@code AnimNodeAssetManager}'s asset table is part of the same race. Re-entrancy matters:
 * {@code refreshAnimSets} calls {@code GetAnimationSet} internally while holding the lock.
 *
 * <p>Lock ordering is one-directional — this lock is always taken before (never after) the {@code
 * AssetManager} instance monitors added by {@link
 * io.pzstorm.storm.patch.fixes.AssetManagerSyncPatch} and the {@code ActionGroup} class monitor
 * added by {@link io.pzstorm.storm.patch.fixes.ActionGroupSyncPatch}; neither {@code AssetManager}
 * nor {@code ActionGroup}/{@code ActionState} ever call back into {@code AnimationSet}.
 */
public class AnimSetLockAdvice {

    // Inlined getstatic runs from zombie.* classes, so this must be public.
    public static final ReentrantLock LOCK = new ReentrantLock();

    @Advice.OnMethodEnter
    public static void onEnter() {
        LOCK.lock();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        LOCK.unlock();
    }
}
