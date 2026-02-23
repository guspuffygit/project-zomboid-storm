package io.pzstorm.storm.event.lua;

import io.pzstorm.storm.event.core.LuaEvent;
import zombie.radio.devices.WaveSignalDevice;

@SuppressWarnings({"WeakerAccess", "unused"})
public class OnDeviceTextEvent implements LuaEvent {

    // TODO: document this event
    public final WaveSignalDevice device;
    public final String text1;
    public final String text2;
    public final String text3;
    public final Float x;
    public final Float y;
    public final Float z;

    public OnDeviceTextEvent(
            String text1,
            String text2,
            Float x,
            Float y,
            Float z,
            String text3,
            WaveSignalDevice device) {
        this.text1 = text1;
        this.x = x;
        this.y = y;
        this.z = z;
        this.text2 = text2;
        this.text3 = text3;
        this.device = device;
    }
}
