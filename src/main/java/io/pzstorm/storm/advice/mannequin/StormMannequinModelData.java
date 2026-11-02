package io.pzstorm.storm.advice.mannequin;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.visual.BaseVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;

/**
 * Client-side change gate for the {@code AnimatedModel.setModelData} call inside the per-frame
 * branch of {@code IsoMannequin.render}.
 *
 * <p>A mannequin that is not drawn from the chunk FBO cache renders through the translucent pass
 * every frame, and that branch calls {@code setModelData(humanVisual, itemVisuals)} on every call.
 * {@code setModelData} keeps its own copy of the visual, so its reference check never matches, and
 * it rebuilds the model instance, re-dresses it and marks the textures dirty. {@code renderMain}
 * then allocates a {@code ModelInstanceTextureCreator} and runs the whole texture pipeline again,
 * once per mannequin per frame.
 *
 * <p>Every write to a mannequin's visual state ends in {@code IsoMannequin.syncModel()}, which
 * {@link #markDirty(Object)} observes through exit advice. {@link #setModelData} runs the vanilla
 * call the first time it sees a model and again after each {@code syncModel}, and otherwise returns
 * at once. This is the discipline the {@code animate} branch already follows, where {@code
 * syncModel} is the only caller of {@code setModelData}.
 *
 * <p>Keeping one {@code ModelInstance} across frames means the main thread and the render thread
 * share it, where vanilla hands each frame a fresh pooled instance. Both threads already write
 * {@code renderRefCount} unsynchronised on a character's kept {@code ModelSlot} instance, and this
 * adds no new kind of risk to it. The field that needs handling is {@code
 * ModelInstance.targetDepth}: {@code AnimatedModel.initRenderData} resets it to 0.5 on the main
 * thread every frame, and {@code WorldModelCamera.Begin} writes the real depth on the render thread
 * just before the shader reads it. When the main-thread reset lands between those two, the
 * mannequin draws at the wrong depth for that frame and vanishes behind the floor. {@link
 * #setDefaultTargetDepth} stands in for the main-thread reset and skips it for instances the gate
 * keeps, so only the render thread touches the field. The gate stays vanilla until that
 * substitution is confirmed woven ({@link #setDepthWriteGuarded}).
 *
 * <p>Always on. Any throwable in the gate's own bookkeeping latches it off for the rest of the
 * process, after which the vanilla call runs every frame. {@link #setEnabled(boolean)} switches it
 * off for A/B checks and {@link #stats()} reports counters; both are reachable through Java eval.
 */
public final class StormMannequinModelData {

    private static final Object LOCK = new Object();
    private static final Set<Object> DIRTY = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Object> APPLIED = Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean disabled;
    private static boolean enabled = true;
    private static volatile boolean depthWriteGuarded;

    private static long applies;
    private static long skips;
    private static long depthWritesSkipped;

    private StormMannequinModelData() {}

    /** Exit hook of {@code IsoMannequin.syncModel()}; {@code model} is the mannequin's model. */
    public static void markDirty(Object model) {
        if (model == null || disabled) {
            return;
        }
        try {
            synchronized (LOCK) {
                DIRTY.add(model);
            }
        } catch (Throwable t) {
            latchOff(t);
        }
    }

    /** Substitute for {@code AnimatedModel.setModelData(BaseVisual, ItemVisuals)} in render. */
    public static void setModelData(AnimatedModel model, BaseVisual visual, ItemVisuals items) {
        if (!active()) {
            model.setModelData(visual, items);
            return;
        }
        boolean skip;
        try {
            synchronized (LOCK) {
                skip = !DIRTY.remove(model) && APPLIED.contains(model);
            }
        } catch (Throwable t) {
            latchOff(t);
            model.setModelData(visual, items);
            return;
        }
        if (skip) {
            skips++;
            return;
        }
        model.setModelData(visual, items);
        applies++;
        try {
            synchronized (LOCK) {
                APPLIED.add(model);
            }
        } catch (Throwable t) {
            latchOff(t);
        }
    }

    /**
     * Substitute for the {@code modelInstance.targetDepth = 0.5F} write inside {@code
     * AnimatedModel.initRenderData}, which runs on the main thread once per instance per frame.
     * Instances kept by the gate are left to the render thread, which sets the real depth in {@code
     * WorldModelCamera.Begin} before every draw.
     */
    public static void setDefaultTargetDepth(ModelInstance instance, float value) {
        if (active() && isKept(instance)) {
            depthWritesSkipped++;
            return;
        }
        instance.targetDepth = value;
    }

    private static boolean isKept(ModelInstance instance) {
        Object owner = instance.getOwner();
        if (owner == null) {
            return false;
        }
        try {
            synchronized (LOCK) {
                return APPLIED.contains(owner);
            }
        } catch (Throwable t) {
            latchOff(t);
            return false;
        }
    }

    private static boolean active() {
        return !disabled && enabled && depthWriteGuarded;
    }

    private static void latchOff(Throwable t) {
        disabled = true;
        LOGGER.error(
                "Storm: mannequin model-data gate failed — reverting to the vanilla per-frame"
                        + " setModelData for the rest of this session",
                t);
    }

    /** Set by {@code AnimatedModelTargetDepthPatch} once the depth-write substitution is woven. */
    public static void setDepthWriteGuarded(boolean value) {
        depthWriteGuarded = value;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static String stats() {
        int dirty;
        int applied;
        synchronized (LOCK) {
            dirty = DIRTY.size();
            applied = APPLIED.size();
        }
        return "applies="
                + applies
                + " skips="
                + skips
                + " depthWritesSkipped="
                + depthWritesSkipped
                + " dirty="
                + dirty
                + " applied="
                + applied
                + " enabled="
                + enabled
                + " depthWriteGuarded="
                + depthWriteGuarded
                + " disabled="
                + disabled;
    }
}
