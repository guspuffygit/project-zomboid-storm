package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Called by UI Manager in its render function before the UI gets drawn.
 *
 * @see OnPostUIDrawEvent
 */
@SuppressWarnings("WeakerAccess")
public class OnPreUIDrawEvent implements LuaEvent {}
