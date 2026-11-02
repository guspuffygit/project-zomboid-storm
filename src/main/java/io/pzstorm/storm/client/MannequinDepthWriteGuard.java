package io.pzstorm.storm.client;

/**
 * Records whether {@code AnimatedModelTargetDepthPatch} has woven the {@code targetDepth} write in
 * {@code AnimatedModel.initRenderData} through the mannequin gate. The gate only skips {@code
 * setModelData} once this is set. The class references no game type, so the patch can touch it from
 * {@code transform()} while {@code AnimatedModel} itself is mid-load.
 */
public final class MannequinDepthWriteGuard {

    private static volatile boolean woven;

    private MannequinDepthWriteGuard() {}

    public static boolean isWoven() {
        return woven;
    }

    public static void setWoven(boolean value) {
        woven = value;
    }
}
