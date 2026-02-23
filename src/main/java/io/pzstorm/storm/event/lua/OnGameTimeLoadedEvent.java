package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.GameTime;

/** Triggered on both client and server after {@link GameTime} has been loaded. */
@SuppressWarnings({"WeakerAccess", "unused"})
public class OnGameTimeLoadedEvent implements LuaEvent {}
