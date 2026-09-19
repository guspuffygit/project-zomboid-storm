package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.pz.jni.VehicleNatives;
import org.junit.jupiter.api.Test;

/** PZVehicleScript parsing (fromJava / next / findScript / definePhysicsMesh). */
class PZVehicleScriptTest implements UnitTest {

    /** 10 scalars, 2 wheels, 3 shapes (box, sphere, mesh-typed). */
    static float[] sampleScript() {
        return new float[] {
            2.0f,
            1200f,
            0.5f,
            30f,
            2.5f,
            3.5f,
            250f,
            0.4f,
            1.7f,
            2.2f, // scalars
            2, // wheelCount
            1,
            0.8f,
            -0.3f,
            1.4f,
            0.35f, // wheel 0 (front)
            0,
            -0.8f,
            -0.3f,
            -1.4f,
            0.4f, // wheel 1 (rear)
            3, // shapeCount
            1,
            0f,
            0.5f,
            0f,
            1.0f,
            0.5f,
            2.0f,
            10f,
            20f,
            30f, // box: offset, extents, rotate
            2,
            0f,
            1f,
            0f,
            0.75f, // sphere: offset, radius
            3,
            0f,
            0f,
            0f // mesh: offset only
        };
    }

    @Test
    void defaultsMatchConstructor() {
        PZVehicleScript s = new PZVehicleScript();
        assertEquals((double) 2.15f, s.modelScale);
        assertEquals(800.0, s.mass);
        assertEquals(4, s.wheelCount);
        assertEquals(0, s.shapeCount);
        assertEquals((double) 0.3f, s.wheels[3].radius);
        assertEquals((double) -0.59f, s.wheels[3].offset.z);
        assertFalse(s.wheels[3].front);
        assertTrue(s.wheels[0].front);
    }

    @Test
    void parsesFullScript() {
        PZVehicleScript s = PZVehicleScript.fromJava("vs-test-parse", sampleScript(), 41);
        assertNotNull(s);
        assertSame(s, PZVehicleScript.findScript("vs-test-parse"));
        assertEquals(2.0, s.modelScale);
        assertEquals(1200.0, s.mass);
        assertEquals((double) 0.4f, s.suspensionRestLength);
        assertEquals((double) 1.7f, s.wheelFriction);
        assertEquals((double) 2.2f, s.stoppingMovementForce);
        assertEquals(2, s.wheelCount);
        assertTrue(s.wheels[0].front);
        assertEquals((double) 0.8f, s.wheels[0].offset.x);
        assertEquals((double) 1.4f, s.wheels[0].offset.z);
        assertEquals(0.0, s.wheels[0].offset.w);
        assertEquals((double) 0.35f, s.wheels[0].radius);
        assertFalse(s.wheels[1].front);
        assertEquals((double) 0.4f, s.wheels[1].radius);
        assertEquals(3, s.shapeCount);
        assertEquals(1, s.shapes[0].type);
        assertEquals(0.5, s.shapes[0].offset.y);
        assertEquals(2.0, s.shapes[0].extents.z);
        assertEquals(30.0, s.shapes[0].rotate.z);
        assertEquals(2, s.shapes[1].type);
        assertEquals(0.75, s.shapes[1].radius);
        assertEquals(3, s.shapes[2].type);
    }

    @Test
    void truncatedScriptIsRejectedAndNotRegistered() {
        float[] full = sampleScript();
        assertNull(PZVehicleScript.fromJava("vs-test-trunc", full, 30));
        assertNull(PZVehicleScript.findScript("vs-test-trunc"));
    }

    @Test
    void redefiningReparsesInPlaceAndReturnsNull() {
        PZVehicleScript s = PZVehicleScript.fromJava("vs-test-redef", sampleScript(), 41);
        float[] changed = sampleScript();
        changed[1] = 999f;
        assertNull(PZVehicleScript.fromJava("vs-test-redef", changed, changed.length));
        assertSame(s, PZVehicleScript.findScript("vs-test-redef"));
        assertEquals(999.0, s.mass);
    }

    @Test
    void nextIntRoundsHalfAwayFromZero() {
        PZVehicleScript s = new PZVehicleScript();
        s.data = new float[] {2.5f, -2.5f, 2.49f, Float.NaN};
        s.len = 4;
        int[] v = new int[1];
        assertTrue(s.next(v));
        assertEquals(3, v[0]);
        assertTrue(s.next(v));
        assertEquals(-3, v[0]);
        assertTrue(s.next(v));
        assertEquals(2, v[0]);
        assertTrue(s.next(v));
        assertEquals(Integer.MIN_VALUE, v[0]);
        assertFalse(s.next(v));
    }

    @Test
    void definePhysicsMeshAppendsAndSetsType() {
        PZVehicleScript s = PZVehicleScript.fromJava("vs-test-mesh", sampleScript(), 41);
        float[] pts = {1, 2, 3, 4, 5, 6};
        s.definePhysicsMesh(0, 2, pts);
        assertEquals(3, s.shapes[0].type);
        assertEquals(6, s.shapes[0].mesh.size());
        s.definePhysicsMesh(0, 1, pts);
        assertEquals(9, s.shapes[0].mesh.size());
        // out-of-range index is ignored
        s.definePhysicsMesh(3, 1, pts);
        s.definePhysicsMesh(-1, 1, pts);
        assertEquals(0, s.shapes[3].mesh.size());
    }

    @Test
    void nativesScriptDefinition() {
        assertThrows(
                NullPointerException.class, () -> VehicleNatives.defineVehicleScript(null, null));
        VehicleNatives.defineVehicleScript("vs-test-native", sampleScript());
        assertNotNull(PZVehicleScript.findScript("vs-test-native"));
        VehicleNatives.defineVehiclePhysicsMesh(
                "vs-test-native", 2, new float[] {0, 0, 0, 1, 1, 1});
        assertEquals(6, PZVehicleScript.findScript("vs-test-native").shapes[2].mesh.size());
        RuntimeException e =
                assertThrows(
                        RuntimeException.class,
                        () -> VehicleNatives.defineVehiclePhysicsMesh("vs-none", 0, new float[3]));
        assertEquals("unknown vehicle script", e.getMessage());
        assertThrows(
                NullPointerException.class,
                () -> VehicleNatives.defineVehiclePhysicsMesh("vs-test-native", 0, null));
    }
}
