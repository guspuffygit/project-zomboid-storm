package io.pzstorm.storm.bullet.trace.scenario;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The server flavour of a world: {@code initWorld(…, true)}, several players each with their own
 * chunk-map slot, the player list sent through the command buffer as they move, server cells, and
 * unmanned vehicles simulated server-side while drivers' own physics comes in over the network.
 *
 * <p>Synthetic: the 42.20 dedicated server never initialises the library ({@code Bullet.init} is
 * client-only), so there is no single game caller for this flavour; the individual calls mirror
 * {@code WorldSimulation.create} with {@code GameServer.server}, {@code
 * IsoChunk.updatePlayerInBullet → Bullet.updatePlayerList} (TO_UPDATE_PLAYER_LIST via cmdBuf),
 * {@code IsoChunkMap} slots 1..3 ({@code activateChunkMap / scrollChunkMap / deactivateChunkMap}),
 * {@code VehicleManager} / {@code VehiclePhysicsPacket} ({@code setOwnVehiclePhysics /
 * getOwnVehiclePhysics}) and the createServerCell / removeServerCell natives (no Java caller).
 */
public final class ServerScenario implements Scenario {

    @Override
    public String name() {
        return "server";
    }

    @Override
    public String description() {
        return "server world: 4 players with chunk-map slots, player list via cmd, server cells,"
                + " server-simulated and network-driven vehicles";
    }

    private static final class Player {
        final int onlineId;
        final int slot;
        float x;
        float y;
        final float dx;
        final float dy;

        Player(int onlineId, int slot, float x, float y, float dx, float dy) {
            this.onlineId = onlineId;
            this.slot = slot;
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
        }
    }

