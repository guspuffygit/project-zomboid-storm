package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;

/**
 * Called by UI Manager in its render function after the UI has been drawn.
 *
 * @see OnPreUIDrawEvent
 */
@SuppressWarnings("WeakerAccess")
public class OnPostUIDrawEvent implements LuaEvent {}
