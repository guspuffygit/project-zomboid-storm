package io.pzstorm.storm.patch.bullet;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.bullet.trace.BulletApi;
import io.pzstorm.storm.bullet.trace.Sig;
import io.pzstorm.storm.bullet.trace.TraceRecorder;
import io.pzstorm.storm.bullet.trace.TraceWriter;
import io.pzstorm.storm.core.StormVersion;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime half of the Bullet trace recorder ({@code -Dstorm.bullet.record=<path>}).
 *
 * <p>JNI binds a native method to the library loaded by its class's loader, and the library caches
 * the {@code jclass} of whichever class first calls it for its upcalls. So the recorder puts an
 * <em>isolated copy</em> of {@code zombie.core.physics.Bullet} in a child-first loader: that copy
 * loads the library and keeps its natives, while the game's own class has its natives rewritten
 * into Java wrappers ({@link BulletRecordPatch}) that record each call and forward it to the copy.
 * The copy's two upcalls ({@code updatePhysicsForLevelIfNeeded}, {@code
 * onVehicleConstraintImpulse}) are rewritten to {@link BulletRecordUpcalls}, which record them and
 * run the game class's real implementation, so any native calls the game makes from inside an
 * upcall are recorded nested under it. {@code DebugLog.nativeLog} and {@code
 * SkeletonBone.getBoneName/getBoneOrdinal}, which the library finds by name through the copy's
 * loader (and so reaches the game classes), get advice that records them while a native call is on
 * the thread's stack.
 *
 * <p>Fail-soft: every bytecode step runs at transform time, and on any failure the game class is
 * left untouched (no recording). Trace I/O errors stop recording and physics continues. What cannot
 * be recovered is a failure to define the prepared copy or load the library into it at runtime;
 * that surfaces exactly as a missing library would in the unpatched game.
 */
public final class BulletRecorder {

    public static final String PROPERTY = "storm.bullet.record";
    static final String BULLET = "zombie.core.physics.Bullet";

    private static volatile byte[] isolatedBytes;
    private static volatile Class<?> gameClass;
    private static volatile Class<?> isolatedClass;
    private static volatile TraceRecorder recorder;
    private static volatile boolean recorderOpened;
    private static volatile String libraryName;
    private static final Map<Method, Target> TARGETS = new ConcurrentHashMap<>();
    private static final Map<String, MethodHandle> GAME_UPCALLS = new ConcurrentHashMap<>();
    private static final Sig[] ADVICE_SIGS = {
        BulletApi.NATIVE_LOG, BulletApi.GET_BONE_NAME, BulletApi.GET_BONE_ORDINAL
    };

    static final int NATIVE_LOG = 0;
    static final int GET_BONE_NAME = 1;
    static final int GET_BONE_ORDINAL = 2;

    private record Target(Sig sig, MethodHandle handle) {}

    private BulletRecorder() {}

    /** Called by {@link BulletRecordPatch} once both class versions were built successfully. */
    static void prepare(byte[] isolated) {
        isolatedBytes = isolated;
    }

    static boolean isPrepared() {
        return isolatedBytes != null;
    }

    static byte[] preparedBytes() {
        return isolatedBytes;
    }

    // ---------------------------------------------------------------- game-class entry points

