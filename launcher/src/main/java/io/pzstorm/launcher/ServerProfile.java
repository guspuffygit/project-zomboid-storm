package io.pzstorm.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One saved server, composed from two stores (see {@link ServerStore}): connection info and
 * credentials come from the game's own saved-servers database — the single source of truth, shared
 * with the in-game server browser — and launcher-only extras from launcher.json. Passwords are
 * never written to launcher.json; {@link #toMap} persists only the extras plus the
 * host/port/username join key (and the name, as a display fallback for when the database is
 * unreachable).
 */
public final class ServerProfile {

    public String name = "";
    public String host = "";
    public int port = 16261;

    /** Server ACCESS password ({@code +password}); lives in the game database only. */
    public String serverPassword = "";

    /** In-game account credentials for full auto-join (handed to Storm's client Java). */
    public String username = "";

    /** Lives in the game database only, and only when the user opted into saving it. */
    public String accountPassword = "";

    /**
     * Row ids in the game database, filled on read/write so edits update in place; -1 = not yet
     * known. Never persisted — ids may change under the game's own compaction.
     */
    public int dbServerId = -1;

    public int dbAccountId = -1;

    /**
     * True once this profile has a row in the game database. Persisted: it is how a launcher.json
     * entry with no matching database row is told apart — {@code true} means the user deleted the
     * server in-game (drop the entry), {@code false} means it was added while the database was
     * unreachable (write it there on the next sync).
     */
    public boolean inGameDb = false;

    /** Fill + submit the vanilla connect popup via Storm's client Java. */
    public boolean autoConnect = false;

    /** Pre-update the server's Steam workshop items before launching. */
    public boolean updateWorkshopMods = true;

    public List<String> extraVmArgs = new ArrayList<>();

    public String connectAddress() {
        return host + ":" + port;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("host", host);
        map.put("port", (long) port);
        map.put("username", username);
        map.put("autoConnect", autoConnect);
        map.put("updateWorkshopMods", updateWorkshopMods);
        map.put("extraVmArgs", new ArrayList<Object>(extraVmArgs));
        map.put("inGameDb", inGameDb);
        return map;
    }

    public static ServerProfile fromMap(Map<String, Object> map) {
        ServerProfile p = new ServerProfile();
        p.name = str(map.get("name"), "");
        p.host = str(map.get("host"), "");
        p.port = (int) num(map.get("port"), 16261);
        // password fields are read but never written back: a pre-single-source-of-truth
        // launcher.json still carries them, and the first sync migrates them into the game database
        p.serverPassword = str(map.get("serverPassword"), "");
        p.username = str(map.get("username"), "");
        p.accountPassword = str(map.get("accountPassword"), "");
        p.autoConnect = bool(map.get("autoConnect"), false);
        p.updateWorkshopMods = bool(map.get("updateWorkshopMods"), true);
        p.inGameDb = bool(map.get("inGameDb"), false);
        Object args = map.get("extraVmArgs");
        if (args instanceof List) {
            for (Object arg : (List<?>) args) {
                if (arg != null && !String.valueOf(arg).isEmpty()) {
                    p.extraVmArgs.add(String.valueOf(arg));
                }
            }
        }
        return p;
    }

    static String str(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    static long num(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @Override
    public String toString() {
        String label = name.isEmpty() ? connectAddress() : name;
        String character = username.isEmpty() ? "" : " — " + username;
        return label + character + "  (" + connectAddress() + ")";
    }
}
