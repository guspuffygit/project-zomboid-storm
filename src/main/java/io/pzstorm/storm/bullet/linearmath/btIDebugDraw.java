// Port of LinearMath/btIDebugDraw.h (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Debug drawing interface with the 2.82 default method bodies verbatim. (2.82 has no {@code
 * DefaultColors} struct / getDefaultColors; that arrived in 2.83.) Float literals in the C++
 * ({@code 0.7f}, {@code 0.5f}, {@code 30.f}, {@code 100.f}) are the double value of the float
 * literal ({@code (double) 0.7f != 0.7}). C++ default arguments are Java overloads. Where C++ calls
 * {@code btCos(a)} and {@code btSin(a)} with the same argument, {@link btScalar#btSinCos} is used
 * (GCC fuses them; identical bits).
 */
public abstract class btIDebugDraw {
    // enum DebugDrawModes
    public static final int DBG_NoDebug = 0;
    public static final int DBG_DrawWireframe = 1;
    public static final int DBG_DrawAabb = 2;
    public static final int DBG_DrawFeaturesText = 4;
    public static final int DBG_DrawContactPoints = 8;
    public static final int DBG_NoDeactivation = 16;
    public static final int DBG_NoHelpText = 32;
    public static final int DBG_DrawText = 64;
    public static final int DBG_ProfileTimings = 128;
    public static final int DBG_EnableSatComparison = 256;
    public static final int DBG_DisableBulletLCP = 512;
    public static final int DBG_EnableCCD = 1024;
    public static final int DBG_DrawConstraints = (1 << 11);
    public static final int DBG_DrawConstraintLimits = (1 << 12);
    public static final int DBG_FastWireframe = (1 << 13);
    public static final int DBG_DrawNormals = (1 << 14);
    public static final int DBG_MAX_DEBUG_DRAW_MODE = (1 << 14) + 1;

    public abstract void drawLine(btVector3 from, btVector3 to, btVector3 color);

    public void drawLine(btVector3 from, btVector3 to, btVector3 fromColor, btVector3 toColor) {
        drawLine(from, to, fromColor);
    }

    public void drawSphere(double radius, btTransform transform, btVector3 color) {
        btVector3 center = new btVector3(transform.getOrigin());
        btVector3 up = transform.getBasis().getColumn(1);
        btVector3 axis = transform.getBasis().getColumn(0);
        double minTh = -btScalar.SIMD_HALF_PI;
        double maxTh = btScalar.SIMD_HALF_PI;
        double minPs = -btScalar.SIMD_HALF_PI;
        double maxPs = btScalar.SIMD_HALF_PI;
        double stepDegrees = 30.f;
        drawSpherePatch(
                center, up, axis, radius, minTh, maxTh, minPs, maxPs, color, stepDegrees, false);
        drawSpherePatch(
                center,
                up,
                axis.negate(),
                radius,
                minTh,
                maxTh,
                minPs,
                maxPs,
                color,
                stepDegrees,
                false);
    }

    public void drawSphere(btVector3 p, double radius, btVector3 color) {
        btTransform tr = new btTransform();
        tr.setIdentity();
        tr.setOrigin(p);
        drawSphere(radius, tr, color);
    }

    public void drawTriangle(
            btVector3 v0,
            btVector3 v1,
            btVector3 v2,
            btVector3 n0,
            btVector3 n1,
            btVector3 n2,
            btVector3 color,
            double alpha) {
        drawTriangle(v0, v1, v2, color, alpha);
    }

    public void drawTriangle(
            btVector3 v0, btVector3 v1, btVector3 v2, btVector3 color, double alpha) {
        drawLine(v0, v1, color);
        drawLine(v1, v2, color);
        drawLine(v2, v0, color);
    }

    public abstract void drawContactPoint(
            btVector3 PointOnB,
            btVector3 normalOnB,
            double distance,
            int lifeTime,
            btVector3 color);

    public abstract void reportErrorWarning(String warningString);

    public abstract void draw3dText(btVector3 location, String textString);

    public abstract void setDebugMode(int debugMode);

    public abstract int getDebugMode();

    public void drawAabb(btVector3 from, btVector3 to, btVector3 color) {
        btVector3 halfExtents = (to.sub(from)).mul(0.5f);
        btVector3 center = (to.add(from)).mul(0.5f);
        int i, j;

        btVector3 edgecoord = new btVector3(1.f, 1.f, 1.f);
        btVector3 pa, pb;
        for (i = 0; i < 4; i++) {
            for (j = 0; j < 3; j++) {
                pa =
                        new btVector3(
                                edgecoord.get(0) * halfExtents.get(0),
                                edgecoord.get(1) * halfExtents.get(1),
                                edgecoord.get(2) * halfExtents.get(2));
                pa.addLocal(center);

                int othercoord = j % 3;
                edgecoord.set(othercoord, edgecoord.get(othercoord) * -1.f);
                pb =
                        new btVector3(
                                edgecoord.get(0) * halfExtents.get(0),
                                edgecoord.get(1) * halfExtents.get(1),
                                edgecoord.get(2) * halfExtents.get(2));
                pb.addLocal(center);

                drawLine(pa, pb, color);
            }
            edgecoord.set(new btVector3(-1.f, -1.f, -1.f));
            if (i < 3) edgecoord.set(i, edgecoord.get(i) * -1.f);
        }
    }

    public void drawTransform(btTransform transform, double orthoLen) {
        btVector3 start = new btVector3(transform.getOrigin());
        drawLine(
                start,
                start.add(transform.getBasis().mul(new btVector3(orthoLen, 0, 0))),
                new btVector3(0.7f, 0, 0));
        drawLine(
                start,
                start.add(transform.getBasis().mul(new btVector3(0, orthoLen, 0))),
                new btVector3(0, 0.7f, 0));
        drawLine(
                start,
                start.add(transform.getBasis().mul(new btVector3(0, 0, orthoLen))),
                new btVector3(0, 0, 0.7f));
    }

    public void drawArc(
            btVector3 center,
            btVector3 normal,
            btVector3 axis,
            double radiusA,
            double radiusB,
            double minAngle,
            double maxAngle,
            btVector3 color,
            boolean drawSect) {
        drawArc(center, normal, axis, radiusA, radiusB, minAngle, maxAngle, color, drawSect, 10.f);
    }

    public void drawArc(
            btVector3 center,
            btVector3 normal,
            btVector3 axis,
            double radiusA,
            double radiusB,
            double minAngle,
            double maxAngle,
            btVector3 color,
            boolean drawSect,
            double stepDegrees) {
        btVector3 vx = axis;
        btVector3 vy = normal.cross(axis);
        double step = stepDegrees * btScalar.SIMD_RADS_PER_DEG;
        int nSteps = btScalar.cvttsd2si((maxAngle - minAngle) / step);
        if (nSteps == 0) nSteps = 1;
        double[] sc = new double[2];
        btScalar.btSinCos(minAngle, sc);
        btVector3 prev = center.add(vx.mul(radiusA).mul(sc[1])).add(vy.mul(radiusB).mul(sc[0]));
        if (drawSect) {
            drawLine(center, prev, color);
        }
        for (int i = 1; i <= nSteps; i++) {
            double angle = minAngle + (maxAngle - minAngle) * (double) i / (double) nSteps;
            btScalar.btSinCos(angle, sc);
            btVector3 next = center.add(vx.mul(radiusA).mul(sc[1])).add(vy.mul(radiusB).mul(sc[0]));
            drawLine(prev, next, color);
            prev = next;
        }
        if (drawSect) {
            drawLine(center, prev, color);
        }
    }

    public void drawSpherePatch(
            btVector3 center,
            btVector3 up,
            btVector3 axis,
            double radius,
            double minTh,
            double maxTh,
            double minPs,
            double maxPs,
            btVector3 color) {
        drawSpherePatch(center, up, axis, radius, minTh, maxTh, minPs, maxPs, color, 10.f, true);
    }

    public void drawSpherePatch(
            btVector3 center,
            btVector3 up,
            btVector3 axis,
            double radius,
            double minTh,
            double maxTh,
            double minPs,
            double maxPs,
            btVector3 color,
            double stepDegrees) {
        drawSpherePatch(
                center, up, axis, radius, minTh, maxTh, minPs, maxPs, color, stepDegrees, true);
    }

    public void drawSpherePatch(
            btVector3 center,
            btVector3 up,
            btVector3 axis,
            double radius,
            double minTh,
            double maxTh,
            double minPs,
            double maxPs,
            btVector3 color,
            double stepDegrees,
            boolean drawCenter) {
        btVector3[] vA = new btVector3[74];
        btVector3[] vB = new btVector3[74];
        for (int k = 0; k < 74; k++) {
            vA[k] = new btVector3();
            vB[k] = new btVector3();
        }
        btVector3[] pvA = vA, pvB = vB, pT;
        btVector3 npole = center.add(up.mul(radius));
        btVector3 spole = center.sub(up.mul(radius));
        btVector3 arcStart = new btVector3();
        double step = stepDegrees * btScalar.SIMD_RADS_PER_DEG;
        btVector3 kv = up;
        btVector3 iv = axis;
        btVector3 jv = kv.cross(iv);
        boolean drawN = false;
        boolean drawS = false;
        if (minTh <= -btScalar.SIMD_HALF_PI) {
            minTh = -btScalar.SIMD_HALF_PI + step;
            drawN = true;
        }
        if (maxTh >= btScalar.SIMD_HALF_PI) {
            maxTh = btScalar.SIMD_HALF_PI - step;
            drawS = true;
        }
        if (minTh > maxTh) {
            minTh = -btScalar.SIMD_HALF_PI + step;
            maxTh = btScalar.SIMD_HALF_PI - step;
            drawN = drawS = true;
        }
        int n_hor = btScalar.cvttsd2si((maxTh - minTh) / step) + 1;
        if (n_hor < 2) n_hor = 2;
        double step_h = (maxTh - minTh) / (double) (n_hor - 1);
        boolean isClosed = false;
        if (minPs > maxPs) {
            minPs = -btScalar.SIMD_PI + step;
            maxPs = btScalar.SIMD_PI;
            isClosed = true;
        } else if ((maxPs - minPs) >= btScalar.SIMD_PI * 2.f) {
            isClosed = true;
        } else {
            isClosed = false;
        }
        int n_vert = btScalar.cvttsd2si((maxPs - minPs) / step) + 1;
        if (n_vert < 2) n_vert = 2;
        double step_v = (maxPs - minPs) / (double) (n_vert - 1);
        double[] sc = new double[2];
        for (int i = 0; i < n_hor; i++) {
            double th = minTh + (double) i * step_h;
            btScalar.btSinCos(th, sc);
            double sth = radius * sc[0];
            double cth = radius * sc[1];
            for (int j = 0; j < n_vert; j++) {
                double psi = minPs + (double) j * step_v;
                btScalar.btSinCos(psi, sc);
                double sps = sc[0];
                double cps = sc[1];
                pvB[j].set(center.add(iv.mul(cth * cps)).add(jv.mul(cth * sps)).add(kv.mul(sth)));
                if (i != 0) {
                    drawLine(pvA[j], pvB[j], color);
                } else if (drawS) {
                    drawLine(spole, pvB[j], color);
                }
                if (j != 0) {
                    drawLine(pvB[j - 1], pvB[j], color);
                } else {
                    arcStart.set(pvB[j]);
                }
                if ((i == (n_hor - 1)) && drawN) {
                    drawLine(npole, pvB[j], color);
                }

                if (drawCenter) {
                    if (isClosed) {
                        if (j == (n_vert - 1)) {
                            drawLine(arcStart, pvB[j], color);
                        }
                    } else {
                        if (((i == 0) || (i == (n_hor - 1))) && ((j == 0) || (j == (n_vert - 1)))) {
                            drawLine(center, pvB[j], color);
                        }
                    }
                }
            }
            pT = pvA;
            pvA = pvB;
            pvB = pT;
        }
    }

    public void drawBox(btVector3 bbMin, btVector3 bbMax, btVector3 color) {
        double n0 = bbMin.x, n1 = bbMin.y, n2 = bbMin.z;
        double x0 = bbMax.x, x1 = bbMax.y, x2 = bbMax.z;
        drawLine(new btVector3(n0, n1, n2), new btVector3(x0, n1, n2), color);
        drawLine(new btVector3(x0, n1, n2), new btVector3(x0, x1, n2), color);
        drawLine(new btVector3(x0, x1, n2), new btVector3(n0, x1, n2), color);
        drawLine(new btVector3(n0, x1, n2), new btVector3(n0, n1, n2), color);
        drawLine(new btVector3(n0, n1, n2), new btVector3(n0, n1, x2), color);
        drawLine(new btVector3(x0, n1, n2), new btVector3(x0, n1, x2), color);
        drawLine(new btVector3(x0, x1, n2), new btVector3(x0, x1, x2), color);
        drawLine(new btVector3(n0, x1, n2), new btVector3(n0, x1, x2), color);
        drawLine(new btVector3(n0, n1, x2), new btVector3(x0, n1, x2), color);
        drawLine(new btVector3(x0, n1, x2), new btVector3(x0, x1, x2), color);
        drawLine(new btVector3(x0, x1, x2), new btVector3(n0, x1, x2), color);
        drawLine(new btVector3(n0, x1, x2), new btVector3(n0, n1, x2), color);
    }

    public void drawBox(btVector3 bbMin, btVector3 bbMax, btTransform trans, btVector3 color) {
        double n0 = bbMin.x, n1 = bbMin.y, n2 = bbMin.z;
        double x0 = bbMax.x, x1 = bbMax.y, x2 = bbMax.z;
        drawLine(
                trans.transform(new btVector3(n0, n1, n2)),
                trans.transform(new btVector3(x0, n1, n2)),
                color);
        drawLine(
                trans.transform(new btVector3(x0, n1, n2)),
                trans.transform(new btVector3(x0, x1, n2)),
                color);
        drawLine(
                trans.transform(new btVector3(x0, x1, n2)),
                trans.transform(new btVector3(n0, x1, n2)),
                color);
        drawLine(
                trans.transform(new btVector3(n0, x1, n2)),
                trans.transform(new btVector3(n0, n1, n2)),
                color);
        drawLine(
                trans.transform(new btVector3(n0, n1, n2)),
                trans.transform(new btVector3(n0, n1, x2)),
                color);
        drawLine(
                trans.transform(new btVector3(x0, n1, n2)),
                trans.transform(new btVector3(x0, n1, x2)),
                color);
        drawLine(
                trans.transform(new btVector3(x0, x1, n2)),
                trans.transform(new btVector3(x0, x1, x2)),
                color);
        drawLine(
                trans.transform(new btVector3(n0, x1, n2)),
                trans.transform(new btVector3(n0, x1, x2)),
                color);
        drawLine(
                trans.transform(new btVector3(n0, n1, x2)),
                trans.transform(new btVector3(x0, n1, x2)),
                color);
        drawLine(
                trans.transform(new btVector3(x0, n1, x2)),
                trans.transform(new btVector3(x0, x1, x2)),
                color);
        drawLine(
                trans.transform(new btVector3(x0, x1, x2)),
                trans.transform(new btVector3(n0, x1, x2)),
                color);
        drawLine(
                trans.transform(new btVector3(n0, x1, x2)),
                trans.transform(new btVector3(n0, n1, x2)),
                color);
    }

    public void drawCapsule(
            double radius, double halfHeight, int upAxis, btTransform transform, btVector3 color) {
        int stepDegrees = 30;

        btVector3 capStart = new btVector3(0.f, 0.f, 0.f);
        capStart.set(upAxis, -halfHeight);

        btVector3 capEnd = new btVector3(0.f, 0.f, 0.f);
        capEnd.set(upAxis, halfHeight);

        {
            btTransform childTransform = new btTransform(transform);
            childTransform.getOrigin().set(transform.transform(capStart));
            {
                btVector3 center = new btVector3(childTransform.getOrigin());
                btVector3 up = childTransform.getBasis().getColumn((upAxis + 1) % 3);
                btVector3 axis = childTransform.getBasis().getColumn(upAxis).negate();
                double minTh = -btScalar.SIMD_HALF_PI;
                double maxTh = btScalar.SIMD_HALF_PI;
                double minPs = -btScalar.SIMD_HALF_PI;
                double maxPs = btScalar.SIMD_HALF_PI;

                drawSpherePatch(
                        center,
                        up,
                        axis,
                        radius,
                        minTh,
                        maxTh,
                        minPs,
                        maxPs,
                        color,
                        (double) stepDegrees,
                        false);
            }
        }

        {
            btTransform childTransform = new btTransform(transform);
            childTransform.getOrigin().set(transform.transform(capEnd));
            {
                btVector3 center = new btVector3(childTransform.getOrigin());
                btVector3 up = childTransform.getBasis().getColumn((upAxis + 1) % 3);
                btVector3 axis = childTransform.getBasis().getColumn(upAxis);
                double minTh = -btScalar.SIMD_HALF_PI;
                double maxTh = btScalar.SIMD_HALF_PI;
                double minPs = -btScalar.SIMD_HALF_PI;
                double maxPs = btScalar.SIMD_HALF_PI;
                drawSpherePatch(
                        center,
                        up,
                        axis,
                        radius,
                        minTh,
                        maxTh,
                        minPs,
                        maxPs,
                        color,
                        (double) stepDegrees,
                        false);
            }
        }

        btVector3 start = new btVector3(transform.getOrigin());

        double[] sc = new double[2];
        for (int i = 0; i < 360; i += stepDegrees) {
            btScalar.btSinCos((double) i * btScalar.SIMD_RADS_PER_DEG, sc);
            double s = sc[0] * radius;
            capStart.set((upAxis + 1) % 3, s);
            capEnd.set((upAxis + 1) % 3, s);
            double c = sc[1] * radius;
            capStart.set((upAxis + 2) % 3, c);
            capEnd.set((upAxis + 2) % 3, c);
            drawLine(
                    start.add(transform.getBasis().mul(capStart)),
                    start.add(transform.getBasis().mul(capEnd)),
                    color);
        }
    }

    public void drawCylinder(
            double radius, double halfHeight, int upAxis, btTransform transform, btVector3 color) {
        btVector3 start = new btVector3(transform.getOrigin());
        btVector3 offsetHeight = new btVector3(0, 0, 0);
        offsetHeight.set(upAxis, halfHeight);
        int stepDegrees = 30;
        btVector3 capStart = new btVector3(0.f, 0.f, 0.f);
        capStart.set(upAxis, -halfHeight);
        btVector3 capEnd = new btVector3(0.f, 0.f, 0.f);
        capEnd.set(upAxis, halfHeight);

        double[] sc = new double[2];
        for (int i = 0; i < 360; i += stepDegrees) {
            btScalar.btSinCos((double) i * btScalar.SIMD_RADS_PER_DEG, sc);
            double s = sc[0] * radius;
            capStart.set((upAxis + 1) % 3, s);
            capEnd.set((upAxis + 1) % 3, s);
            double c = sc[1] * radius;
            capStart.set((upAxis + 2) % 3, c);
            capEnd.set((upAxis + 2) % 3, c);
            drawLine(
                    start.add(transform.getBasis().mul(capStart)),
                    start.add(transform.getBasis().mul(capEnd)),
                    color);
        }
        btVector3 yaxis = new btVector3(0, 0, 0);
        yaxis.set(upAxis, 1.0);
        btVector3 xaxis = new btVector3(0, 0, 0);
        xaxis.set((upAxis + 1) % 3, 1.0);
        drawArc(
                start.sub(transform.getBasis().mul(offsetHeight)),
                transform.getBasis().mul(yaxis),
                transform.getBasis().mul(xaxis),
                radius,
                radius,
                0,
                btScalar.SIMD_2_PI,
                color,
                false,
                10.0);
        drawArc(
                start.add(transform.getBasis().mul(offsetHeight)),
                transform.getBasis().mul(yaxis),
                transform.getBasis().mul(xaxis),
                radius,
                radius,
                0,
                btScalar.SIMD_2_PI,
                color,
                false,
                10.0);
    }

    public void drawCone(
            double radius, double height, int upAxis, btTransform transform, btVector3 color) {
        int stepDegrees = 30;
        btVector3 start = new btVector3(transform.getOrigin());

        btVector3 offsetHeight = new btVector3(0, 0, 0);
        double halfHeight = height * 0.5;
        offsetHeight.set(upAxis, halfHeight);
        btVector3 offsetRadius = new btVector3(0, 0, 0);
        offsetRadius.set((upAxis + 1) % 3, radius);
        btVector3 offset2Radius = new btVector3(0, 0, 0);
        offset2Radius.set((upAxis + 2) % 3, radius);

        btVector3 capEnd = new btVector3(0.f, 0.f, 0.f);
        capEnd.set(upAxis, -halfHeight);

        double[] sc = new double[2];
        for (int i = 0; i < 360; i += stepDegrees) {
            btScalar.btSinCos((double) i * btScalar.SIMD_RADS_PER_DEG, sc);
            capEnd.set((upAxis + 1) % 3, sc[0] * radius);
            capEnd.set((upAxis + 2) % 3, sc[1] * radius);
            drawLine(
                    start.add(transform.getBasis().mul(offsetHeight)),
                    start.add(transform.getBasis().mul(capEnd)),
                    color);
        }

        drawLine(
                start.add(transform.getBasis().mul(offsetHeight)),
                start.add(transform.getBasis().mul(offsetHeight.negate().add(offsetRadius))),
                color);
        drawLine(
                start.add(transform.getBasis().mul(offsetHeight)),
                start.add(transform.getBasis().mul(offsetHeight.negate().sub(offsetRadius))),
                color);
        drawLine(
                start.add(transform.getBasis().mul(offsetHeight)),
                start.add(transform.getBasis().mul(offsetHeight.negate().add(offset2Radius))),
                color);
        drawLine(
                start.add(transform.getBasis().mul(offsetHeight)),
                start.add(transform.getBasis().mul(offsetHeight.negate().sub(offset2Radius))),
                color);

        btVector3 yaxis = new btVector3(0, 0, 0);
        yaxis.set(upAxis, 1.0);
        btVector3 xaxis = new btVector3(0, 0, 0);
        xaxis.set((upAxis + 1) % 3, 1.0);
        drawArc(
                start.sub(transform.getBasis().mul(offsetHeight)),
                transform.getBasis().mul(yaxis),
                transform.getBasis().mul(xaxis),
                radius,
                radius,
                0,
                btScalar.SIMD_2_PI,
                color,
                false,
                10.0);
    }

    public void drawPlane(
            btVector3 planeNormal, double planeConst, btTransform transform, btVector3 color) {
        btVector3 planeOrigin = planeNormal.mul(planeConst);
        btVector3 vec0 = new btVector3(), vec1 = new btVector3();
        btVector3.btPlaneSpace1(planeNormal, vec0, vec1);
        double vecLen = 100.f;
        btVector3 pt0 = planeOrigin.add(vec0.mul(vecLen));
        btVector3 pt1 = planeOrigin.sub(vec0.mul(vecLen));
        btVector3 pt2 = planeOrigin.add(vec1.mul(vecLen));
        btVector3 pt3 = planeOrigin.sub(vec1.mul(vecLen));
        drawLine(transform.transform(pt0), transform.transform(pt1), color);
        drawLine(transform.transform(pt2), transform.transform(pt3), color);
    }
}
