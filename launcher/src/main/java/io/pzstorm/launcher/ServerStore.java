package io.pzstorm.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Composes {@link LauncherConfig#servers} from its two stores: the game's saved-servers database
 * ({@link VanillaServerDb} — connection info and credentials, the source of truth) and
 * launcher.json (launcher-only extras: auto-connect, workshop pre-update, extra JVM args), joined
 * on host:port:username.
 *
 * <p>Reconciliation on {@link #load}: database rows win for everything they hold; a json entry with
 * no matching row is either migrated into the database (never marked {@code inGameDb} — a
 * pre-migration or offline-added entry) or dropped ({@code inGameDb} set — the user deleted the
 * server in-game). Add/edit/remove in the launcher write straight through to the database.
 *
 * <p>Everything fails soft: without the SQLite driver (no game install found) the launcher runs on
 * the json extras alone — the list still shows, joins still work, only stored credentials are
 * unavailable until the game dir is configured.
 */
public final class ServerStore {

    private ServerStore() {}

    /** Rebuilds {@code config.servers} from the game database + json extras. */
    public static void load(LauncherConfig config) {
        load(config, LauncherPaths.zomboidDir());
    }

    static void load(LauncherConfig config, Path zomboidDir) {
        Path dbFile = VanillaServerDb.databaseFile(zomboidDir);
        try (VanillaServerDb.BorrowedDriver borrowed = driver(config)) {
            if (borrowed == null) {
                Log.warn(
                        "SQLite driver unavailable (projectzomboid.jar not found) — using the"
                                + " launcher's own server list; stored credentials are in the"
                                + " game's database and unavailable this run.");
                return;
            }
            List<ServerProfile> composed = VanillaServerDb.readProfiles(dbFile, borrowed.driver);
            for (ServerProfile profile : composed) {
                healAccountPassword(config, profile, dbFile, borrowed);
            }
            for (ServerProfile entry : config.servers) {
                ServerProfile match = find(composed, entry);
                if (match != null) {
                    applyExtras(entry, match);
                } else if (!entry.inGameDb) {
                    hashAccountPassword(config, entry);
                    try {
                        VanillaServerDb.upsert(dbFile, borrowed.driver, entry);
                        Log.info("Migrated '" + entry + "' into the game's saved-server list.");
                    } catch (Exception e) {
                        Log.warn(
                                "Could not write '"
                                        + entry
                                        + "' to the game's saved-server list: "
                                        + e.getMessage());
                    }
                    composed.add(entry);
                } else {
                    Log.info(
                            "'"
                                    + entry
                                    + "' is no longer in the game's saved-server list —"
                                    + " dropping it from the launcher too.");
                }
            }
            config.servers.clear();
            config.servers.addAll(composed);
        } catch (Exception e) {
            Log.warn("Could not read the game's saved-server list: " + e.getMessage());
        }
    }

    /** Writes one profile through to the game database (best-effort; the json copy still saves). */
    public static void save(LauncherConfig config, ServerProfile profile) {
        save(config, profile, LauncherPaths.zomboidDir());
    }

    static void save(LauncherConfig config, ServerProfile profile, Path zomboidDir) {
        try (VanillaServerDb.BorrowedDriver borrowed = driver(config)) {
            if (borrowed == null) {
                Log.warn(
                        "SQLite driver unavailable — '"
                                + profile
                                + "' is kept in the launcher only and will be written to the"
                                + " game's saved-server list once the game install is found.");
                return;
            }
            if (!hashAccountPassword(config, profile)) {
                // a raw password in the database would fail every login (the game and the
                // server only ever compare the hashed form) — better to not store it at all
                Log.warn(
                        "Could not hash the account password for '"
                                + profile
                                + "' — not saving it; re-enter it once the game install is"
                                + " configured.");
                profile.accountPassword = "";
            }
            VanillaServerDb.upsert(
                    VanillaServerDb.databaseFile(zomboidDir), borrowed.driver, profile);
        } catch (Exception e) {
            Log.warn(
                    "Could not write '"
                            + profile
                            + "' to the game's saved-server list: "
                            + e.getMessage());
        }
    }

    /** Removes the profile from the config and from the game database. */
    public static void remove(LauncherConfig config, ServerProfile profile) {
        remove(config, profile, LauncherPaths.zomboidDir());
    }

    static void remove(LauncherConfig config, ServerProfile profile, Path zomboidDir) {
        config.servers.remove(profile);
        try (VanillaServerDb.BorrowedDriver borrowed = driver(config)) {
            if (borrowed == null) {
                Log.warn(
                        "SQLite driver unavailable — '"
                                + profile
                                + "' may reappear from the game's saved-server list.");
                return;
            }
            VanillaServerDb.delete(
                    VanillaServerDb.databaseFile(zomboidDir), borrowed.driver, profile);
        } catch (Exception e) {
            Log.warn(
                    "Could not remove '"
                            + profile
                            + "' from the game's saved-server list: "
                            + e.getMessage());
        }
    }

    /**
     * Database rows carry no launcher extras, so a matched json entry contributes them; its
     * credentials (if any linger from a pre-migration json) are dropped — the database won.
     */
    private static void applyExtras(ServerProfile entry, ServerProfile match) {
        match.autoConnect = entry.autoConnect;
        match.updateWorkshopMods = entry.updateWorkshopMods;
        match.extraVmArgs = new ArrayList<>(entry.extraVmArgs);
        if (match.name.isEmpty()) {
            match.name = entry.name;
        }
    }

    private static ServerProfile find(List<ServerProfile> profiles, ServerProfile entry) {
        for (ServerProfile profile : profiles) {
            if (profile.port == entry.port
                    && profile.host.equalsIgnoreCase(entry.host)
                    && profile.username.equalsIgnoreCase(entry.username)) {
                return profile;
            }
        }
        return null;
    }

    /**
     * Brings the profile's account password into the game's stored form ({@link PzPasswordHash}).
     * Plaintext can enter through the edit dialog or a legacy launcher.json; the game only ever
     * compares the hashed form, so it must never reach the database raw. Returns false when the
     * value is plaintext but hashing is unavailable.
     */
    private static boolean hashAccountPassword(LauncherConfig config, ServerProfile profile) {
        if (profile.accountPassword.isEmpty() || PzPasswordHash.isHashed(profile.accountPassword)) {
            return true;
        }
        String hashed = PzPasswordHash.hash(profile.accountPassword, gameJar(config));
        if (hashed == null) {
            return false;
        }
        profile.accountPassword = hashed;
        return true;
    }

    /**
     * Repairs a database row holding a raw password (written by launcher versions that predate
     * {@link PzPasswordHash}): the game itself only ever writes the hashed form, so a raw value can
     * only be the launcher's own doing — and it fails every login until rehashed.
     */
    private static void healAccountPassword(
            LauncherConfig config,
            ServerProfile profile,
            Path dbFile,
            VanillaServerDb.BorrowedDriver borrowed) {
        if (profile.accountPassword.isEmpty() || PzPasswordHash.isHashed(profile.accountPassword)) {
            return;
        }
        if (!hashAccountPassword(config, profile)) {
            Log.warn(
                    "Account password for '"
                            + profile
                            + "' is stored raw and could not be hashed — logins will fail until"
                            + " it is re-entered.");
            return;
        }
        try {
            VanillaServerDb.upsert(dbFile, borrowed.driver, profile);
            Log.info(
                    "Re-hashed the stored account password for '"
                            + profile
                            + "' (was written raw by an older launcher).");
        } catch (Exception e) {
            Log.warn(
                    "Could not write the re-hashed account password for '"
                            + profile
                            + "': "
                            + e.getMessage());
        }
    }

    private static Path gameJar(LauncherConfig config) {
        Path gameDir = config.resolveGameDir();
        return gameDir == null ? null : gameDir.resolve("projectzomboid.jar");
    }

    private static VanillaServerDb.BorrowedDriver driver(LauncherConfig config) {
        return VanillaServerDb.loadDriver(gameJar(config));
    }
}
