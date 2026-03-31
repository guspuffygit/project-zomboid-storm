package io.pzstorm.storm.event.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.packet.PacketEvent;
import io.pzstorm.storm.event.zomboid.OnPacketReceivedEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import zombie.core.raknet.UdpConnection;

/**
 * Routes {@link OnPacketReceivedEvent} instances to handlers registered for specific packet class
 * names via {@link OnPacketReceived}.
 *
 * <p>This is the packet equivalent of {@link ClientCommandDispatcher}. When a patched packet's
 * {@code processServer} method is called, {@link #dispatchPacket(Object, UdpConnection)} creates an
 * {@link OnPacketReceivedEvent} and dispatches it through {@link StormEventDispatcher} (for {@link
 * SubscribeEvent} handlers) and then through this dispatcher (for {@link OnPacketReceived}
 * handlers).
 */
public class PacketEventDispatcher {

    private static final Map<String, Set<HandlerMethod>> HANDLER_REGISTRY = new HashMap<>();

    private static final String TYPED_EVENT_PACKAGE = "io.pzstorm.storm.event.packet.";

    /** Cache of packet simple name → typed event constructor. */
    private static final Map<String, Constructor<? extends PacketEvent>> TYPED_EVENT_CACHE =
            new ConcurrentHashMap<>();

    /** Packet simple names known to have no typed event class. */
    private static final Set<String> NO_TYPED_EVENT = ConcurrentHashMap.newKeySet();

