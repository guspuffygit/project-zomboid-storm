package io.pzstorm.storm.bullet.trace.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vehicle scripts as {@code VehicleScript} holds them after {@code Load} (script values multiplied
 * by the model scale, component-wise in float, as {@code Vector3f.mul} does) and the exact {@code
 * float[200]} layout {@code VehicleScript.toBullet} passes to {@code defineVehicleScript}.
 */
public final class VehicleScripts {

    private VehicleScripts() {}

    public record Wheel(boolean front, float offsetX, float offsetY, float offsetZ, float radius) {}

    /** type 1 = box (extents + rotate degrees), 2 = sphere (radius), 3 = mesh. */
    public record Shape(
            int type,
            float offX,
            float offY,
            float offZ,
            float extX,
            float extY,
            float extZ,
            float rotX,
            float rotY,
            float rotZ,
            float radius) {}

    public record Script(
            String fullName,
            float modelScale,
            float modelOffsetX,
            float modelOffsetY,
            float modelOffsetZ,
            float mass,
            float rollInfluence,
            float suspensionStiffness,
            float suspensionCompression,
            float suspensionDamping,
            float maxSuspensionTravelCm,
            float suspensionRestLength,
            float wheelFriction,
            float stoppingMovementForce,
            Wheel[] wheels,
            float comX,
            float comY,
            float comZ,
            float extentsX,
            float extentsY,
            float extentsZ,
            boolean chassisShape,
            float chassisX,
            float chassisY,
            float chassisZ,
            Shape[] shapes,
            float engineForce,
            float brakingForce,
            float maxSpeed,
            Map<String, float[]> attachments) {

        /**
         * {@code BaseVehicle.getAttachmentLocalPos}: the attachment offset plus the model offset,
         * both already scaled; null if the script has no such attachment.
         */
        public float[] attachmentLocalPos(String id) {
            float[] a = attachments.get(id);
            return a == null
                    ? null
                    : new float[] {a[0] + modelOffsetX, a[1] + modelOffsetY, a[2] + modelOffsetZ};
        }

        /** How many shapes the native vehicle has: the chassis box (if any) then the shapes. */
        public int nativeShapeCount() {
            int n = (chassisShape ? 1 : 0) + shapes.length;
            return n == 0 ? 1 : n;
        }

        /** {@code VehicleScript.toBullet}. */
        public float[] toBullet() {
            float[] p = new float[200];
            int n = 0;
            p[n++] = modelScale;
            p[n++] = mass;
            p[n++] = rollInfluence;
            p[n++] = suspensionStiffness;
            p[n++] = suspensionCompression;
            p[n++] = suspensionDamping;
            p[n++] = maxSuspensionTravelCm;
            p[n++] = suspensionRestLength;
            p[n++] = wheelFriction;
            p[n++] = stoppingMovementForce;
            p[n++] = wheels.length;
            for (Wheel w : wheels) {
                p[n++] = w.front ? 1.0F : 0.0F;
                p[n++] = w.offsetX + modelOffsetX - 0.0F * comX;
                p[n++] = w.offsetY + modelOffsetY - 0.0F * comY + 1.0F * suspensionRestLength;
                p[n++] = w.offsetZ + modelOffsetZ - 0.0F * comZ;
                p[n++] = w.radius;
            }
            int numShapes = (chassisShape ? 1 : 0) + shapes.length;
            if (numShapes == 0) {
                numShapes = 1;
            }
            p[n++] = numShapes;
            if (chassisShape || shapes.length == 0) {
                p[n++] = 1.0F;
                p[n++] = comX;
                p[n++] = comY;
                p[n++] = comZ;
                p[n++] = chassisShape ? chassisX : extentsX;
                p[n++] = chassisShape ? chassisY : extentsY;
                p[n++] = chassisShape ? chassisZ : extentsZ;
                p[n++] = 0.0F;
                p[n++] = 0.0F;
                p[n++] = 0.0F;
            }
            for (Shape s : shapes) {
                p[n++] = s.type;
                p[n++] = s.offX;
                p[n++] = s.offY;
                p[n++] = s.offZ;
                if (s.type == 1) {
                    p[n++] = s.extX;
                    p[n++] = s.extY;
                    p[n++] = s.extZ;
                    p[n++] = s.rotX;
                    p[n++] = s.rotY;
                    p[n++] = s.rotZ;
                } else if (s.type == 2) {
                    p[n++] = s.radius;
                }
            }
            return p;
        }
    }

