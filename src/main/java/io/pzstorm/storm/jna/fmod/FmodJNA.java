package io.pzstorm.storm.jna.fmod;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.PointerByReference;
import fmod.javafmodJNI;
import io.pzstorm.storm.jna.fmod.ctypes.FMOD_VECTOR;
import io.pzstorm.storm.jna.fmod.models.FmodVector;
import io.pzstorm.storm.jna.fmod.results.*;
import java.util.List;

public class FmodJNA {
    // The fmod version calculated (2.2.6 -> 2.02.06)
    private static final int FMOD_VERSION = 0x00020206;

    public static int FMOD_System_Update(long systemHandle) {
        LOGGER.trace("FMOD_System_CreateSound({})", systemHandle);

        int result = FmodRawLibrary.instance.FMOD_System_Update(systemHandle);
        LOGGER.trace("FMOD_System_CreateSound({}) -> {}", systemHandle, result);

        return result;
    }

    public static long FMOD_System_CreateSound(long systemHandle, String nameOrData, long flags) {
        LOGGER.debug("FMOD_System_CreateSound({}, {}, {})", systemHandle, nameOrData, flags);

        PointerByReference sound = new PointerByReference();

        int result =
                FmodRawLibrary.instance.FMOD_System_CreateSound(
                        systemHandle, nameOrData, flags, null, sound);

        long soundHandle = Pointer.nativeValue(sound.getValue());

        LOGGER.debug(
                "FMOD_System_CreateSound({}, {}, {}) -> {} - {}",
                systemHandle,
                nameOrData,
                flags,
                result,
                soundHandle);

        return soundHandle;
    }

    public static long FMOD_System_PlaySound(long systemHandle, long soundHandle, boolean paused) {
        LOGGER.debug("FMOD_System_PlaySound({}, {}, {})", systemHandle, soundHandle, paused);

        PointerByReference channelRef = new PointerByReference();

        int result =
                FmodRawLibrary.instance.FMOD_System_PlaySound(
                        systemHandle, soundHandle, 0, paused, channelRef);

        if (result != 0) {
            LOGGER.error("FMOD_System_PlaySound failed: {}", result);
        }

        long channel = Pointer.nativeValue(channelRef.getValue());

        LOGGER.debug(
                "FMOD_System_PlaySound({}, {}, {}) -> {} - {}",
                systemHandle,
                soundHandle,
                paused,
                result,
                channel);

        return channel;
    }

    public static int FMOD_System_Set3DSettings(
            long systemHandle, float dopplerScale, float distanceFactor, float rolloffScale) {
        LOGGER.debug(
                "FMOD_System_Set3DSettings({}, {}, {}, {})",
                systemHandle,
                dopplerScale,
                distanceFactor,
                rolloffScale);

        int result =
                FmodRawLibrary.instance.FMOD_System_Set3DSettings(
                        systemHandle, dopplerScale, distanceFactor, rolloffScale);

        LOGGER.debug(
                "FMOD_System_Set3DSettings({}, {}, {}, {}) -> {}",
                systemHandle,
                dopplerScale,
                distanceFactor,
                rolloffScale,
                result);

        return result;
    }

    public static int FMOD_Channel_Set3DAttributes(
            long channelHandlePtr, FmodVector position, FmodVector velocity) {
        FMOD_VECTOR nativePosition = FMOD_VECTOR.from(position);
        FMOD_VECTOR nativeVelocity = FMOD_VECTOR.from(velocity);
        LOGGER.debug(
                "FMOD_Channel_Set3DAttributes({}, {}, {})", channelHandlePtr, position, velocity);
        return FmodRawLibrary.instance.FMOD_Channel_Set3DAttributes(
                channelHandlePtr, nativePosition.getPointer(), nativeVelocity.getPointer());
    }

    public static ChannelGet3DAttributesResult FMOD_Channel_Get3DAttributes(long channelHandlePtr) {
        FMOD_VECTOR nativePosition = new FMOD_VECTOR();
        FMOD_VECTOR nativeVelocity = new FMOD_VECTOR();
        int result =
                FmodRawLibrary.instance.FMOD_Channel_Get3DAttributes(
                        channelHandlePtr, nativePosition.getPointer(), nativeVelocity.getPointer());
        return new ChannelGet3DAttributesResult(
                result, nativePosition.getValue(), nativeVelocity.getValue());
    }

