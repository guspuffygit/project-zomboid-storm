package io.pzstorm.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One saved server. Account username/password are deliberately NOT stored here: the game client
 * cannot accept them on the command line — it reads them from its own saved-servers database and
 * pre-fills the connect dialog. The launcher only carries the server access password ({@code
 * +password}).
 */
public final class ServerProfile {

    public String name = "";
    public String host = "";
    public int port = 16261;

    /** Port of the server's Storm HTTP endpoint; 0 disables mod sync for this server. */
    public int stormHttpPort = 0;

    public String serverPassword = "";

    /** In-game account credentials for full auto-join (handed to Storm's client Java). */
    public String username = "";

    /** Only persisted when the user opts in; plain text in launcher.json. */
    public String accountPassword = "";

    /** Fill + submit the vanilla connect popup via Storm's client Java. */
    public boolean autoConnect = false;

    /** Pre-update the server's Steam workshop items before launching. */
    public boolean updateWorkshopMods = true;

    public boolean syncMods = true;
    public boolean noSteam = false;
    public List<String> extraVmArgs = new ArrayList<>();

    public String connectAddress() {
        return host + ":" + port;
    }

    /** Directory-safe identity of this server; survives profile renames. */
    public String serverKey() {
        return (host + "_" + port).replaceAll("[^A-Za-z0-9.\\-]", "_");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("host", host);
        map.put("port", (long) port);
        map.put("stormHttpPort", (long) stormHttpPort);
        map.put("serverPassword", serverPassword);
        map.put("username", username);
        map.put("accountPassword", accountPassword);
        map.put("autoConnect", autoConnect);
        map.put("updateWorkshopMods", updateWorkshopMods);
        map.put("syncMods", syncMods);
        map.put("noSteam", noSteam);
        map.put("extraVmArgs", new ArrayList<Object>(extraVmArgs));
        return map;
    }

    public static ServerProfile fromMap(Map<String, Object> map) {
        ServerProfile p = new ServerProfile();
        p.name = str(map.get("name"), "");
        p.host = str(map.get("host"), "");
        p.port = (int) num(map.get("port"), 16261);
        p.stormHttpPort = (int) num(map.get("stormHttpPort"), 0);
        p.serverPassword = str(map.get("serverPassword"), "");
        p.username = str(map.get("username"), "");
        p.accountPassword = str(map.get("accountPassword"), "");
        p.autoConnect = bool(map.get("autoConnect"), false);
        p.updateWorkshopMods = bool(map.get("updateWorkshopMods"), true);
        p.syncMods = bool(map.get("syncMods"), true);
        p.noSteam = bool(map.get("noSteam"), false);
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
        return label + "  (" + connectAddress() + ")";
    }
}
