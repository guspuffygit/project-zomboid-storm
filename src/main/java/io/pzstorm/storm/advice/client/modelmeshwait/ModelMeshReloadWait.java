package io.pzstorm.storm.advice.client.modelmeshwait;

import io.pzstorm.storm.logging.StormLogger;
import java.util.ArrayList;
import zombie.GameWindow;
import zombie.core.Core;
import zombie.network.GameServer;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.AnimationsMesh;

/**
 * Blocks {@code ModelManager.initAnimationMeshes(true)} until every animation mesh it kicked off
 * has finished loading, the way vanilla already does for {@code initAnimationMeshes(false)}.
 *
 * <p>Vanilla's boot pass pumps the file system until every {@code AnimationsMesh.modelMesh} is
 * ready or failed. The reload pass that {@code Core.ResetLua} runs at connect skips that loop, and
 * {@code loadModAnimations()} then loads a mod's animations only {@code if
 * (am.modelMesh.isReady())}. A mesh that lands late is never picked up ({@code
 * MeshAssetManager.loadCallback} does not notify {@code ModelManager}, and {@code
 * loadModAnimations} registers the mod before the load loop so a second call short-circuits), and
 * {@code setActiveAnimations()} then wipes the mesh's clip table. Anything animated on that mesh
 * (players, zombies, vehicles) renders invisible for the rest of the session. The reload only
 * misses the cache when it asks for a mesh key boot never loaded, so the failure is intermittent
 * and per-player. Waiting here closes the window.
 *
 * <p>Fail-soft: bounded by {@link #TIMEOUT_NANOS}, and any error permanently disables the wait so
 * vanilla behavior resumes.
 */
public final class ModelMeshReloadWait {

    public static final long TIMEOUT_NANOS = 60_000_000_000L;

    public static volatile boolean disabled;

    private ModelMeshReloadWait() {}

    public static void waitForMeshes() {
        if (disabled) {
            return;
        }
        try {
            ArrayList<AnimationsMesh> meshes = ScriptManager.instance.getAllAnimationsMeshes();
            int pending = countLoading(meshes);
            if (pending == 0) {
                return;
            }
            long start = System.nanoTime();
            while (countLoading(meshes) > 0) {
                if (System.nanoTime() - start > TIMEOUT_NANOS) {
                    StormLogger.LOGGER.warn(
                            "Gave up waiting for animation meshes after {} ms; still loading: {}",
                            TIMEOUT_NANOS / 1_000_000L,
                            loadingNames(meshes));
                    return;
                }
                GameWindow.fileSystem.updateAsyncTransactions();
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!GameServer.server) {
                    Core.getInstance().StartFrame();
                    Core.getInstance().EndFrame();
                    Core.getInstance().StartFrameUI();
                    Core.getInstance().EndFrameUI();
                }
            }
            StormLogger.LOGGER.info(
                    "Waited {} ms for {} animation mesh(es) to load before mod animations",
                    (System.nanoTime() - start) / 1_000_000L,
                    pending);
        } catch (Throwable t) {
            disabled = true;
            StormLogger.LOGGER.error("ModelMeshReloadWait disabled after error", t);
        }
    }

    private static int countLoading(ArrayList<AnimationsMesh> meshes) {
        int n = 0;
        for (AnimationsMesh am : meshes) {
            if (isLoading(am)) {
                n++;
            }
        }
        return n;
    }

    private static boolean isLoading(AnimationsMesh am) {
        return am.modelMesh != null && !am.modelMesh.isFailure() && !am.modelMesh.isReady();
    }

    private static String loadingNames(ArrayList<AnimationsMesh> meshes) {
        StringBuilder sb = new StringBuilder();
        for (AnimationsMesh am : meshes) {
            if (isLoading(am)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(am.meshFile);
            }
        }
        return sb.toString();
    }
}
