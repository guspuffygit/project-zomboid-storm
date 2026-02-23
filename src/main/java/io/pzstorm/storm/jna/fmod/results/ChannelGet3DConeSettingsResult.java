package io.pzstorm.storm.jna.fmod.results;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class ChannelGet3DConeSettingsResult {
    private int result;
    private float insideConeAngle;
    private float outsideConeAngle;
    private float outsideVolume;
}