    /**
     * Builds a script from raw script-file values, applying {@code VehicleScript.Load}'s scaling.
     */
    public static final class Builder {
        private final String name;
        private final float scale;
        private float moX, moY, moZ;
        private float mass = 800,
                roll = 1,
                stiff = 40,
                comp = 3.83F,
                damp = 2.88F,
                travel = 10,
                rest = 0.2F,
                friction = 1.4F,
                stopping = 4,
                engine = 4000,
                braking = 90,
                maxSpeed = 90;
        private float comX, comY, comZ, exX = 1, exY = 1, exZ = 2;
        private boolean chassis = true;
        private float chX = 1, chY = 1, chZ = 2;
        private final List<Wheel> wheels = new ArrayList<>();
        private final List<Shape> shapes = new ArrayList<>();
        private final Map<String, float[]> attachments = new LinkedHashMap<>();

        public Builder(String fullName, float modelScale) {
            this.name = fullName;
            this.scale = modelScale;
        }

        public Builder modelOffset(float x, float y, float z) {
            moX = x * scale;
            moY = y * scale;
            moZ = z * scale;
            return this;
        }

        public Builder mass(float m) {
            mass = m;
            return this;
        }

        public Builder handling(
                float rollInfluence,
                float stiffness,
                float compression,
                float damping,
                float travelCm,
                float restLength,
                float wheelFriction,
                float stoppingForce) {
            roll = rollInfluence;
            stiff = stiffness;
            comp = compression;
            damp = damping;
            travel = travelCm * scale;
            rest = restLength * scale;
            friction = wheelFriction;
            stopping = stoppingForce;
            return this;
        }

        public Builder engine(float engineForce, float brakingForce, float maxSpeedKmh) {
            engine = engineForce;
            braking = brakingForce;
            maxSpeed = maxSpeedKmh;
            return this;
        }

        public Builder centerOfMass(float x, float y, float z) {
            comX = x * scale;
            comY = y * scale;
            comZ = z * scale;
            return this;
        }

        public Builder extents(float x, float y, float z) {
            exX = x * scale;
            exY = y * scale;
            exZ = z * scale;
            return this;
        }

        /** {@code physicsChassisShape} with {@code useChassisPhysicsCollision = true}. */
        public Builder chassis(float x, float y, float z) {
            chassis = true;
            chX = x * scale;
            chY = y * scale;
            chZ = z * scale;
            return this;
        }

        /** {@code useChassisPhysicsCollision = false}. */
        public Builder noChassisCollision() {
            chassis = false;
            return this;
        }

        public Builder wheel(boolean front, float x, float y, float z, float radius) {
            wheels.add(new Wheel(front, x * scale, y * scale, z * scale, radius * scale));
            return this;
        }

        public Builder box(
                float ox,
                float oy,
                float oz,
                float ex,
                float ey,
                float ez,
                float rx,
                float ry,
                float rz) {
            shapes.add(
                    new Shape(
                            1,
                            ox * scale,
                            oy * scale,
                            oz * scale,
                            ex * scale,
                            ey * scale,
                            ez * scale,
                            rx,
                            ry,
                            rz,
                            0));
            return this;
        }

        public Builder sphere(float ox, float oy, float oz, float radius) {
            shapes.add(
                    new Shape(
                            2,
                            ox * scale,
                            oy * scale,
                            oz * scale,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            radius * scale));
            return this;
        }

        /**
         * type 3: a physics-shape mesh; {@code toBullet} writes only type and offset, the points
         * come later through {@code defineVehiclePhysicsMesh}.
         */
        public Builder mesh(float ox, float oy, float oz) {
            shapes.add(new Shape(3, ox * scale, oy * scale, oz * scale, 0, 0, 0, 0, 0, 0, 0));
            return this;
        }

        /** {@code attachment <id> { offset = x y z }}, scaled like {@code VehicleScript.Load}. */
        public Builder attachment(String id, float x, float y, float z) {
            attachments.put(id, new float[] {x * scale, y * scale, z * scale});
            return this;
        }

        public Script build() {
            return new Script(
                    name,
                    scale,
                    moX,
                    moY,
                    moZ,
                    mass,
                    roll,
                    stiff,
                    comp,
                    damp,
                    travel,
                    rest,
                    friction,
                    stopping,
                    wheels.toArray(new Wheel[0]),
                    comX,
                    comY,
                    comZ,
                    exX,
                    exY,
                    exZ,
                    chassis,
                    chX,
                    chY,
                    chZ,
                    shapes.toArray(new Shape[0]),
                    engine,
                    braking,
                    maxSpeed,
                    Map.copyOf(attachments));
        }
    }