    /**
     * All packet class names that should be patched with {@link
     * io.pzstorm.storm.patch.networking.PacketReceivedPatch}. Each class gets a Byte Buddy advice
     * on its {@code processServer} method.
     */
    public static final List<String> SUPPORTED_PACKETS =
            List.of(
                    "zombie.network.packets.AddBrokenGlassPacket",
                    "zombie.network.packets.AddExplosiveTrapPacket",
                    "zombie.network.packets.AddInventoryItemToContainerPacket",
                    "zombie.network.packets.AddItemToMapPacket",
                    "zombie.network.packets.AddTicketPacket",
                    "zombie.network.packets.AddUserlogPacket",
                    "zombie.network.packets.AddWarningPointPacket",
                    "zombie.network.packets.AddXpPacket",
                    "zombie.network.packets.BanUnbanUserActionPacket",
                    "zombie.network.packets.BodyDamageUpdatePacket",
                    "zombie.network.packets.BuildActionPacket",
                    "zombie.network.packets.EquipPacket",
                    "zombie.network.packets.ExtraInfoPacket",
                    "zombie.network.packets.FishingActionPacket",
                    "zombie.network.packets.GameCharacterAttachedItemPacket",
                    "zombie.network.packets.GeneralActionPacket",
                    "zombie.network.packets.GetModDataPacket",
                    "zombie.network.packets.HumanVisualPacket",
                    "zombie.network.packets.ItemStatsPacket",
                    "zombie.network.packets.ItemTransactionPacket",
                    "zombie.network.packets.NetTimedActionPacket",
                    "zombie.network.packets.NetworkUserActionPacket",
                    "zombie.network.packets.ObjectModDataPacket",
                    "zombie.network.packets.PVPEventsPacket",
                    "zombie.network.packets.PlayerXpPacket",
                    "zombie.network.packets.ReadAnnotedMapPacket",
                    "zombie.network.packets.RegisterZonePacket",
                    "zombie.network.packets.RemoveInventoryItemFromContainerPacket",
                    "zombie.network.packets.RemoveItemFromSquarePacket",
                    "zombie.network.packets.RemoveTicketPacket",
                    "zombie.network.packets.RemoveUserlogPacket",
                    "zombie.network.packets.RequestDataPacket",
                    "zombie.network.packets.RequestItemsForContainerPacket",
                    "zombie.network.packets.RequestLargeAreaZipPacket",
                    "zombie.network.packets.RequestNetworkUsersPacket",
                    "zombie.network.packets.RequestRolesPacket",
                    "zombie.network.packets.RequestTradingPacket",
                    "zombie.network.packets.RolesEditPacket",
                    "zombie.network.packets.SafetyPacket",
                    "zombie.network.packets.SledgehammerDestroyPacket",
                    "zombie.network.packets.StartFirePacket",
                    "zombie.network.packets.StopFirePacket",
                    "zombie.network.packets.SyncClothingPacket",
                    "zombie.network.packets.SyncCustomLightSettingsPacket",
                    "zombie.network.packets.SyncExtendedPlacementPacket",
                    "zombie.network.packets.SyncHandWeaponFieldsPacket",
                    "zombie.network.packets.SyncItemActivatedPacket",
                    "zombie.network.packets.SyncItemFieldsPacket",
                    "zombie.network.packets.SyncNonPvpZonePacket",
                    "zombie.network.packets.SyncPlayerAlarmClockPacket",
                    "zombie.network.packets.SyncThumpablePacket",
                    "zombie.network.packets.SyncVisualsPacket",
                    "zombie.network.packets.SyncWorldAlarmClockPacket",
                    "zombie.network.packets.SyncZonePacket",
                    "zombie.network.packets.TeleportPacket",
                    "zombie.network.packets.TeleportToHimUserActionPacket",
                    "zombie.network.packets.TeleportUserActionPacket",
                    "zombie.network.packets.TradingUIAddItemPacket",
                    "zombie.network.packets.TradingUIRemoveItemPacket",
                    "zombie.network.packets.TradingUIUpdateStatePacket",
                    "zombie.network.packets.UpdateOverlaySpritePacket",
                    "zombie.network.packets.VariableSyncPacket",
                    "zombie.network.packets.ViewTicketsPacket",
                    "zombie.network.packets.ViewedTicketPacket",
                    "zombie.network.packets.WarStateSyncPacket",
                    "zombie.network.packets.WaveSignalPacket",
                    "zombie.network.packets.WorldMessagePacket",
                    "zombie.network.packets.actions.AnimalEventPacket",
                    "zombie.network.packets.actions.BurnCorpsePacket",
                    "zombie.network.packets.actions.EatFoodPacket",
                    "zombie.network.packets.actions.RemoveBloodPacket",
                    "zombie.network.packets.actions.SmashWindowPacket",
                    "zombie.network.packets.actions.SneezeCoughPacket",
                    "zombie.network.packets.actions.StatePacket",
                    "zombie.network.packets.actions.WakeUpPlayerPacket",
                    "zombie.network.packets.character.AnimalCommandPacket",
                    "zombie.network.packets.character.AnimalTracksPacket",
                    "zombie.network.packets.character.AnimalUpdatePacket",
                    "zombie.network.packets.character.CreatePlayerPacket",
                    "zombie.network.packets.character.ForageItemFoundPacket",
                    "zombie.network.packets.character.PlayerDataRequestPacket",
                    "zombie.network.packets.character.PlayerDropHeldItemsPacket",
                    "zombie.network.packets.character.PlayerPacket",
                    "zombie.network.packets.character.RemoveCorpseFromMapPacket",
                    "zombie.network.packets.character.ThumpPacket",
                    "zombie.network.packets.connection.GoogleAuthKeyPacket",
                    "zombie.network.packets.connection.GoogleAuthPacket",
                    "zombie.network.packets.connection.LoadPlayerProfilePacket",
                    "zombie.network.packets.connection.LoginPacket",
                    "zombie.network.packets.connection.LoginQueueDonePacket",
                    "zombie.network.packets.connection.QueuePacket",
                    "zombie.network.packets.connection.ServerCustomizationPacket",
                    "zombie.network.packets.hit.AttackCollisionCheckPacket",
                    "zombie.network.packets.hit.HitCharacter",
                    "zombie.network.packets.safehouse.SafehouseAcceptPacket",
                    "zombie.network.packets.safehouse.SafehouseChangeMemberPacket",
                    "zombie.network.packets.safehouse.SafehouseChangeOwnerPacket",
                    "zombie.network.packets.safehouse.SafehouseChangeRespawnPacket",
                    "zombie.network.packets.safehouse.SafehouseChangeTitlePacket",
                    "zombie.network.packets.safehouse.SafehouseClaimPacket",
                    "zombie.network.packets.safehouse.SafehouseInvitePacket",
                    "zombie.network.packets.safehouse.SafehouseReleasePacket",
                    "zombie.network.packets.safehouse.SafezoneClaimPacket",
                    "zombie.network.packets.service.PlayerInventoryPacket",
                    "zombie.network.packets.service.PopmanDebugCommandPacket",
                    "zombie.network.packets.service.ReceiveContainerModDataPacket",
                    "zombie.network.packets.service.ReceiveModDataPacket",
                    "zombie.network.packets.service.RecipePacket",
                    "zombie.network.packets.service.RequestUserLogPacket",
                    "zombie.network.packets.service.ScoreboardUpdatePacket",
                    "zombie.network.packets.service.ServerLOSPacket",
                    "zombie.network.packets.service.TimeSyncPacket",
                    "zombie.network.packets.sound.PlaySoundPacket",
                    "zombie.network.packets.sound.PlayWorldSoundPacket",
                    "zombie.network.packets.sound.StopSoundPacket",
                    "zombie.network.packets.sound.WorldSoundPacket",
                    "zombie.network.packets.vehicle.VehicleCollidePacket",
                    "zombie.network.packets.vehicle.VehicleEnterPacket",
                    "zombie.network.packets.vehicle.VehicleExitPacket",
                    "zombie.network.packets.vehicle.VehiclePassengerPositionPacket",
                    "zombie.network.packets.vehicle.VehiclePassengerRequestPacket",
                    "zombie.network.packets.vehicle.VehiclePhysicsPacket",
                    "zombie.network.packets.vehicle.VehicleRequestPacket",
                    "zombie.network.packets.vehicle.VehicleSwitchSeatPacket",
                    "zombie.network.packets.world.DebugStoryPacket");

