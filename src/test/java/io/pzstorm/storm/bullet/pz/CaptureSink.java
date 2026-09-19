package io.pzstorm.storm.bullet.pz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Records every {@link PhysicsDebugRenderer.DrawSink} call as a string (floats as hex bits). */
final class CaptureSink implements PhysicsDebugRenderer.DrawSink {

    final List<String> probes = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    final List<Object> receivers = new ArrayList<>();

    /** Method names whose GetMethodID fails. */
    final Set<String> missing = new HashSet<>();

    CaptureSink(String... missing) {
        this.missing.addAll(Arrays.asList(missing));
    }

    static String f(float v) {
        return Float.toString(v) + "#" + Integer.toHexString(Float.floatToRawIntBits(v));
    }

    static String line(String name, Object... args) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] instanceof Float fl ? f(fl) : String.valueOf(args[i]));
        }
        return sb.append(')').toString();
    }

    @Override
    public boolean getMethodID(Class<?> clazz, String name, String sig) {
        probes.add(name + sig);
        return !missing.contains(name);
    }

    @Override
    public void drawLine(
            Object obj,
            float fromX,
            float fromY,
            float fromZ,
            float toX,
            float toY,
            float toZ,
            float fromR,
            float fromG,
            float fromB,
            float toR,
            float toG,
            float toB) {
        receivers.add(obj);
        calls.add(
                line(
                        "drawLine",
                        fromX,
                        fromY,
                        fromZ,
                        toX,
                        toY,
                        toZ,
                        fromR,
                        fromG,
                        fromB,
                        toR,
                        toG,
                        toB));
    }

    @Override
    public void drawSphere(
            Object obj, float pX, float pY, float pZ, float radius, float r, float g, float b) {
        receivers.add(obj);
        calls.add(line("drawSphere", pX, pY, pZ, radius, r, g, b));
    }

    @Override
    public void drawTriangle(
            Object obj,
            float aX,
            float aY,
            float aZ,
            float bX,
            float bY,
            float bZ,
            float cX,
            float cY,
            float cZ,
            float r,
            float g,
            float b,
            float alpha) {
        receivers.add(obj);
        calls.add(line("drawTriangle", aX, aY, aZ, bX, bY, bZ, cX, cY, cZ, r, g, b, alpha));
    }

    @Override
    public void drawContactPoint(
            Object obj,
            float pointOnBX,
            float pointOnBY,
            float pointOnBZ,
            float normalOnBX,
            float normalOnBY,
            float normalOnBZ,
            float distance,
            int lifeTime,
            float r,
            float g,
            float b) {
        receivers.add(obj);
        calls.add(
                line(
                        "drawContactPoint",
                        pointOnBX,
                        pointOnBY,
                        pointOnBZ,
                        normalOnBX,
                        normalOnBY,
                        normalOnBZ,
                        distance,
                        lifeTime,
                        r,
                        g,
                        b));
    }

    @Override
    public void drawCapsule(
            Object obj,
            float radius,
            float halfHeight,
            int upAxis,
            float pX,
            float pY,
            float pZ,
            float rX,
            float rY,
            float rZ,
            float qZ,
            float r,
            float g,
            float b,
            float a) {
        receivers.add(obj);
        calls.add(
                line(
                        "drawCapsule",
                        radius,
                        halfHeight,
                        upAxis,
                        pX,
                        pY,
                        pZ,
                        rX,
                        rY,
                        rZ,
                        qZ,
                        r,
                        g,
                        b,
                        a));
    }
}