    @Override
    public void run(ScenarioContext ctx) {
        GameSim sim = new GameSim(ctx);
        sim.boot();
        VehicleScripts.Script[] scripts = {
            VehicleScripts.carNormal(), VehicleScripts.van(), VehicleScripts.pickUpTruck()
        };
        for (VehicleScripts.Script s : scripts) {
            ctx.bullet.defineVehicleScript(s.fullName(), s.toBullet());
        }
        float px = 10620.5F + ctx.range(-40, 40);
        float py = 9860.5F + ctx.range(-40, 40);
        sim.createWorld(0, 0, 66, 63, px, py, 13, true);

        List<Player> players = new ArrayList<>();
        players.add(new Player(ctx.random.nextInt(32), 0, px, py, 0.05F, 0.0F));
        for (int slot = 1; slot < 4; slot++) {
            float x = px + ctx.range(-60, 60), y = py + ctx.range(-60, 60);
            double a = ctx.random.nextDouble() * Math.PI * 2;
            players.add(
                    new Player(
                            32 + slot * 5 + ctx.random.nextInt(5),
                            slot,
                            x,
                            y,
                            0.06F * (float) Math.cos(a),
                            0.06F * (float) Math.sin(a)));
            sim.activateChunkMap(slot, x, y);
        }
        sendPlayers(ctx, players);

        // Server cells (40 squares, 5 x 5 chunks) are the only terrain of a server world:
        // WorldSimulation::getChunk / getChunkForAnyPlayer look only at cells when the world is a
        // server, and a chunk map's own chunks never receive shapes. Every parked vehicle gets the
        // 3 x 3 cells around it, created before it spawns, and their chunks stream in.
        //
        // The chunk maps never scroll here. ChunkMap::scroll* adopts the incoming edge chunk from
        // getChunkForAnyPlayer (in a server world, a cell's chunk) and deletes the outgoing one
        // when no map references it, although the cell still owns it; removeServerCell then
        // deletes chunks a map still holds. Both are use-after-frees in the native library (seen
        // as crashes in Chunk::setNeed during stepSimulation and in ~Chunk under
        // removeServerCell). activateChunkMap adopts only other maps' chunks and creates fresh
        // ones for the rest, so static maps never touch a cell's chunks. No game build pairs
        // server cells with moving chunk maps (the 42.20 dedicated server never initialises the
        // library), so the scenario does not either.
        Cells cells = new Cells(ctx, sim);
        List<float[]> spots = new ArrayList<>();
        List<Set<Long>> vehicleCells = new ArrayList<>();
        for (Player p : players) {
            // open ground, and apart from the other players' vehicles (overlapping bodies are
            // pushed out through the floor)
            float[] at =
                    ctx.terrain.clearSpot(
                            p.x + ctx.range(-5, 5),
                            p.y + ctx.range(-5, 5),
                            new float[] {0, 0},
                            (x, y) -> near(spots, x, y));
            spots.add(at);
            vehicleCells.add(cells.around(at[0], at[1]));
        }

        Fleet fleet = new Fleet(ctx, sim);
        List<SimVehicle> parked = new ArrayList<>();
        for (int i = 0; i < spots.size(); i++) {
            float[] at = spots.get(i);
            parked.add(
                    fleet.spawn(
                            scripts[ctx.random.nextInt(scripts.length)],
                            at[0],
                            at[1],
                            ctx.random.nextDouble() * Math.PI * 2));
        }
        fleet.settle(30);
        Remote remote = new Remote();

        ctx.note("players move");
        float[] own = new float[27];
        for (int f = 0; f < 600; f++) {
            for (Player p : players) {
                // players walk; their chunk maps stay where they were activated (see above)
                p.x += p.dx;
                p.y += p.dy;
            }
            if (f % 10 == 0) {
                sendPlayers(ctx, players);
            }
            // a driver's client reports; the server applies it to the (unowned) body
            SimVehicle driven = parked.get(1);
            if (f >= 60 && f < 400 && !driven.removed && driven.state != null) {
                driveRemote(ctx, remote, driven, own, f);
            }
            // the server-side AI nudge of an unmanned vehicle
            SimVehicle loose = parked.get(2);
            if (f == 200 && !loose.removed) {
                loose.applyNetImpulse(
                        ctx.range(-3000, 3000), 1500, ctx.range(-3000, 3000), 0, 0, 0);
            }
            if (f == 300) {
                // a player leaves: its vehicle unloads, then its chunk-map slot and the cells no
                // other vehicle stands on go away
                parked.get(3).remove();
                fleet.frame(1);
                sim.deactivateChunkMap(3);
                Set<Long> keep = new LinkedHashSet<>();
                for (int i = 0; i < 3; i++) {
                    keep.addAll(vehicleCells.get(i));
                }
                for (long c : vehicleCells.get(3)) {
                    if (!keep.contains(c)) {
                        cells.remove(c);
                    }
                }
                players.remove(3);
                sendPlayers(ctx, players);
            }
            if (f == 420) {
                // and joins again elsewhere, with a vehicle of its own
                Player p = new Player(60 + ctx.random.nextInt(10), 3, px + 20, py - 20, 0, 0.05F);
                players.add(p);
                sim.activateChunkMap(3, p.x, p.y);
                float[] at = fleet.clearSpot(p.x + 3, p.y + 3);
                cells.around(at[0], at[1]);
                parked.add(
                        fleet.spawn(
                                scripts[ctx.random.nextInt(scripts.length)],
                                at[0],
                                at[1],
                                ctx.random.nextDouble() * Math.PI * 2));
                sendPlayers(ctx, players);
            }
            fleet.frame(Fleet.substeps(f));
            if (f % 20 == 0) {
                for (SimVehicle v : fleet.live()) {
                    ctx.bullet.getOwnVehiclePhysics(v.id, own); // VehiclePhysicsPacket.set
                }
            }
        }

        ctx.note("teardown");
        sendPlayers(ctx, new ArrayList<>());
        fleet.noteHeights();
        fleet.removeAll();
        fleet.frame(1);
        cells.removeAll();
        for (int slot = 1; slot < 4; slot++) {
            sim.deactivateChunkMap(slot);
        }
        sim.destroyWorld();
    }

    /** The server cells created so far, in creation order. */
    private static final class Cells {
        final ScenarioContext ctx;
        final GameSim sim;
        final Set<Long> live = new LinkedHashSet<>();

        Cells(ScenarioContext ctx, GameSim sim) {
            this.ctx = ctx;
            this.sim = sim;
        }