    /** Replaces the body of the game's {@code Bullet.loadLibrary(String)}. */
    public static void loadLibrary(Class<?> owner, String libName) throws Throwable {
        gameClass = owner;
        Class<?> iso = isolated();
        LOGGER.info(
                "[bullet-record] loading {} into an isolated Bullet copy for recording", libName);
        Method m = iso.getDeclaredMethod("loadLibrary", String.class);
        m.setAccessible(true);
        try {
            // the call must originate in the isolated class so the library binds to its loader
            m.invoke(null, libName);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        libraryName = libName;
        openRecorder();
    }

    /** Body of every former native of the game's {@code Bullet}. */
    public static Object call(Method gameMethod, Object[] args) throws Throwable {
        Target t = TARGETS.get(gameMethod);
        if (t == null) {
            t = target(gameMethod);
        }
        TraceRecorder r = recorderOpened ? recorder : openRecorder();
        if (r == null || t.sig == null) {
            return t.handle.invokeWithArguments(args);
        }
        MethodHandle h = t.handle;
        return r.call(t.sig, args, a -> h.invokeWithArguments(a));
    }

    // ---------------------------------------------------------------- upcalls

    /** Records an upcall of the isolated copy and runs the game class's implementation. */
    static Object upcall(Sig sig, Object[] args) throws Throwable {
        MethodHandle h = gameUpcall(sig);
        TraceRecorder r = recorder;
        if (r == null) {
            return h.invokeWithArguments(args);
        }
        return r.upcall(sig, args, a -> h.invokeWithArguments(a));
    }

    /** Advice entry for the name-resolved upcalls; true when an exit must follow. */
    public static boolean adviceEnter(int which, Object[] args) {
        TraceRecorder r = recorder;
        if (r == null || !r.isEnabled() || !r.insideNativeCall()) {
            return false;
        }
        return r.upcallEnter(ADVICE_SIGS[which], args);
    }

    public static void adviceExit(int which, Object ret, Throwable thrown) {
        TraceRecorder r = recorder;
        if (r != null) {
            r.upcallExit(ADVICE_SIGS[which], ret, thrown);
        }
    }

    // ---------------------------------------------------------------- internals

    private static Target target(Method gameMethod) throws ReflectiveOperationException {
        Class<?> owner = gameMethod.getDeclaringClass();
        if (gameClass == null) {
            gameClass = owner;
        }
        Method im =
                isolated().getDeclaredMethod(gameMethod.getName(), gameMethod.getParameterTypes());
        im.setAccessible(true);
        Sig sig = BulletApi.nativeFor(gameMethod);
        if (sig == null) {
            LOGGER.warn(
                    "[bullet-record] native {} is not in the trace API; forwarding it unrecorded",
                    gameMethod);
        }
        Target t = new Target(sig, MethodHandles.lookup().unreflect(im));
        TARGETS.put(gameMethod, t);
        return t;
    }

    private static MethodHandle gameUpcall(Sig sig) throws ReflectiveOperationException {
        MethodHandle h = GAME_UPCALLS.get(sig.name);
        if (h == null) {
            Class<?> owner = gameClass;
            if (owner == null) {
                throw new IllegalStateException("upcall before any Bullet native was called");
            }
            Method m = owner.getDeclaredMethod(sig.name, sig.method.getParameterTypes());
            m.setAccessible(true);
            h = MethodHandles.lookup().unreflect(m);
            GAME_UPCALLS.put(sig.name, h);
        }
        return h;
    }

    static synchronized Class<?> isolated() throws ClassNotFoundException {
        Class<?> c = isolatedClass;
        if (c != null) {
            return c;
        }
        byte[] bytes = isolatedBytes;
        if (bytes == null) {
            throw new IllegalStateException("isolated Bullet class was not prepared");
        }
        ClassLoader parent =
                gameClass != null
                        ? gameClass.getClassLoader()
                        : BulletRecorder.class.getClassLoader();
        c = Class.forName(BULLET, false, new IsolatedLoader(parent, bytes));
        isolatedClass = c;
        return c;
    }

    private static synchronized TraceRecorder openRecorder() {
        if (recorderOpened) {
            return recorder;
        }
        recorderOpened = true;
        String spec = System.getProperty(PROPERTY);
        if (spec == null || spec.isBlank()) {
            return null;
        }
        Path path = Path.of(resolvePath(spec));
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("source", "game");
        meta.put("library", String.valueOf(libraryName));
        meta.put("storm", StormVersion.getVersion());
        meta.put("side", sideName());
        meta.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        meta.put(
                "java",
                System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        meta.put("pid", Long.toString(ProcessHandle.current().pid()));
        meta.put("started", Instant.now().toString());
        try {
            TraceWriter w = TraceWriter.open(path, meta);
            TraceRecorder r = new TraceRecorder(w, msg -> LOGGER.error("[bullet-record] {}", msg));
            Runtime.getRuntime().addShutdownHook(new Thread(r::close, "storm-bullet-record-close"));
            recorder = r;
            LOGGER.info("[bullet-record] recording Bullet calls to {}", path.toAbsolutePath());
            return r;
        } catch (Exception e) {
            LOGGER.error("[bullet-record] cannot open {}; physics runs unrecorded", path, e);
            return null;
        }
    }

    /** {@code {pid}} and {@code {time}} tokens make a per-process file name. */
    static String resolvePath(String spec) {
        return spec.replace("{pid}", Long.toString(ProcessHandle.current().pid()))
                .replace(
                        "{time}",
                        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneOffset.UTC)
                                .format(Instant.now()));
    }

    private static String sideName() {
        try {
            return io.pzstorm.storm.util.StormEnv.isStormServer() ? "server" : "client";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** The currently open recorder, or null (tests and diagnostics). */
    static TraceRecorder recorder() {
        return recorder;
    }

    /**
     * Defines only {@code zombie.core.physics.Bullet} itself (child-first); everything else,
     * including the game's {@code DebugLog} and {@code SkeletonBone} the library looks up by name,
     * comes from the game's loader.
     */
    static final class IsolatedLoader extends ClassLoader {
        private final byte[] bytes;

        IsolatedLoader(ClassLoader parent, byte[] bytes) {
            super("storm-bullet-record", parent);
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (BULLET.equals(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        c = defineClass(name, bytes, 0, bytes.length);
                    }
                    return c;
                }
                if (name.startsWith("io.pzstorm.storm.patch.bullet.")) {
                    return BulletRecorder.class.getClassLoader().loadClass(name);
                }
                return super.loadClass(name, resolve);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return getParent() == null ? null : getParent().getResourceAsStream(name);
        }
    }
}