    public static int FMOD_Channel_Set3DMinMaxDistance(
            long channelHandlePtr, float minDistance, float maxDistance) {
        LOGGER.debug(
                "FMOD_Channel_Set3DMinMaxDistance({}, {}, {})",
                channelHandlePtr,
                minDistance,
                maxDistance);

        int result =
                FmodRawLibrary.instance.FMOD_Channel_Set3DMinMaxDistance(
                        channelHandlePtr, minDistance, maxDistance);

        LOGGER.debug(
                "FMOD_Channel_Set3DMinMaxDistance({}, {}, {}) -> {}",
                channelHandlePtr,
                minDistance,
                maxDistance,
                result);

        return result;
    }

    public static ChannelGet3DMinMaxDistanceResult FMOD_Channel_Get3DMinMaxDistance(
            long channelHandlePtr) {
        FloatByReference minDistance = new FloatByReference();
        FloatByReference maxDistance = new FloatByReference();

        LOGGER.debug("FMOD_Channel_Get3DMinMaxDistance({})", channelHandlePtr);

        int result =
                FmodRawLibrary.instance.FMOD_Channel_Get3DMinMaxDistance(
                        channelHandlePtr, minDistance, maxDistance);

        LOGGER.debug(
                "FMOD_Channel_Get3DMinMaxDistance({}) -> minDistance: {}, maxDistance: {}",
                channelHandlePtr,
                minDistance.getValue(),
                maxDistance.getValue());

        return new ChannelGet3DMinMaxDistanceResult(
                result, minDistance.getValue(), maxDistance.getValue());
    }

    public static int FMOD_Channel_Set3DConeSettings(
            long channelHandlePtr,
            float insideConeAngle,
            float outsideConeAngle,
            float outsideVolume) {
        LOGGER.debug(
                "FMOD_Channel_Set3DConeSettings({}, {}, {}, {})",
                channelHandlePtr,
                insideConeAngle,
                outsideConeAngle,
                outsideVolume);
        int result =
                FmodRawLibrary.instance.FMOD_Channel_Set3DConeSettings(
                        channelHandlePtr, insideConeAngle, outsideConeAngle, outsideVolume);
        LOGGER.debug(
                "FMOD_Channel_Set3DConeSettings({}, {}, {}, {}) -> {}",
                channelHandlePtr,
                insideConeAngle,
                outsideConeAngle,
                outsideVolume,
                result);
        return result;
    }

    public static ChannelGet3DConeSettingsResult FMOD_Channel_Get3DConeSettings(
            long channelHandlePtr) {
        FloatByReference insideConeAngle = new FloatByReference();
        FloatByReference outsideConeAngle = new FloatByReference();
        FloatByReference outsideVolume = new FloatByReference();
        int result =
                FmodRawLibrary.instance.FMOD_Channel_Get3DConeSettings(
                        channelHandlePtr, insideConeAngle, outsideConeAngle, outsideVolume);

        ChannelGet3DConeSettingsResult coneSettingsResult =
                new ChannelGet3DConeSettingsResult(
                        result,
                        insideConeAngle.getValue(),
                        outsideConeAngle.getValue(),
                        outsideVolume.getValue());

        LOGGER.debug(
                "FMOD_Channel_Get3DConeSettings({}) -> insideConeAngle: {}, outsideConeAngle: {}, outsideVolume: {}",
                channelHandlePtr,
                coneSettingsResult.getInsideConeAngle(),
                coneSettingsResult.getOutsideConeAngle(),
                coneSettingsResult.getOutsideVolume());
        return coneSettingsResult;
    }

    public static int FMOD_Channel_Set3DConeOrientation(long channelHandlePtr, FmodVector vector) {
        FMOD_VECTOR nativeVector = FMOD_VECTOR.from(vector);

        LOGGER.debug("FMOD_Channel_Set3DConeOrientation({}, {})", channelHandlePtr, vector);

        int result =
                FmodRawLibrary.instance.FMOD_Channel_Set3DConeOrientation(
                        channelHandlePtr, nativeVector.getPointer());

        LOGGER.debug(
                "FMOD_Channel_Set3DConeOrientation({}, {}) -> {}",
                channelHandlePtr,
                vector,
                result);

        return result;
    }

    public static ChannelGet3DConeOrientationResult FMOD_Channel_Get3DConeOrientation(
            long channelHandlePtr) {
        FMOD_VECTOR nativeVector = new FMOD_VECTOR();

        LOGGER.debug("FMOD_Channel_Get3DConeOrientation({})", channelHandlePtr);

        int result =
                FmodRawLibrary.instance.FMOD_Channel_Get3DConeOrientation(
                        channelHandlePtr, nativeVector.getPointer());

        FmodVector vector = nativeVector.getValue();
        LOGGER.debug(
                "FMOD_Channel_Get3DConeOrientation({}) -> {} - {}",
                channelHandlePtr,
                result,
                vector);

        return new ChannelGet3DConeOrientationResult(result, vector);
    }