        /** Creates (if needed) the 3 x 3 cells around square (x, y); returns their keys. */
        Set<Long> around(float x, float y) {
            int cx = Math.floorDiv((int) Math.floor(x), CELL);
            int cy = Math.floorDiv((int) Math.floor(y), CELL);
            Set<Long> out = new LinkedHashSet<>();
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    long k = key(cx + dx, cy + dy);
                    out.add(k);
                    if (live.add(k)) {
                        create(cx + dx, cy + dy);
                    }
                }
            }
            return out;
        }

        /** createServerCell, then the cell's 25 chunks stream in. */
        private void create(int cx, int cy) {
            ctx.bullet.createServerCell(cx, cy);
            int per = CELL / GameSim.CHUNK_SQUARES;
            for (int y = 0; y < per; y++) {
                for (int x = 0; x < per; x++) {
                    sim.loadChunk(cx * per + x, cy * per + y);
                }
            }
        }

        void remove(long k) {
            if (live.remove(k)) {
                ctx.bullet.removeServerCell((int) (k >> 32), (int) k);
            }
        }

        void removeAll() {
            for (long k : new ArrayList<>(live)) {
                remove(k);
            }
        }

        static long key(int cx, int cy) {
            return ((long) cx << 32) | (cy & 0xFFFFFFFFL);
        }
    }

    /** {@code ServerCell}: 40 squares on a side (5 chunks of 8). */
    private static final int CELL = 40;

    /** Whether (x, y) lies within {@link Fleet#SPACING} squares of any chosen spot. */
    private static boolean near(List<float[]> spots, float x, float y) {
        for (float[] s : spots) {
            float dx = s[0] - x, dy = s[1] - y;
            if (dx * dx + dy * dy < Fleet.SPACING * Fleet.SPACING) {
                return true;
            }
        }
        return false;
    }

    private static void sendPlayers(ScenarioContext ctx, List<Player> players) {
        int[] ids = new int[players.size()];
        float[] xs = new float[players.size()];
        float[] ys = new float[players.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = players.get(i).onlineId;
            xs[i] = players.get(i).x;
            ys[i] = players.get(i).y;
        }
        ctx.cmd.updatePlayerList(ids, xs, ys);
    }

    /** Where the remote driver's client has its car (its own physics collides, ours does not). */
    private static final class Remote {
        boolean started;
        float x;
        float y;
        float height;
        boolean stopped;
    }

    /**
     * {@code VehicleManager.updateVehiclePos}: a moving remote-driven vehicle, float[27] = world x,
     * world y, physics height, rotation xyzw, velocity xyz, wheel count, 4 per wheel (the layout
     * {@code getOwnVehiclePhysics} writes). The driver steers gently and brakes to a stop in front
     * of a tree / solid / wall, as a client whose physics collides would.
     */
    private static void driveRemote(
            ScenarioContext ctx, Remote r, SimVehicle v, float[] dd, int f) {
        GameSim.VehicleState s = v.state;
        if (!r.started) {
            r.started = true;
            r.x = v.worldX();
            r.y = v.worldY();
            r.height = s.y();
        }
        double yaw = 0.3 * Math.sin(f / 90.0);
        float speed = r.stopped ? 0.0F : 5.0F;
        float vx = (float) Math.sin(yaw) * speed, vz = (float) Math.cos(yaw) * speed;
        float nx = r.x + vx / 60.0F, ny = r.y + vz / 60.0F;
        float lookX = r.x + (float) Math.sin(yaw) * 3.0F,
                lookY = r.y + (float) Math.cos(yaw) * 3.0F;
        if (!r.stopped
                && !ctx.terrain.isClear((int) Math.floor(lookX), (int) Math.floor(lookY), 1, 1)) {
            r.stopped = true;
            vx = 0;
            vz = 0;
            nx = r.x;
            ny = r.y;
        }
        r.x = nx;
        r.y = ny;
        float[] q = GameSim.yawQuat(yaw);
        int i = 0;
        dd[i++] = r.x;
        dd[i++] = r.y;
        dd[i++] = r.height;
        dd[i++] = q[0];
        dd[i++] = q[1];
        dd[i++] = q[2];
        dd[i++] = q[3];
        dd[i++] = vx;
        dd[i++] = 0.0F;
        dd[i++] = vz;
        int wheels = Math.min(4, s.wheels().length / 4);
        dd[i++] = wheels + 0.1F; // getOwnVehiclePhysics writes count + 0.1
        for (int w = 0; w < wheels * 4; w++) {
            dd[i++] = s.wheels()[w];
        }
        while (i < dd.length) {
            dd[i++] = 0;
        }
        boolean collide = f % 3 == 0;
        ctx.bullet.setOwnVehiclePhysics(v.id, dd, collide);
        if (collide) {
            ctx.bullet.getOwnVehiclePhysics(v.id, dd);
            r.x = dd[0];
            r.y = dd[1];
        }
    }
}
