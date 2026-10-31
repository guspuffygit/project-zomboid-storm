package io.pzstorm.storm.patch.fixes;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import sun.misc.Unsafe;
import zombie.core.properties.PropertyContainer;
import zombie.core.random.RandStandard;
import zombie.entity.Component;
import zombie.entity.ComponentType;
import zombie.entity.Engine;
import zombie.entity.GameEntity;
import zombie.entity.GameEntityManager;
import zombie.entity.components.fluids.FluidContainer;
import zombie.entity.util.LongMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.util.list.PZArrayList;

/**
 * The real {@code GameEntityManager}, {@code Engine} and {@code IsoObject.getEntityNetID} on one
 * square. {@link EntityIdCollisionPatchTest} defines this class in a loader of its own, once with
 * the vanilla manager and once with the woven one, and each public method returns what happened as
 * text, so the two runs can be compared without sharing a single game class.
 */
public final class EntityIdCollisionScenario {

    private static final int X = 6470;
    private static final int Y = 6480;
    private static final long K0 = X + ((long) Y << 16);
    private static final long K1 = K0 + (1L << 40);

    /** A floor as the id rule sees it: index 0, wherever it sits in the list. */
    private static final class Floor extends IsoObject {
        @Override
        public boolean isFloor() {
            return true;
        }
    }

    private final Engine engine = new Engine();
    private final IsoObject grass = new IsoObject();
    private final IsoObject crate = new IsoObject();
    private final IsoObject floor = new Floor();
    private final PZArrayList<IsoObject> objects;
    private final LongMap<GameEntity> map;
    private final List<String> log = new ArrayList<>();

    /**
     * {@code [grass, crate, floor]} with the crate and the floor registered, then the grass goes.
     */
    private EntityIdCollisionScenario() throws Exception {
        RandStandard.INSTANCE.init(); // this loader's GameClient reads it in its static init
        map = staticField("idToEntityMap");
        map.clear();
        setStatic("engine", engine);
        IsoGridSquare square = allocate(IsoGridSquare.class);
        objects = new PZArrayList<>(IsoObject.class, 3);
        field(IsoGridSquare.class, "objects").set(square, objects);
        field(IsoGridSquare.class, "properties").set(square, new PropertyContainer());
        square.x = X;
        square.y = Y;
        for (IsoObject o : List.of(grass, crate, floor)) {
            objects.add(o);
            o.square = square;
        }
        for (IsoObject o : List.of(crate, floor)) {
            addComponent(o, ComponentType.Script.CreateComponent());
        }
        register(crate);
        register(floor);
        objects.remove(grass);
        crate.getEntityNetID(); // the first read after the grass goes: "expected null", no entry
    }

