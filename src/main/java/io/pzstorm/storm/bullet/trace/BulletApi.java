package io.pzstorm.storm.bullet.trace;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The JNI surface as data: every native of {@link BulletBackend} and every upcall of {@link
 * UpcallHandler}, in a stable order (natives sorted by name+descriptor, then upcalls in declaration
 * order of the game). Trace files carry their own table, so this order is not part of the format.
 */
public final class BulletApi {

    public static final List<Sig> NATIVES;
    public static final List<Sig> UPCALLS;
    public static final List<Sig> ALL;

    public static final Sig UPDATE_PHYSICS_FOR_LEVEL_IF_NEEDED;
    public static final Sig ON_VEHICLE_CONSTRAINT_IMPULSE;
    public static final Sig NATIVE_LOG;
    public static final Sig GET_BONE_NAME;
    public static final Sig GET_BONE_ORDINAL;

    private static final Map<String, Sig> BY_KEY = new HashMap<>();
    private static final Map<Method, Sig> BY_METHOD = new HashMap<>();

    static {
        List<Method> natives = new ArrayList<>(Arrays.asList(BulletBackend.class.getMethods()));
        natives.removeIf(
                m -> m.isDefault() || java.lang.reflect.Modifier.isStatic(m.getModifiers()));
        natives.sort(Comparator.comparing(m -> m.getName() + Sig.descriptorOf(m)));
        String[] upcallOrder = {
            "updatePhysicsForLevelIfNeeded",
            "onVehicleConstraintImpulse",
            "nativeLog",
            "getBoneName",
            "getBoneOrdinal"
        };
        List<Sig> all = new ArrayList<>();
        List<Sig> nat = new ArrayList<>();
        List<Sig> up = new ArrayList<>();
        for (Method m : natives) {
            Sig s = new Sig(all.size(), false, m);
            all.add(s);
            nat.add(s);
        }
        for (String name : upcallOrder) {
            Method m =
                    Arrays.stream(UpcallHandler.class.getMethods())
                            .filter(x -> x.getName().equals(name))
                            .findFirst()
                            .orElseThrow();
            Sig s = new Sig(all.size(), true, m);
            all.add(s);
            up.add(s);
        }
        for (Sig s : all) {
            BY_KEY.put((s.upcall ? "^" : "") + s.key(), s);
            BY_METHOD.put(s.method, s);
        }
        NATIVES = Collections.unmodifiableList(nat);
        UPCALLS = Collections.unmodifiableList(up);
        ALL = Collections.unmodifiableList(all);
        UPDATE_PHYSICS_FOR_LEVEL_IF_NEEDED = up.get(0);
        ON_VEHICLE_CONSTRAINT_IMPULSE = up.get(1);
        NATIVE_LOG = up.get(2);
        GET_BONE_NAME = up.get(3);
        GET_BONE_ORDINAL = up.get(4);
    }

    private BulletApi() {}

    /** The native with this name and descriptor, or null. */
    public static Sig nativeSig(String name, String descriptor) {
        return BY_KEY.get(name + descriptor);
    }

    /** The upcall with this name and descriptor, or null. */
    public static Sig upcallSig(String name, String descriptor) {
        return BY_KEY.get("^" + name + descriptor);
    }

    /** The native or upcall a {@link BulletBackend} / {@link UpcallHandler} method stands for. */
    public static Sig of(Method m) {
        Sig s = BY_METHOD.get(m);
        if (s != null) {
            return s;
        }
        return BY_KEY.get(
                (m.getDeclaringClass() == UpcallHandler.class ? "^" : "")
                        + m.getName()
                        + Sig.descriptorOf(m));
    }

    /** Native lookup by name + parameter types (for mapping a game {@code Bullet} method). */
    public static Sig nativeFor(Method m) {
        return nativeSig(m.getName(), Sig.descriptorOf(m));
    }
}