    /** Base.CarNormal (vehicle_car_normal*.txt + CarNormalCollision template), B42.20. */
    public static Script carNormal() {
        return new Builder("Base.CarNormal", 1.82F)
                .modelOffset(0.0F, 0.2692F, 0.0F)
                .mass(800)
                .handling(1.0F, 40.0F, 3.83F, 2.88F, 10.0F, 0.2F, 1.4F, 4.0F)
                .engine(4000, 90, 90)
                .centerOfMass(0.0F, 0.3022F, 0.0F)
                .extents(0.8901F, 0.6484F, 2.6044F)
                .noChassisCollision()
                .wheel(true, 0.3626F, -0.3022F, 0.8516F, 0.15F)
                .wheel(true, -0.3626F, -0.3022F, 0.8516F, 0.15F)
                .wheel(false, 0.3626F, -0.3022F, -0.6099F, 0.15F)
                .wheel(false, -0.3626F, -0.3022F, -0.6099F, 0.15F)
                .box(0.0F, 0.1575F, -0.0055F, 0.8681F, 0.3187F, 2.5385F, 0, 0, 0)
                .box(0.0F, 0.3626F, 0.3113F, 0.8022F, 0.1758F, 0.4945F, 41, 0, 0)
                .box(0.0F, 0.3846F, -0.602F, 0.8022F, 0.3956F, 0.1868F, 44, 0, 0)
                .box(0.0F, 0.4554F, -0.1758F, 0.8022F, 0.2747F, 0.7143F, 0, 0, 0)
                .attachment(TRAILER, 0.0F, -0.2747F, -1.3462F)
                .attachment(TRAILER_FRONT, 0.0F, -0.2747F, 1.3187F)
                .build();
    }

    public static final String TRAILER = "trailer";
    public static final String TRAILER_FRONT = "trailerfront";

    /** Base.PickUpTruck (vehicle_pickuptruck.txt), B42.20. */
    public static Script pickUpTruck() {
        return new Builder("Base.PickUpTruck", 1.82F)
                .modelOffset(0.0F, 0.3022F, 0.0F)
                .mass(1030)
                .handling(0.8F, 40.0F, 3.83F, 2.88F, 10.0F, 0.2F, 1.5F, 1.0F)
                .engine(4000, 80, 70)
                .centerOfMass(0.0F, 0.3022F, 0.0F)
                .extents(0.8681F, 0.6593F, 2.1868F)
                .noChassisCollision()
                .wheel(true, 0.3462F, -0.3956F, 0.7582F, 0.15F)
                .wheel(true, -0.3462F, -0.3956F, 0.7582F, 0.15F)
                .wheel(false, 0.3462F, -0.3956F, -0.5879F, 0.15F)
                .wheel(false, -0.3462F, -0.3956F, -0.5879F, 0.15F)
                .box(0.0F, 0.1801F, -0.0055F, 0.8242F, 0.3956F, 2.2088F, 0, 0, 0)
                .box(0.0F, 0.4121F, 0.387F, 0.6923F, 0.1758F, 0.4066F, 53, 0, 0)
                .box(0.0F, 0.4341F, -0.0298F, 0.6923F, 0.3626F, 0.1758F, 9, 0, 0)
                .box(0.0F, 0.4908F, 0.1264F, 0.6923F, 0.2747F, 0.4176F, 0, 0, 0)
                .attachment(TRAILER, 0.0F, -0.2747F, -1.1813F)
                .attachment(TRAILER_FRONT, 0.0F, -0.2747F, 1.1374F)
                .build();
    }

    /** Base.SportsCar (vehicle_sportscar.txt), B42.20; braking from the car template. */
    public static Script sportsCar() {
        return new Builder("Base.SportsCar", 1.82F)
                .modelOffset(0.0F, 0.2473F, 0.0F)
                .mass(800)
                .handling(0.7F, 50.0F, 4.1F, 3.4F, 20.0F, 0.2F, 1.8F, 2.0F)
                .engine(5700, 90, 120)
                .centerOfMass(0.0F, 0.2473F, 0.0F)
                .extents(0.7802F, 0.5055F, 2.0549F)
                .noChassisCollision()
                .wheel(true, 0.3242F, -0.2143F, 0.5879F, 0.15F)
                .wheel(true, -0.3242F, -0.2143F, 0.5879F, 0.15F)
                .wheel(false, 0.3352F, -0.2143F, -0.5659F, 0.15F)
                .wheel(false, -0.3352F, -0.2143F, -0.5659F, 0.15F)
                .box(0.0F, 0.1703F, -0.0031F, 0.7912F, 0.2747F, 2.0659F, 1, 0, 0)
                .box(0.0F, 0.3044F, 0.1593F, 0.6813F, 0.1758F, 0.4396F, 32, 0, 0)
                .box(0.0F, 0.3276F, -0.7143F, 0.6813F, 0.5165F, 0.1538F, 68, 0, 0)
                .box(0.0F, 0.3758F, -0.2418F, 0.6813F, 0.2418F, 0.5275F, 0, 0, 0)
                .attachment(TRAILER, 0.0F, -0.1356F, -1.0696F)
                .attachment(TRAILER_FRONT, 0.0F, -0.1356F, 1.0769F)
                .build();
    }