    /** The rain barrel case: a component that wants to run in meta after the unload. */
    public static String barrelLeavesOverTheFloor() throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        FluidContainer barrel = FluidContainer.CreateContainer();
        field(FluidContainer.class, "rainCatcher").setFloat(barrel, 1.0F);
        addComponent(s.crate, barrel);
        s.unregister(s.crate);
        s.snapshot("barrel left");
        return s.result()
                + " keptFluid="
                + s.crate.hasComponent(ComponentType.FluidContainer)
                + " metaTag="
                + s.crate.hasComponent(ComponentType.MetaTag);
    }

    /** The floor went first, so the id is free and the barrel can offload the normal way. */
    public static String barrelLeavesWithTheIdFree() throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        FluidContainer barrel = FluidContainer.CreateContainer();
        field(FluidContainer.class, "rainCatcher").setFloat(barrel, 1.0F);
        addComponent(s.crate, barrel);
        s.unregister(s.floor);
        s.unregister(s.crate);
        s.snapshot("barrel left");
        return s.result()
                + " keptFluid="
                + s.crate.hasComponent(ComponentType.FluidContainer)
                + " metaTag="
                + s.crate.hasComponent(ComponentType.MetaTag);
    }

    /** Crate then floor, the order of a chunk unload's walk over {@code [crate, floor]}. */
    public static String crateLeavesThenFloor() throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        s.unregister(s.crate);
        s.snapshot("crate left");
        s.unregister(s.floor);
        s.snapshot("floor left");
        return s.result();
    }

    /** The floor goes first, so the crate finds no entry at all. */
    public static String floorLeavesThenCrate() throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        s.unregister(s.floor);
        s.snapshot("floor left");
        s.unregister(s.crate);
        s.snapshot("crate left");
        return s.result();
    }

    /** No collision: the crate alone on the square. */
    public static String crateAlone() throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        s.unregister(s.floor);
        s.objects.remove(s.floor);
        s.map.clear();
        s.map.put(K0, s.crate);
        s.snapshot("before");
        s.unregister(s.crate);
        s.snapshot("crate left");
        return s.result();
    }

    /** Vanilla's body fails after the swap: the occupant still has to get its entry back. */
    public static String engineGoneMidUnregister() throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        setStatic("engine", null);
        s.unregister(s.crate);
        s.snapshot("crate failed");
        return s.result();
    }

    /** Never registered: nothing may run for it, not even the id recompute. */
    public static String neverRegistered() throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        IsoObject plain = new IsoObject();
        s.objects.add(plain);
        plain.square = s.crate.square;
        s.unregister(plain);
        return s.result() + " plainId=" + field(IsoObject.class, "isoEntityNetId").get(plain);
    }

    /** Off the square, so the id reads -1. Whatever vanilla does, ours must match. */
    public static String offTheSquare() throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        s.objects.remove(s.crate);
        s.unregister(s.crate);
        s.snapshot("crate left");
        return s.result();
    }

    /** In the engine, but the manager already let go: vanilla returns at once. */
    public static String managerAlreadyLetGo(boolean occupied) throws Exception {
        EntityIdCollisionScenario s = new EntityIdCollisionScenario();
        if (!occupied) {
            s.unregister(s.floor);
        }
        field(GameEntity.class, "addedToEntityManager").setBoolean(s.crate, false);
        s.log.clear();
        s.unregister(s.crate);
        s.snapshot("crate call");
        return s.result();
    }

    /** The counters the guard feeds, as {@code collisions/missing}. */
    public static String counters() throws Exception {
        Class<?> metrics = Class.forName("io.pzstorm.storm.metrics.EntityIdCollisionMetrics");
        return count(metrics, "collisions") + "/" + count(metrics, "missingEntries");
    }

    private static long count(Class<?> metrics, String name) throws Exception {
        return field(metrics, name).getLong(null);
    }

    private static void addComponent(IsoObject object, Component component) throws Exception {
        Method add = GameEntity.class.getDeclaredMethod("addComponent", Component.class);
        add.setAccessible(true);
        add.invoke(object, component);
    }

    private void register(IsoObject object) throws Exception {
        Method add = Engine.class.getDeclaredMethod("addEntity", GameEntity.class);
        add.setAccessible(true);
        add.invoke(engine, object);
        map.put(object.getEntityNetID(), object);
        field(GameEntity.class, "addedToEntityManager").setBoolean(object, true);
    }

    private void unregister(IsoObject object) throws Exception {
        Method unregister =
                GameEntityManager.class.getDeclaredMethod(
                        "UnregisterEntity", GameEntity.class, boolean.class);
        unregister.setAccessible(true);
        try {
            unregister.invoke(null, object, true);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            log.add("threw " + cause.getClass().getSimpleName() + ":" + cause.getMessage());
        }
    }

    private void snapshot(String when) {
        log.add(
                when
                        + ": K0="
                        + name(map.get(K0))
                        + " K1="
                        + name(map.get(K1))
                        + " crateInEngine="
                        + crate.isAddedToEngine()
                        + " floorInEngine="
                        + floor.isAddedToEngine());
    }

    private String result() {
        return String.join(" | ", log);
    }

    private String name(GameEntity entity) {
        return entity == null
                ? "none"
                : entity == crate ? "crate" : entity == floor ? "floor" : "other";
    }

    @SuppressWarnings("unchecked")
    private static <T> T staticField(String name) throws Exception {
        return (T) field(GameEntityManager.class, name).get(null);
    }

    private static void setStatic(String name, Object value) throws Exception {
        field(GameEntityManager.class, name).set(null, value);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return type.cast(((Unsafe) theUnsafe.get(null)).allocateInstance(type));
    }
}
