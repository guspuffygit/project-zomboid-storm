package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.OnPacketReceived;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnServerStartedEvent;
import io.pzstorm.storm.event.zomboid.OnPacketReceivedEvent;
import zombie.characters.Role;
import zombie.characters.Roles;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.ServerWorldDatabase;
import zombie.network.packets.INetworkPacket;

/**
 * Pins every custom (non-read-only) role to the same position as the built-in {@code user} role
 * (2000).
 *
 * <p>Vanilla {@code Roles.updatePositions()} renumbers custom roles {@code 0..N} on every {@code
 * Roles.save()}, placing them strictly below {@code user}. {@code PlayerPacket}'s relay then takes
 * the "higher-ranked observer" bypass for every peer whose role position is strictly greater than
 * the sender's, so a single custom-role player gets relayed map-wide via {@code
 * PlayerUpdateReliable}, which in turn drives the {@code PlayerDataRequest} refusal loop. Equal
 * positions make the strict comparison false for every pair and the bypass never engages.
 *
 * <p>Positions only change through {@code Roles.save()} (first boot, every {@code RolesEditPacket}
 * edit, shutdown) and {@code Roles.addRole} (new roles spawn at {@code -1}, also via the packet),
 * so two hooks cover everything: {@link OnServerStartedEvent} corrects what the DB loaded verbatim
 * before any player joins, and the post-{@code processServer} {@code RolesEditPacket} event re-pins
 * after vanilla renumbered, persists the corrected positions, and re-syncs clients.
 *
 * <p>Server-only; every step fails soft (log and move on).
 */
public final class RolePositionPin {

    private static final String USER_ROLE = "user";

    private RolePositionPin() {}

    @SubscribeEvent
    public static void onServerStarted(OnServerStartedEvent event) {
        if (!GameServer.server) {
            return;
        }
        int pinned = pin();
        if (pinned > 0) {
            LOGGER.info("RolePositionPin: pinned {} custom role(s) to the user position", pinned);
        }
    }

    @OnPacketReceived("RolesEditPacket")
    public static void onRolesEdit(OnPacketReceivedEvent event) {
        if (!GameServer.server) {
            return;
        }
        afterRolesEdit();
    }

    /**
     * Re-pin after a role edit; when anything moved, persist the corrected positions (vanilla
     * already wrote the renumbered ones) and re-send the role table so clients don't keep the
     * renumbered copy vanilla just broadcast.
     */
    static void afterRolesEdit() {
        int pinned = pin();
        if (pinned == 0) {
            return;
        }
        LOGGER.info("RolePositionPin: re-pinned {} custom role(s) after role edit", pinned);
        persist();
        resync();
    }

    /**
     * Set every non-read-only role's position to the {@code user} role's position.
     *
     * @return number of roles whose position changed; {@code 0} when nothing moved or the role
     *     table isn't initialised yet.
     */
    static int pin() {
        try {
            Role user = Roles.getRole(USER_ROLE);
            if (user == null) {
                LOGGER.warn("RolePositionPin: no '{}' role; skipping pin", USER_ROLE);
                return 0;
            }
            int userPos = user.getPosition();
            int changed = 0;
            for (Role r : Roles.getRoles()) {
                if (!r.isReadOnly() && r.getPosition() != userPos) {
                    r.setPosition(userPos);
                    changed++;
                }
            }
            return changed;
        } catch (Throwable t) {
            LOGGER.warn("RolePositionPin: pin failed", t);
            return 0;
        }
    }

    private static void persist() {
        try {
            ServerWorldDatabase db = ServerWorldDatabase.instance;
            if (db == null) {
                LOGGER.warn("RolePositionPin: ServerWorldDatabase not initialised; not persisting");
                return;
            }
            for (Role r : Roles.getRoles()) {
                if (!r.isReadOnly()) {
                    db.saveRole(r);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("RolePositionPin: persisting pinned role positions failed", t);
        }
    }

    private static void resync() {
        try {
            if (GameServer.udpEngine == null) {
                return;
            }
            INetworkPacket.sendToAll(PacketTypes.PacketType.Roles);
        } catch (Throwable t) {
            LOGGER.warn("RolePositionPin: re-sending Roles to clients failed", t);
        }
    }
}