    public static int FMOD_Channel_Set3DCustomRolloff(
            long channelHandlePtr, List<FmodVector> points) {
        FMOD_VECTOR head = new FMOD_VECTOR();
        FMOD_VECTOR[] nativeArray = (FMOD_VECTOR[]) head.toArray(points.size());
        for (int i = 0; i < nativeArray.length; i++) {
            nativeArray[i].setX(points.get(i).getX()); // Distance
            nativeArray[i].setY(points.get(i).getY()); // Volume
            nativeArray[i].setZ(0); // Z must be 0 per docs [cite: 522]
            nativeArray[i].write();
        }

        long pointsPtr = Pointer.nativeValue(head.getPointer());

        return javafmodJNI.FMOD_Channel_Set3DCustomRolloff(
                channelHandlePtr, pointsPtr, points.size());
    }

    public static long FMOD_Studio_Create() {
        return javafmodJNI.FMOD_Studio_Create();
    }

    public static long FMOD_System_Create() {
        PointerByReference system = new PointerByReference();

        int result = FmodRawLibrary.instance.FMOD_System_Create(system, FMOD_VERSION);
        if (result != 0) {
            LOGGER.error("FMOD system failed to start: {}", result);
        }

        long systemHandle = Pointer.nativeValue(system.getValue());

        LOGGER.debug("New FMOD System created with systemHandle: {}", systemHandle);

        return systemHandle;
    }

    public static int FMOD_System_Init(long systemHandle, int maxChannels, long flags) {
        LOGGER.debug("FMOD_System_Init({}, {}, {})", systemHandle, maxChannels, flags);
        int result =
                FmodRawLibrary.instance.FMOD_System_Init(systemHandle, maxChannels, flags, null);

        LOGGER.debug(
                "FMOD_System_Init({}, {}, {}) -> {}", systemHandle, maxChannels, flags, result);
        return result;
    }

    public static SystemGet3DListenerAttributesResult FMOD_System_Get3DListenerAttributes(
            long systemHandle, int listener) {
        FMOD_VECTOR position = new FMOD_VECTOR();
        FMOD_VECTOR velocity = new FMOD_VECTOR();
        FMOD_VECTOR forward = new FMOD_VECTOR();
        FMOD_VECTOR up = new FMOD_VECTOR();

        LOGGER.debug("FMOD_System_Get3DListenerAttributes({}, {})", systemHandle, listener);

        int result =
                FmodRawLibrary.instance.FMOD_System_Get3DListenerAttributes(
                        systemHandle,
                        listener,
                        position.getPointer(),
                        velocity.getPointer(),
                        forward.getPointer(),
                        up.getPointer());

        LOGGER.debug(
                "FMOD_System_Get3DListenerAttributes({}, {}) -> {} - position {}, velocity {}, forward {}, up {}",
                systemHandle,
                listener,
                result,
                position.getValue(),
                velocity.getValue(),
                forward.getValue(),
                up.getValue());

        return new SystemGet3DListenerAttributesResult(
                result,
                position.getValue(),
                velocity.getValue(),
                forward.getValue(),
                up.getValue());
    }

    public static SystemGet3DListenerAttributesResult FMOD_System_Set3DListenerAttributes(
            long systemHandle,
            int listener,
            FmodVector position,
            FmodVector velocity,
            FmodVector forward,
            FmodVector up) {
        FMOD_VECTOR nativePosition = FMOD_VECTOR.from(position);
        FMOD_VECTOR nativeVelocity = FMOD_VECTOR.from(velocity);
        FMOD_VECTOR nativeForward = FMOD_VECTOR.from(forward);
        FMOD_VECTOR nativeUp = FMOD_VECTOR.from(up);

        LOGGER.trace(
                "FMOD_System_Set3DListenerAttributes({}, {}, {}, {}, {}, {})",
                systemHandle,
                listener,
                position,
                velocity,
                forward,
                up);

        int result =
                FmodRawLibrary.instance.FMOD_System_Set3DListenerAttributes(
                        systemHandle,
                        listener,
                        nativePosition.getPointer(),
                        nativeVelocity.getPointer(),
                        nativeForward.getPointer(),
                        nativeUp.getPointer());

        LOGGER.trace(
                "FMOD_System_Set3DListenerAttributes({}, {}) -> {} - position {}, velocity {}, forward {}, up {}",
                systemHandle,
                listener,
                result,
                nativePosition.getValue(),
                nativeVelocity.getValue(),
                nativeForward.getValue(),
                nativeUp.getValue());

        return new SystemGet3DListenerAttributesResult(
                result,
                nativePosition.getValue(),
                nativeVelocity.getValue(),
                nativeForward.getValue(),
                nativeUp.getValue());
    }
}
