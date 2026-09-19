package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.BulletUpcalls;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btGeneric6DofConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btHingeConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btPoint2PointConstraint;
import io.pzstorm.storm.bullet.dynamics.vehicle.btWheelInfo;
import io.pzstorm.storm.bullet.libm.GlibcMath;
import io.pzstorm.storm.bullet.pz.jni.VehicleNatives;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PZVehicle + VehicleNatives against a real WorldSimulation and a stub upcall target. */
class PZVehicleTest implements UnitTest {

    private static final String SCRIPT = "pzv-test-car";
    private final List<String> logs = new ArrayList<>();
    private BulletUpcalls.Target savedTarget;
    private WorldSimulation ws;

    /** 10 scalars, 4 wheels, one box shape. */
    static float[] carScript() {
        return new float[] {
            2.0f, 1000f, 1.0f, 20f, 1.83f, 1.88f, 100f, 1.0f, 2.0f, 1.0f, // scalars
            4, // wheels
            1, 0.8f, -0.2f, 1.2f, 0.4f, 1, -0.8f, -0.2f, 1.2f, 0.4f, 0, 0.8f, -0.2f, -1.2f, 0.45f,
            0, -0.8f, -0.2f, -1.2f, 0.45f, 1, // shapes
            1, 0f, 0.3f, 0f, 1.0f, 0.5f, 2.0f, 0f, 0f, 0f
        };
    }

