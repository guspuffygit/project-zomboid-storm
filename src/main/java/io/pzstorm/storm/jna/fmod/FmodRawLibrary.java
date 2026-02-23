package io.pzstorm.storm.jna.fmod;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.PointerByReference;

public interface FmodRawLibrary extends Library {
    FmodRawLibrary instance = Native.load(FmodUtils.getFmodPath(), FmodRawLibrary.class);

    int FMOD_System_Create(PointerByReference system, int headerversion);

    int FMOD_System_Init(long systemHandle, int maxChannels, long flags, Pointer extraDriverData);

    int FMOD_System_PlaySound(
            long systemHandle,
            long soundHandle,
            long channelGroup,
            boolean paused,
            PointerByReference channel);

    int FMOD_System_CreateSound(
            long systemHandle,
            String nameOrData,
            long modes,
            Pointer exInfo,
            PointerByReference soundOut);

    int FMOD_System_Update(long systemHandle);

    int FMOD_System_Release(long systemHandle);

    int FMOD_System_Set3DSettings(
            long systemHandle, float dopplerScale, float distanceFactor, float rolloffScale);

    int FMOD_System_Get3DListenerAttributes(
            long systemHandle,
            int listener,
            Pointer position,
            Pointer velocity,
            Pointer forward,
            Pointer up);

    int FMOD_System_Set3DListenerAttributes(
            long systemHandle,
            int listener,
            Pointer position,
            Pointer velocity,
            Pointer forward,
            Pointer up);

    int FMOD_Channel_Get3DAttributes(long channelHandle, Pointer pos, Pointer vel);

    int FMOD_Channel_Set3DAttributes(long channelHandle, Pointer pos, Pointer vel);

    int FMOD_Channel_Get3DConeSettings(
            long channelHandle,
            FloatByReference insideConeAngle,
            FloatByReference outsideConeAngle,
            FloatByReference outsideVolume);

    int FMOD_Channel_Set3DConeSettings(
            long channelHandle, float insideConeAngle, float outsideConeAngle, float outsideVolume);

    int FMOD_Channel_Get3DConeOrientation(long channelHandle, Pointer orientation);

    int FMOD_Channel_Set3DConeOrientation(long channelHandle, Pointer orientation);

    int FMOD_Channel_Get3DMinMaxDistance(
            long channelHandle, FloatByReference minDistance, FloatByReference maxDistance);

    int FMOD_Channel_Set3DMinMaxDistance(long channelHandle, float minDistance, float maxDistance);

    int FMOD_ChannelGroup_Get3DAttributes(long channelGroupHandle, Pointer pos, Pointer vel);

    int FMOD_ChannelGroup_Set3DAttributes(long channelGroupHandle, Pointer pos, Pointer vel);

    int FMOD_ChannelGroup_Get3DConeSettings(
            long channelGroupHandle,
            FloatByReference insideConeAngle,
            FloatByReference outsideConeAngle,
            FloatByReference outsideVolume);

    int FMOD_ChannelGroup_Set3DConeSettings(
            long channelGroupHandle,
            float insideConeAngle,
            float outsideConeAngle,
            float outsideVolume);

    int FMOD_ChannelGroup_Get3DConeOrientation(long channelGroupHandle, Pointer orientation);

    int FMOD_ChannelGroup_Set3DConeOrientation(long channelGroupHandle, Pointer orientation);

    int FMOD_ChannelGroup_Get3DMinMaxDistance(
            long channelGroupHandle, FloatByReference minDistance, FloatByReference maxDistance);

    int FMOD_ChannelGroup_Set3DMinMaxDistance(
            long channelGroupHandle, float minDistance, float maxDistance);
}