    /** Base.Van (vehicle_van.txt), B42.20. */
    public static Script van() {
        return new Builder("Base.Van", 1.82F)
                .modelOffset(0.0F, 0.3681F, 0.0F)
                .mass(816)
                .handling(0.7F, 30.0F, 3.83F, 2.88F, 10.0F, 0.2F, 1.4F, 5.0F)
                .engine(3700, 70, 65)
                .centerOfMass(0.0F, 0.3626F, 0.0F)
                .extents(0.9341F, 0.7253F, 2.3297F)
                .noChassisCollision()
                .wheel(true, 0.3791F, -0.4121F, 0.8681F, 0.15F)
                .wheel(true, -0.3791F, -0.4121F, 0.8681F, 0.15F)
                .wheel(false, 0.3791F, -0.4121F, -0.5385F, 0.15F)
                .wheel(false, -0.3791F, -0.4121F, -0.5385F, 0.15F)
                .box(0.0F, 0.2168F, -0.0055F, 0.8956F, 0.4451F, 2.2857F, 0, 0, 0)
                .box(0.0F, 0.5181F, 0.6593F, 0.8956F, 0.2308F, 0.3681F, 52, 0, 0)
                .box(0.0F, 0.5879F, -0.2543F, 0.8956F, 0.2912F, 1.7857F, 0, 0, 0)
                .attachment(TRAILER, 0.0F, -0.2747F, -1.1703F)
                .attachment(TRAILER_FRONT, 0.0F, -0.2747F, 1.1758F)
                .build();
    }

    /** Base.Trailer (vehicle_trailer.txt), B42.20: two wheels, drawbar boxes + hitch sphere. */
    public static Script trailer() {
        return new Builder("Base.Trailer", 1.82F)
                .modelOffset(0.0F, 0.1868F, 0.1374F)
                .mass(200)
                .handling(1.0F, 40.0F, 2.83F, 2.88F, 10.0F, 0.2F, 4.0F, 2.0F)
                .engine(3600, 1, 70)
                .centerOfMass(0.0F, 0.2198F, -0.1813F)
                .extents(0.6264F, 0.2198F, 0.9341F)
                .noChassisCollision()
                .wheel(true, 0.3736F, -0.1868F, -0.3022F, 0.15F)
                .wheel(true, -0.3736F, -0.1868F, -0.3022F, 0.15F)
                .box(0.1099F, 0.0989F, 0.5385F, 0.0549F, 0.0549F, 0.5385F, 0, -20, 0)
                .box(-0.1099F, 0.0989F, 0.5385F, 0.0549F, 0.0549F, 0.5385F, 0, 20, 0)
                .sphere(0.0F, 0.0989F, 0.7582F, 0.0549F)
                .box(0.0F, 0.2148F, -0.1868F, 0.6374F, 0.2198F, 0.956F, 0, 0, 0)
                .attachment(TRAILER, 0.0F, -0.0879F, 0.8162F)
                .build();
    }

    /**
     * Synthetic: a wheel-less wreck-like body whose collision is the chassis box plus one type-3
     * mesh shape (physics-shape script), so {@code defineVehiclePhysicsMesh(name, 1 + 0, ...)}
     * addresses the mesh exactly as {@code Bullet.initPhysicsMeshes} computes the index (it assumes
     * the chassis is native shape 0).
     */
    public static Script meshWreck() {
        return new Builder("Base.StormMeshWreck", 1.82F)
                .modelOffset(0.0F, 0.2F, 0.0F)
                .mass(600)
                .handling(1.0F, 40.0F, 3.83F, 2.88F, 10.0F, 0.2F, 1.4F, 4.0F)
                .engine(0, 90, 0)
                .centerOfMass(0.0F, 0.25F, 0.0F)
                .extents(0.85F, 0.5F, 2.2F)
                .chassis(0.85F, 0.3F, 2.2F)
                .mesh(0.0F, 0.35F, 0.0F)
                .attachment(TRAILER, 0.0F, -0.1F, -1.2F)
                .attachment(TRAILER_FRONT, 0.0F, -0.1F, 1.2F)
                .build();
    }
}
