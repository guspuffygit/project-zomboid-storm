package io.pzstorm.storm.mod;

import io.pzstorm.storm.event.zomboid.OnLoadModsEvent;
import java.util.HashSet;
import java.util.Set;

public class ActiveMods {

    private static final Set<String> activeMods = new HashSet<>();

    public static void onLoadMods(OnLoadModsEvent event) {
        activeMods.clear();
        activeMods.addAll(event.getActiveMods());
    }

    public static boolean isModActive(String modName) {
        return activeMods.contains(modName);
    }
}