    /** Clear all registries. Intended for use in tests only. */
    public static void reset() {
        HANDLER_REGISTRY.clear();
        TYPED_EVENT_CACHE.clear();
        NO_TYPED_EVENT.clear();
    }

    /**
     * Register a handler method annotated with {@link OnPacketReceived}. Called by {@link
     * StormEventDispatcher} during handler scanning.
     *
     * @param method the handler method.
     * @param handler the handler instance, or {@code null} for static methods.
     */
    static void registerHandler(Method method, @Nullable Object handler) {
        OnPacketReceived annotation = method.getAnnotation(OnPacketReceived.class);
        String packetClassName = annotation.value();

        HANDLER_REGISTRY
                .computeIfAbsent(packetClassName, k -> new HashSet<>())
                .add(new HandlerMethod(method, handler));

        LOGGER.debug(
                "Registered @OnPacketReceived handler: {}:{} -> {}",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                packetClassName);
    }

    /**
     * Called from {@link io.pzstorm.storm.patch.networking.PacketReceivedPatch} enter advice
     * <b>before</b> {@code processServer} runs. Constructs the typed {@link PacketEvent} subclass
     * (if one exists) and calls {@link PacketEvent#capturePreState()} so it can snapshot any game
     * state that will be mutated.
     *
     * @param packet the raw packet object.
     * @param connection the UDP connection.
     * @return the typed event with pre-state captured, or {@code null} if no typed event exists.
     */
    public static @Nullable Object createTypedEvent(Object packet, UdpConnection connection) {
        String simpleName = packet.getClass().getSimpleName();
        if (NO_TYPED_EVENT.contains(simpleName)) {
            return null;
        }

        Constructor<? extends PacketEvent> ctor = TYPED_EVENT_CACHE.get(simpleName);
        if (ctor == null) {
            ctor = resolveTypedEventConstructor(simpleName);
            if (ctor == null) {
                NO_TYPED_EVENT.add(simpleName);
                return null;
            }
            TYPED_EVENT_CACHE.put(simpleName, ctor);
        }

        try {
            PacketEvent typedEvent = ctor.newInstance(packet, connection);
            typedEvent.capturePreState();
            return typedEvent;
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to create typed packet event for {}", simpleName, e);
            return null;
        }
    }

    /**
     * Called from {@link io.pzstorm.storm.patch.networking.PacketReceivedPatch} exit advice
     * <b>after</b> {@code processServer} completes. Dispatches both the generic {@link
     * OnPacketReceivedEvent} and the pre-built typed {@link PacketEvent} (if one was created in the
     * enter advice).
     *
     * @param packet the raw packet object.
     * @param connection the UDP connection.
     * @param preBuiltEvent the typed event created by {@link #createTypedEvent}, or {@code null}.
     */
    public static void dispatchPacket(
            Object packet, UdpConnection connection, @Nullable Object preBuiltEvent) {
        // Dispatch the generic event (for @SubscribeEvent and @OnPacketReceived handlers)
        OnPacketReceivedEvent event = new OnPacketReceivedEvent(packet, connection);
        StormEventDispatcher.dispatchEvent(event);

        // Dispatch the typed event that was constructed before processServer ran
        if (preBuiltEvent instanceof PacketEvent typedEvent) {
            StormEventDispatcher.dispatchEvent(typedEvent);
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Constructor<? extends PacketEvent> resolveTypedEventConstructor(
            String packetSimpleName) {
        String eventClassName = TYPED_EVENT_PACKAGE + packetSimpleName + "Event";
        try {
            Class<?> eventClass =
                    Class.forName(
                            eventClassName, true, PacketEventDispatcher.class.getClassLoader());
            if (!PacketEvent.class.isAssignableFrom(eventClass)) {
                LOGGER.warn("Class {} does not extend PacketEvent, skipping", eventClassName);
                return null;
            }
            return ((Class<? extends PacketEvent>) eventClass)
                    .getConstructor(Object.class, UdpConnection.class);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            LOGGER.error(
                    "Typed event class {} missing required constructor(Object, UdpConnection)",
                    eventClassName);
            return null;
        }
    }

    /**
     * Dispatch an {@link OnPacketReceivedEvent} to any matching typed handlers registered via
     * {@link OnPacketReceived}.
     *
     * @param event the packet received event.
     */
    public static void dispatch(OnPacketReceivedEvent event) {
        Set<HandlerMethod> handlers = HANDLER_REGISTRY.get(event.packetClassName);
        if (handlers == null || handlers.isEmpty()) {
            return;
        }

        for (HandlerMethod handlerMethod : handlers) {
            LOGGER.trace("Dispatching packet event {} to handler", event.packetClassName);
            handlerMethod.invoke(event);
        }
    }

    private static class HandlerMethod {
        private final Method method;
        private final @Nullable Object handler;

        private HandlerMethod(Method method, @Nullable Object handler) {
            this.method = method;
            this.handler = handler;
        }

        private void invoke(OnPacketReceivedEvent event) {
            try {
                method.invoke(handler, event);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
