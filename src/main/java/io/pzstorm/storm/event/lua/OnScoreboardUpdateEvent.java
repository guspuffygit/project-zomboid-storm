package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import java.util.ArrayList;

/** Called when multiplayer scoreboard is updated. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnScoreboardUpdateEvent implements LuaEvent {

    public final ArrayList<String> usernames;
    public final ArrayList<String> displayNames;
    public final ArrayList<String> steamIds;

    public OnScoreboardUpdateEvent(
            ArrayList<String> usernames,
            ArrayList<String> displayNames,
            ArrayList<String> steamIds) {
        this.usernames = usernames;
        this.displayNames = displayNames;
        this.steamIds = steamIds;
    }
}