    @BeforeEach
    void setUp() {
        savedTarget = BulletUpcalls.target;
        BulletUpcalls.target =
                new BulletUpcalls.Target() {
                    @Override
                    public boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
                        return false;
                    }

                    @Override
                    public void onVehicleConstraintImpulse(int c, int a, int b, float i) {}

                    @Override
                    public void nativeLog(String a, String b, String c) {
                        logs.add(a + " " + b + " " + c);
                    }

                    @Override
                    public String getBoneName(int ordinal) {
                        return "";
                    }

                    @Override
                    public int getBoneOrdinal(String name) {
                        return -1;
                    }
                };
        VehicleNatives.defineVehicleScript(SCRIPT, carScript());
        ws = new WorldSimulation(0, 0, 100, 100, 1000, 2000, true);
        WorldSimulation.instance = ws;
        PZBullet.hack_vehicles.clear();
    }

    @AfterEach
    void tearDown() {
        WorldSimulation.instance = null;
        PZBullet.hack_vehicles.clear();
        BulletUpcalls.target = savedTarget;
    }

    private void addCar(int id, float x, float y) {
        VehicleNatives.addVehicle(id, x, y, 0.5f, 0f, 0f, 0f, 1f, SCRIPT);
    }

    @Test
    void addVehicleBuildsChassisAndWheels() {
        addCar(7, 1010.5f, 2020.25f);
        PZVehicle v = ws.m_vehicles.get(7);
        assertNotNull(v);
        assertEquals(7, v.m_id);
        assertEquals(4, v.m_vehicle.getNumWheels());
        btWheelInfo w0 = v.m_vehicle.getWheelInfo(0);
        btWheelInfo w3 = v.m_vehicle.getWheelInfo(3);
        assertTrue(w0.m_bIsFrontWheel);
        assertFalse(w3.m_bIsFrontWheel);
        assertEquals((double) 0.4f, w0.m_wheelsRadius);
        assertEquals((double) 0.45f, w3.m_wheelsRadius);
        assertEquals(2.0, w0.m_frictionSlip);
        // chassis in Bullet space: (x - offX, z, y - offY)
        assertEquals(10.5, v.m_carChassis.getWorldTransform().getOrigin().x);
        assertEquals(0.5, v.m_carChassis.getWorldTransform().getOrigin().y);
        assertEquals(20.25, v.m_carChassis.getWorldTransform().getOrigin().z);
        assertEquals(1, VehicleNatives.getVehicleCount());
        assertSame(v, PZBullet.hack_vehicles.get(0));
    }

    @Test
    void addVehicleErrors() {
        assertEquals(
                "scriptName is null",
                assertThrows(
                                NullPointerException.class,
                                () -> VehicleNatives.addVehicle(1, 0, 0, 0, 0, 0, 0, 1, null))
                        .getMessage());
        assertEquals(
                "unknown vehicle script",
                assertThrows(
                                RuntimeException.class,
                                () -> VehicleNatives.addVehicle(1, 0, 0, 0, 0, 0, 0, 1, "nope"))
                        .getMessage());
        WorldSimulation.instance = null;
        assertEquals(
                "WorldSimulation::instance is null",
                assertThrows(NullPointerException.class, () -> addCar(1, 0, 0)).getMessage());
        assertThrows(RuntimeException.class, VehicleNatives::getVehicleCount);
    }

    @Test
    void ownPhysicsRoundTrip() {
        addCar(3, 1005f, 2005f);
        float[] out = new float[11 + 16];
        assertEquals(0, VehicleNatives.getOwnVehiclePhysics(3, out));
        assertEquals(1005f, out[0]);
        assertEquals(2005f, out[1]);
        assertEquals(0.5f, out[2]);
        assertEquals(1f, out[6]);
        assertEquals(4.1f, out[10]);

        float[] in = out.clone();
        in[0] = 1007.5f;
        in[1] = 2003.25f;
        in[2] = 1.5f;
        in[7] = 2f;
        in[8] = -1f;
        in[9] = 0.5f;
        for (int w = 0; w < 4; w++) {
            in[11 + w * 4] = 0.1f * w; // steering
            in[12 + w * 4] = 1.5f + w; // rotation
            in[13 + w * 4] = 0.25f; // skid
            in[14 + w * 4] = 0.3f; // suspension length
        }
        assertEquals(1, VehicleNatives.setOwnVehiclePhysics(3, in, false));
        float[] back = new float[out.length];
        VehicleNatives.getOwnVehiclePhysics(3, back);
        for (int i = 0; i < back.length; i++) {
            assertEquals(in[i], back[i], "slot " + i);
        }

        in[10] = 3.9f; // (int) 3.9 = 3 != 4 wheels
        assertEquals(-5, VehicleNatives.setOwnVehiclePhysics(3, in, false));
        assertEquals(-4, VehicleNatives.setOwnVehiclePhysics(99, in, false));
        assertThrows(RuntimeException.class, () -> VehicleNatives.getOwnVehiclePhysics(99, back));
    }

    @Test
    void ownPhysicsCollideSweepsWithoutObstacles() {
        addCar(4, 1005f, 2005f);
        float[] in = new float[27];
        VehicleNatives.getOwnVehiclePhysics(4, in);
        in[0] = 1006f;
        assertEquals(1, VehicleNatives.setOwnVehiclePhysics(4, in, true));
        float[] back = new float[27];
        VehicleNatives.getOwnVehiclePhysics(4, back);
        assertEquals(1006f, back[0]);
    }

    @Test
    void vehiclePhysicsRecords() {
        addCar(1, 1001f, 2001f);
        addCar(2, 1002f, 2002f);
        assertEquals(2, VehicleNatives.getVehicleCount());
        float[] out = new float[60];
        assertEquals(2, VehicleNatives.getVehiclePhysics(0, out));
        assertEquals(1.1f, out[0]);
        assertEquals(1f, out[1]); // bullet-space x
        assertEquals(0.5f, out[2]);
        assertEquals(1f, out[3]);
        assertEquals(4.1f, out[13]);
        assertEquals(2.1f, out[30]);
        assertEquals(1, VehicleNatives.getVehiclePhysics(1, out));
        assertEquals(2.1f, out[0]);
        assertEquals(0, VehicleNatives.getVehiclePhysics(2, out));
        assertThrows(
                IllegalArgumentException.class, () -> VehicleNatives.getVehiclePhysics(-1, out));
        assertThrows(
                IllegalArgumentException.class,
                () -> VehicleNatives.getVehiclePhysics(0, new float[29]));
        // len/30 limits the record count
        assertEquals(1, VehicleNatives.getVehiclePhysics(0, new float[59]));
    }

    @Test
    void collidedFlagIsReportedOnce() {
        addCar(1, 1001f, 2001f);
        VehicleNatives.getVehicleCount();
        ws.m_vehicles.get(1).m_collided = true;
        float[] out = new float[30];
        VehicleNatives.getVehiclePhysics(0, out);
        assertEquals(1f, out[12]);
        VehicleNatives.getVehiclePhysics(0, out);
        assertEquals(0f, out[12]);
    }

    @Test
    void tiresAndParams() {
        addCar(5, 1005f, 2005f);
        PZVehicle v = ws.m_vehicles.get(5);
        VehicleNatives.setTireInflation(5, 2, 0.75f);
        assertEquals(0.75f, v.m_tireInflation[2]);
        VehicleNatives.setTireRemoved(5, 1, true);
        btWheelInfo w1 = v.m_vehicle.getWheelInfo(1);
        assertTrue(v.m_tireRemoved[1]);
        assertEquals(0.4, w1.m_frictionSlip);
        assertEquals((double) 0.4f * 0.66, w1.m_wheelsRadius);
        VehicleNatives.setTireRemoved(5, 1, false);
        assertEquals(2.0, w1.m_frictionSlip);
        assertEquals((double) 0.4f, w1.m_wheelsRadius);
        assertThrows(
                IllegalArgumentException.class, () -> VehicleNatives.setTireRemoved(5, 4, true));
        assertThrows(
                IllegalArgumentException.class, () -> VehicleNatives.setTireInflation(5, -1, 1f));

        float[] p = new float[24];
        for (int w = 0; w < 4; w++) {
            p[w * 6] = w == 3 ? 0f : 1f;
            p[w * 6 + 1] = 0.5f;
            p[w * 6 + 2] = 99f; // unused by the native
            p[w * 6 + 3] = 3f;
            p[w * 6 + 4] = 4f;
            p[w * 6 + 5] = 0.25f;
        }
        assertEquals(0, VehicleNatives.setVehicleParams(5, p));
        btWheelInfo w3 = v.m_vehicle.getWheelInfo(3);
        assertFalse(v.m_tirePresent[3]);
        assertTrue(v.m_tirePresent[0]);
        assertEquals(0.5f, v.m_tireInflation[0]);
        assertEquals(3.0, w3.m_wheelsDampingRelaxation);
        assertEquals(4.0, w3.m_wheelsDampingCompression);
        assertEquals(1.25, w3.m_suspensionRestLength1);
        assertEquals((double) 0.45f * 0.66, w3.m_wheelsRadius);
        assertEquals(0.4, w3.m_frictionSlip);
        assertEquals((double) 0.4f, v.m_vehicle.getWheelInfo(0).m_wheelsRadius);
        assertThrows(
                IllegalArgumentException.class,
                () -> VehicleNatives.setVehicleParams(5, new float[23]));
        assertEquals(-1, VehicleNatives.setVehicleParams(6, p));
        assertTrue(
                logs.contains(
                        "PZBullet Warning (PZBullet.cpp:772) > PZBullet::setVehicleParams> Vehicle"
                                + " not found. VehicleId:6"));
    }

    @Test
    void notFoundWarnings() {
        VehicleNatives.controlVehicle(42, 1f, 0f, 0f);
        VehicleNatives.applyCentralForceToVehicle(42, 1f, 0f, 0f);
        VehicleNatives.applyTorqueToVehicle(42, 1f, 0f, 0f);
        VehicleNatives.teleportVehicle(42, 0, 0, 0, 0, 0, 0, 1);
        assertEquals(0, VehicleNatives.setVehicleStatic(42, true));
        assertEquals(-1, VehicleNatives.setVehicleMass(42, 10f));
        VehicleNatives.setVehicleVelocityMultiplier(42, 1f, 1f);
        assertFalse(VehicleNatives.checkWheelCollision(42, 0, 0));
        assertEquals(
                List.of(
                        "PZBullet Warning (PZBullet.cpp:281) > Vehicle not found. VehicleId:42",
                        "PZBullet Warning (PZBullet.cpp:316) > Vehicle not found. VehicleId:42",
                        "PZBullet Warning (PZBullet.cpp:333) > Vehicle not found. VehicleId:42",
                        "PZBullet Warning (PZBullet.cpp:350) > Vehicle not found. VehicleId:42",
                        "PZBullet Warning (PZBullet.cpp:719) > Vehicle not found. VehicleId:42",
                        "PZBullet Warning (PZBullet.cpp:842) > Vehicle not found. VehicleId:42",
                        "PZBullet Warning (PZBullet.cpp:967) > Vehicle not found. VehicleId:42"),
                logs.stream().filter(s -> s.contains("VehicleId:42")).toList());
        assertThrows(RuntimeException.class, () -> VehicleNatives.setVehicleActive(42, true));
        VehicleNatives.setVehicleActive(42, false); // no throw
    }

    @Test
    void forcesTeleportMassAndMultiplier() {
        addCar(8, 1005f, 2005f);
        PZVehicle v = ws.m_vehicles.get(8);
        VehicleNatives.applyCentralForceToVehicle(8, 1f, 2f, 3f);
        assertEquals(2.0, v.m_carChassis.getTotalForce().y);
        VehicleNatives.teleportVehicle(8, 1020f, 2030f, 2f, 0f, 0f, 0f, 1f);
        assertEquals(20.0, v.m_carChassis.getWorldTransform().getOrigin().x);
        assertEquals(2.0, v.m_carChassis.getWorldTransform().getOrigin().y);
        assertEquals(30.0, v.m_carChassis.getWorldTransform().getOrigin().z);
        assertEquals(0.0, v.m_carChassis.getLinearVelocity().x);
        assertEquals(0, VehicleNatives.setVehicleMass(8, 500f));
        assertEquals(1.0 / 500.0, v.m_carChassis.getInvMass());
        VehicleNatives.setVehicleVelocityMultiplier(8, 30f, 0.5f);
        assertEquals(30.0, v.m_maxSpeed);
        assertEquals(0.5, v.m_velocityMultiplier);
        assertEquals(1, VehicleNatives.setVehicleStatic(8, true));
        assertTrue(v.m_isStatic);
        assertEquals(0.0, v.m_carChassis.getInvMass());
        assertEquals(1, VehicleNatives.setVehicleStatic(8, false));
        assertFalse(v.m_isStatic);
    }

    @Test
    void constraints() {
        addCar(1, 1001f, 2001f);
        addCar(2, 1004f, 2001f);
        int hinge = VehicleNatives.addHingeConstraint(1, 2, 0, 0, -2, 0, 0, 2);
        assertEquals(1, hinge); // counter at WS+0x258 starts at 0
        assertTrue(ws.m_constraints.get(hinge).c instanceof btHingeConstraint);
        assertSame(ws.m_vehicles.get(1), ws.m_constraints.get(hinge).vehA);
        assertNull(ws.m_constraints.get(hinge).ragdollA);
        int point = VehicleNatives.addPointConstraint(1, 2, 0, 0, -2, 0, 0, 2);
        assertTrue(ws.m_constraints.get(point).c instanceof btPoint2PointConstraint);

        int dof =
                VehicleNatives.add6DofConstraint(
                        1, 2, 0, 0, -2, 0, 0, 2, -1, -2, -3, 1, 2, 3, 4f, -4f, 0.5f, 7f, 1f, -7f);
        btGeneric6DofConstraint g = (btGeneric6DofConstraint) ws.m_constraints.get(dof).c;
        double twoPi = 6.283185307179586;
        assertEquals((double) 4f - twoPi, g.getRotationalLimitMotor(0).m_loLimit);
        assertEquals((double) -4f + twoPi, g.getRotationalLimitMotor(1).m_loLimit);
        assertEquals((double) 0.5f, g.getRotationalLimitMotor(2).m_loLimit);
        assertEquals(GlibcMath.fmod(7.0, twoPi), g.getRotationalLimitMotor(0).m_hiLimit);
        assertEquals(GlibcMath.fmod(-7.0, twoPi), g.getRotationalLimitMotor(2).m_hiLimit);

        int rope = VehicleNatives.addRopeConstraint(1, 2, 0, 0, -2, 0, 0, 2, 1.5f);
        assertTrue(ws.m_constraints.get(rope).c instanceof btGeneric6DofConstraint);
        assertEquals(4, ws.m_constraints.size());

        assertEquals(-1, VehicleNatives.addHingeConstraint(1, 1, 0, 0, 0, 0, 0, 0));
        assertEquals(-1, VehicleNatives.addPointConstraint(1, 9, 0, 0, 0, 0, 0, 0));
        assertTrue(
                logs.contains(
                        "PZBullet Warning (PZBullet.cpp:1114) > Vehicle not found. VehicleId:9"));
        assertEquals(
                "the same vehicle",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> VehicleNatives.addRopeConstraint(1, 1, 0, 0, 0, 0, 0, 0, 1))
                        .getMessage());
        assertEquals(
                "vehicle B not found",
                assertThrows(
                                NullPointerException.class,
                                () -> VehicleNatives.addRopeConstraint(1, 9, 0, 0, 0, 0, 0, 0, 1))
                        .getMessage());

        VehicleNatives.setConstraintERP(hinge, 0.3f, -1);
        VehicleNatives.removeConstraint(point);
        assertEquals(3, ws.m_constraints.size());
        VehicleNatives.removeVehicle(2);
        assertEquals(0, ws.m_constraints.size());
        assertEquals(1, VehicleNatives.getVehicleCount());
    }

    @Test
    void controlAndStep() {
        addCar(1, 1001f, 2001f);
        VehicleNatives.setVehicleActive(1, true);
        VehicleNatives.controlVehicle(1, 500f, 0f, 0.2f);
        PZVehicle v = ws.m_vehicles.get(1);
        // rear-wheel drive: front wheels steer, rear wheels get the engine force
        assertEquals(0.0, v.m_vehicle.getWheelInfo(0).m_engineForce);
        assertEquals((double) 0.2f, v.m_vehicle.getWheelInfo(0).m_steering);
        assertEquals(500.0, v.m_vehicle.getWheelInfo(3).m_engineForce);
        assertEquals(0.0, v.m_vehicle.getWheelInfo(3).m_steering);
        ws.m_dynamicsWorld.stepSimulation(1.0 / 60.0, 1, 1.0 / 60.0);
        float[] out = new float[27];
        VehicleNatives.getOwnVehiclePhysics(1, out);
        assertTrue(Float.isFinite(out[0]) && Float.isFinite(out[2]));
    }
}
