package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.BulletUpcalls;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link GLDebugDrawer}: once-logs (function-local statics) and colour substitution. */
class GLDebugDrawerTest implements UnitTest {

    private final List<String> logs = new ArrayList<>();

    private static String msg(int line, String text) {
        return "PZBullet Debug (GLDebugDrawer.cpp:" + line + ") > " + text;
    }

    private static final String LINE17 = "GLDebugDrawer::drawLine - IS USED!, but calls nothing";

    @BeforeEach
    void setUp() {
        GLDebugDrawer.drawLine4_onceGLDebugDraw = false;
        GLDebugDrawer.drawLine3_onceGLDebugDraw = false;
        GLDebugDrawer.drawCapsule_onceGLDebugDraw = false;
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
                        return null;
                    }

                    @Override
                    public int getBoneOrdinal(String name) {
                        return -1;
                    }
                };
    }

    @AfterEach
    void tearDown() {
        BulletUpcalls.target = BulletUpcalls.GameTarget.INSTANCE;
        GLDebugDrawer.drawLine4_onceGLDebugDraw = false;
        GLDebugDrawer.drawLine3_onceGLDebugDraw = false;
        GLDebugDrawer.drawCapsule_onceGLDebugDraw = false;
    }

    private static final btVector3 A = new btVector3(1, 2, 3);
    private static final btVector3 B = new btVector3(4, 5, 6);
    private static final btVector3 C = new btVector3(0.2, 0.4, 0.6);

    @Test
    void ctorAndDebugMode() {
        GLDebugDrawer d = new GLDebugDrawer();
        assertEquals(0, d.getDebugMode());
        d.setDebugMode(0x801);
        assertEquals(0x801, d.getDebugMode());
    }

    @Test
    void drawLine4LogsOnceAcrossInstances() {
        new GLDebugDrawer().drawLine(A, B, C, C);
        new GLDebugDrawer().drawLine(A, B, C, C);
        assertEquals(List.of(msg(17, LINE17)), logs);
        assertTrue(GLDebugDrawer.drawLine4_onceGLDebugDraw);
    }

    @Test
    void drawLine3LogsOnceThenForwardsRed() {
        GLDebugDrawer d = new GLDebugDrawer();
        d.drawLine(A, B, C);
        d.drawLine(A, B, C);
        assertEquals(List.of(msg(23, "GLDebugDrawer::onceDrawLine00"), msg(17, LINE17)), logs);
        assertTrue(GLDebugDrawer.drawLine3_onceGLDebugDraw);

        List<String> fwd = new ArrayList<>();
        GLDebugDrawer cap =
                new GLDebugDrawer() {
                    @Override
                    public void drawLine(btVector3 from, btVector3 to, btVector3 fc, btVector3 tc) {
                        fwd.add(
                                from.x + "," + to.x + " " + fc.x + fc.y + fc.z + " " + tc.x + tc.y
                                        + tc.z);
                    }
                };
        cap.drawLine(A, B, C);
        assertEquals(List.of("1.0,4.0 1.00.00.0 1.00.00.0"), fwd);
    }

    @Test
    void sphereAndTriangleLogEveryCall() {
        GLDebugDrawer d = new GLDebugDrawer();
        d.drawSphere(A, 1.0, C);
        d.drawSphere(A, 1.0, C);
        d.drawTriangle(A, B, A, C, 0.5);
        d.drawTriangle(A, B, A, C, 0.5);
        String s = msg(30, "GLDebugDrawer::drawSphere - IS USED!, but calls nothing");
        String t = msg(43, "GLDebugDrawer::drawTriangle - IS USED!, but calls nothing");
        assertEquals(List.of(s, s, t, t), logs);
    }

    @Test
    void emptyMethodsDoNothing() {
        GLDebugDrawer d = new GLDebugDrawer();
        d.drawContactPoint(A, B, 0.1, 3, C);
        d.reportErrorWarning("w");
        d.draw3dText(A, "t");
        assertTrue(logs.isEmpty());
    }

    @Test
    void drawCapsuleLogsOnceAndDrawsCyan() {
        List<String> colours = new ArrayList<>();
        GLDebugDrawer cap =
                new GLDebugDrawer() {
                    @Override
                    public void drawLine(btVector3 from, btVector3 to, btVector3 c) {
                        colours.add(c.x + "," + c.y + "," + c.z);
                    }

                    @Override
                    public void drawLine(btVector3 from, btVector3 to, btVector3 fc, btVector3 tc) {
                        colours.add(fc.x + "," + fc.y + "," + fc.z);
                    }
                };
        btTransform t = new btTransform(new btQuaternion(0, 0, 0, 1));
        t.setOrigin(new btVector3(1, 2, 3));
        cap.drawCapsule(0.3, 0.5, 1, t, C);
        cap.drawCapsule(0.3, 0.5, 1, t, C);
        assertEquals(List.of(msg(36, "GLDebugDrawer::drawCapsule")), logs);
        assertFalse(colours.isEmpty());
        for (String c : colours) {
            assertEquals("0.0,1.0,1.0", c);
        }
    }
}
