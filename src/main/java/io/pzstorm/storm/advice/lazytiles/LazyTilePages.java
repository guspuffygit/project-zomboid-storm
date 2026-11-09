package io.pzstorm.storm.advice.lazytiles;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.GameWindow;
import zombie.asset.Asset;
import zombie.asset.AssetManager;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;
import zombie.core.textures.TextureIDAssetManager;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoTreeJumbo;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.objects.IsoTree;
import zombie.iso.sprite.IsoAnim;
import zombie.iso.sprite.IsoDirectionFrame;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Defers tile-pack page loads until a page is first drawn or a loaded chunk references it.
 *
 * <p>Vanilla loads every page of every pack at join because {@code LoadTileDefinitions} builds a
 * {@link Texture} per sprite and each constructor loads its page. A deferred page stays EMPTY with
 * {@code desiredState = READY}, so calling the real {@code startLoading} later finishes the vanilla
 * pipeline unchanged.
 *
 * <p>Main-thread state ({@link #IN_FLIGHT}, {@link #WAITING_LEVELS}) is touched only from {@link
 * GameWindow#gameThread}. Other threads hand requests over through {@link #REQUESTED}. Any failure
 * turns the feature off and releases every deferred page.
 */
public final class LazyTilePages {

    private static final String PACK_PREFIX = "@pack@/";

    /** Vanilla UI packs stay eager so menus and icons never flash the error texture. */
    private static final Set<String> EAGER_PACKS =
            Set.of(
                    "UI",
                    "UI2",
                    "IconsMoveables",
                    "RadioIcons",
                    "ApComUI",
                    "Mechanics",
                    "WeatherFx");

    /** Chunk FBOs that skipped an unready tile re-render at most this often. */
    private static final long INVALIDATE_INTERVAL_MS = 250L;

    private static final Map<Asset, Boolean> DEFERRED = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Asset> REQUESTED = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<Boolean> RELEASING = new ThreadLocal<>();
    private static final ArrayList<Asset> IN_FLIGHT = new ArrayList<>();
    private static final HashMap<IsoChunk, long[]> WAITING_LEVELS = new HashMap<>();
    private static final AtomicInteger DEFERRED_TOTAL = new AtomicInteger();
    private static final AtomicInteger RELEASED_TOTAL = new AtomicInteger();

    private static volatile boolean disabled;
    private static volatile Method startLoading;
    private static boolean completedSinceInvalidate;
    private static long lastInvalidateMs;

    private LazyTilePages() {}

    /** {@code TextureIDAssetManager.startLoading} entry; true skips the vanilla load. */
    public static boolean defer(Asset asset) {
        if (disabled || RELEASING.get() == Boolean.TRUE || !isLazyPage(asset)) {
            return false;
        }
        try {
            DEFERRED.put(asset, Boolean.TRUE);
            if (DEFERRED_TOTAL.incrementAndGet() == 1) {
                LOGGER.info("Lazy tile loading enabled; tile-pack pages load on first use");
            }
            return true;
        } catch (Throwable t) {
            fail("defer", t);
            return false;
        }
    }

    /** Requests the page behind {@code tex} if it is still deferred. Safe from any thread. */
    public static void request(Texture tex) {
        if (tex != null) {
            requestPage(tex.getTextureId());
        }
    }

    /** Requests {@code id} if it is a deferred page. Safe from any thread. */
    public static void requestPage(TextureID id) {
        if (id == null || !DEFERRED.containsKey(id)) {
            return;
        }
        if (Thread.currentThread() == GameWindow.gameThread) {
            release(id);
        } else if (DEFERRED.remove(id) != null) {
            REQUESTED.add(id);
        }
    }

    /**
     * {@code IsoSprite.performRenderFrame} entry; true skips drawing a tile whose page is still
     * loading, so the world shows nothing there for a few frames instead of the error texture.
     */
    public static boolean skipFrame(IsoSprite sprite, IsoObject obj, IsoDirections dir, int frame) {
        if (disabled) {
            return false;
        }
        try {
            Texture tex =
                    sprite.getTextureForFrame(frame, dir, obj != null && obj.isUseSnowSprite());
            if (!awaitPage(tex)) {
                return false;
            }
            if (obj != null && FBORenderChunkManager.instance.isCaching()) {
                waitForRedraw(obj.getSquare());
            }
            return true;
        } catch (Throwable t) {
            fail("skipFrame", t);
            return false;
        }
    }

    /**
     * {@code IsoTree.render} entry; true skips the tree until its pages load. {@code
     * FBORenderTrees} hands page ids straight to the GPU, so trees never reach {@link #skipFrame}
     * or {@code Texture.bind}. A cut-away jumbo tree also swaps in trunk and treetop sprites that
     * no chunk object references.
     */
    public static boolean skipTree(IsoTree tree) {
        if (disabled || tree == null) {
            return false;
        }
        try {
            IsoDirections dir = tree.getDir();
            boolean waiting = awaitTreeSprite(tree.getSprite(), dir, tree);
            ArrayList<IsoSpriteInstance> attached = tree.attachedAnimSprite;
            if (attached != null && !attached.isEmpty() && attached.get(0) != null) {
                waiting |= awaitTreeSprite(attached.get(0).parentSprite, dir, tree);
            }
            if (waiting && FBORenderChunkManager.instance.isCaching()) {
                waitForRedraw(tree.getSquare());
            }
            return waiting;
        } catch (Throwable t) {
            fail("skipTree", t);
            return false;
        }
    }

    /** {@code IsoChunk.loadInMainThread} exit; requests every page the chunk's objects draw. */
    public static void requestChunk(IsoChunk chunk) {
        if (disabled || chunk == null || chunk.squares == null) {
            return;
        }
        try {
            for (IsoGridSquare[] level : chunk.squares) {
                if (level == null) {
                    continue;
                }
                for (IsoGridSquare square : level) {
                    if (square != null) {
                        requestSquare(square);
                    }
                }
            }
        } catch (Throwable t) {
            fail("requestChunk", t);
        }
    }

    /** {@code FileSystemImpl.updateAsyncTransactions} entry; runs once per main-thread frame. */
    public static void drain() {
        if (Thread.currentThread() != GameWindow.gameThread) {
            return;
        }
        try {
            if (disabled) {
                releaseAll();
                return;
            }
            for (Asset asset = REQUESTED.poll(); asset != null; asset = REQUESTED.poll()) {
                startLoadingNow(asset);
            }
            reapFinished();
            invalidateWaitingLevels();
        } catch (Throwable t) {
            fail("drain", t);
        }
    }

    public static int deferredCount() {
        return DEFERRED.size();
    }

    public static int deferredTotal() {
        return DEFERRED_TOTAL.get();
    }

    public static int releasedTotal() {
        return RELEASED_TOTAL.get();
    }

    public static boolean isDisabled() {
        return disabled;
    }

    private static boolean isLazyPage(Asset asset) {
        if (asset == null || asset.getPath() == null) {
            return false;
        }
        String path = asset.getPath().getPath();
        if (path == null || !path.startsWith(PACK_PREFIX)) {
            return false;
        }
        int slash = path.indexOf('/', PACK_PREFIX.length());
        String pack =
                slash < 0
                        ? path.substring(PACK_PREFIX.length())
                        : path.substring(PACK_PREFIX.length(), slash);
        return !EAGER_PACKS.contains(pack);
    }

    /** Requests the page behind {@code tex}; true while that lazy page is still loading. */
    private static boolean awaitPage(Texture tex) {
        if (tex == null || tex.isReady()) {
            return false;
        }
        TextureID id = tex.getTextureId();
        if (id == null || id.isFailure() || !isLazyPage(id)) {
            return false;
        }
        request(tex);
        return true;
    }

    private static boolean awaitTreeSprite(IsoSprite sprite, IsoDirections dir, IsoTree tree) {
        if (sprite == null) {
            return false;
        }
        boolean waiting = awaitPage(sprite.getTextureForCurrentFrame(dir, tree));
        IsoTreeJumbo.TreeDescription jumbo = IsoTreeJumbo.Jumbos.get(sprite.name);
        if (jumbo != null) {
            waiting |= awaitPage(namedTexture(jumbo.trunk(), dir, tree));
            waiting |= awaitPage(namedTexture(jumbo.treetop(), dir, tree));
        }
        return waiting;
    }

    /** Looks the sprite up without {@code getSprite}, which adds a blank sprite on a miss. */
    private static Texture namedTexture(String name, IsoDirections dir, IsoTree tree) {
        IsoSprite sprite = name == null ? null : IsoSpriteManager.instance.namedMap.get(name);
        return sprite == null ? null : sprite.getTextureForCurrentFrame(dir, tree);
    }

    private static void requestSquare(IsoGridSquare square) {
        List<IsoObject> objects = square.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject obj = objects.get(i);
            if (obj == null) {
                continue;
            }
            requestSprite(obj.sprite);
            requestSprite(obj.overlaySprite);
            ArrayList<IsoSpriteInstance> attached = obj.attachedAnimSprite;
            if (attached != null) {
                for (int j = 0; j < attached.size(); j++) {
                    IsoSpriteInstance inst = attached.get(j);
                    if (inst != null) {
                        requestSprite(inst.parentSprite);
                    }
                }
            }
        }
    }

    private static void requestSprite(IsoSprite sprite) {
        if (sprite == null) {
            return;
        }
        request(sprite.texture);
        IsoAnim anim = sprite.currentAnim;
        if (anim == null) {
            return;
        }
        for (int i = 0; i < anim.frames.size(); i++) {
            IsoDirectionFrame frame = anim.frames.get(i);
            if (frame == null) {
                continue;
            }
            for (Texture tex : frame.directions) {
                request(tex);
            }
        }
    }

    private static void release(Asset asset) {
        if (DEFERRED.remove(asset) != null) {
            startLoadingNow(asset);
        }
    }

    private static void startLoadingNow(Asset asset) {
        if (!asset.isEmpty()) {
            return;
        }
        RELEASING.set(Boolean.TRUE);
        try {
            startLoadingMethod().invoke(TextureIDAssetManager.instance, asset);
            IN_FLIGHT.add(asset);
            RELEASED_TOTAL.incrementAndGet();
        } catch (Throwable t) {
            fail("startLoading", t);
        } finally {
            RELEASING.set(Boolean.FALSE);
        }
    }

    private static Method startLoadingMethod() throws NoSuchMethodException {
        Method method = startLoading;
        if (method == null) {
            method = AssetManager.class.getDeclaredMethod("startLoading", Asset.class);
            method.setAccessible(true);
            startLoading = method;
        }
        return method;
    }

    private static void reapFinished() {
        for (int i = IN_FLIGHT.size() - 1; i >= 0; i--) {
            if (!IN_FLIGHT.get(i).isEmpty()) {
                int last = IN_FLIGHT.size() - 1;
                IN_FLIGHT.set(i, IN_FLIGHT.get(last));
                IN_FLIGHT.remove(last);
                completedSinceInvalidate = true;
            }
        }
    }

    private static void waitForRedraw(IsoGridSquare square) {
        if (square == null || square.chunk == null) {
            return;
        }
        int bit = square.getZ() + 32;
        if (bit < 0 || bit > 63) {
            return;
        }
        long[] mask = WAITING_LEVELS.get(square.chunk);
        if (mask == null) {
            mask = new long[1];
            WAITING_LEVELS.put(square.chunk, mask);
        }
        mask[0] |= 1L << bit;
    }

    /**
     * A chunk FBO that skipped an unready tile keeps the gap until something redraws it. Once any
     * deferred page finishes, redraw every waiting level; tiles still loading skip again and
     * re-register.
     */
    private static void invalidateWaitingLevels() {
        if (WAITING_LEVELS.isEmpty()) {
            completedSinceInvalidate = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (!completedSinceInvalidate || now - lastInvalidateMs < INVALIDATE_INTERVAL_MS) {
            return;
        }
        lastInvalidateMs = now;
        completedSinceInvalidate = false;
        for (Map.Entry<IsoChunk, long[]> entry : WAITING_LEVELS.entrySet()) {
            long mask = entry.getValue()[0];
            for (int bit = 0; bit < 64; bit++) {
                if ((mask & (1L << bit)) != 0) {
                    entry.getKey()
                            .invalidateRenderChunkLevel(bit - 32, FBORenderChunk.DIRTY_REDRAW);
                }
            }
        }
        WAITING_LEVELS.clear();
    }

    private static void releaseAll() {
        for (Asset asset = REQUESTED.poll(); asset != null; asset = REQUESTED.poll()) {
            startLoadingNow(asset);
        }
        if (DEFERRED.isEmpty()) {
            return;
        }
        ArrayList<Asset> all = new ArrayList<>(DEFERRED.keySet());
        for (int i = 0; i < all.size(); i++) {
            release(all.get(i));
        }
        LOGGER.warn("Lazy tile loading off; released {} deferred pages", all.size());
    }

    private static void fail(String where, Throwable t) {
        if (!disabled) {
            disabled = true;
            LOGGER.error("Lazy tile loading failed in {}; falling back to eager loading", where, t);
        }
    }
}
