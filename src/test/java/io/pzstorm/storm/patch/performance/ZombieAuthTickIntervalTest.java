package io.pzstorm.storm.patch.performance;

import java.lang.reflect.Field;
import java.util.HashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoZombie;
import zombie.characters.component.NetworkZombieComponent;
import zombie.characters.ecs.ECSComponent;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

class ZombieAuthTickIntervalTest {

    private boolean savedServerFlag;
    private long savedFrame;

    @BeforeEach
    void captureState() {
        savedServerFlag = GameServer.server;
        savedFrame = MovingObjectUpdateScheduler.instance.getFrameCounter();
    }

    @AfterEach
    void resetInterval() throws Exception {
        GameServer.server = savedServerFlag;
        setFrame(savedFrame);
        ZombieAuthTickInterval.setCurrentTickIntervalForTest(
                ZombieAuthTickInterval.DEFAULT_TICK_INTERVAL);
    }

    @Test
    void strideOneAlwaysRuns() {
        for (long frame = 0; frame < 20; frame++) {
            Assertions.assertTrue(ZombieAuthTickInterval.shouldRunForZombie(1, frame, 7));
        }
    }

    @Test
    void strideFourRunsExactlyOncePerWindowPerZombie() {
        // Include a negative id: IsoZombie.getOnlineID() returns a short that can wrap negative.
        for (int id : new int[] {0, 1, 3, 12345, -7, Short.MIN_VALUE}) {
            for (long windowStart = 0; windowStart < 40; windowStart += 4) {
                int runs = 0;
                for (long frame = windowStart; frame < windowStart + 4; frame++) {
                    if (ZombieAuthTickInterval.shouldRunForZombie(4, frame, id)) {
                        runs++;
                    }
                }
                Assertions.assertEquals(1, runs, "id=" + id + " window at " + windowStart);
            }
        }
    }

    @Test
    void zombiesArePhaseSpreadByOnlineId() {
        // At any single frame, ids 0..3 with stride 4 must land on 4 distinct phases —
        // exactly one of them runs.
        long frame = 100;
        int runs = 0;
        for (int id = 0; id < 4; id++) {
            if (ZombieAuthTickInterval.shouldRunForZombie(4, frame, id)) {
                runs++;
            }
        }
        Assertions.assertEquals(1, runs);
    }

    @Test
    void setterClampsToBounds() {
        Assertions.assertEquals(
                ZombieAuthTickInterval.MIN_TICK_INTERVAL,
                ZombieAuthTickInterval.setTickInterval(0));
        Assertions.assertEquals(
                ZombieAuthTickInterval.MAX_TICK_INTERVAL,
                ZombieAuthTickInterval.setTickInterval(999));
        Assertions.assertEquals(4, ZombieAuthTickInterval.setTickInterval(4));
        Assertions.assertEquals(4, ZombieAuthTickInterval.getCurrentTickInterval());
    }

    @Test
    void ownedAndUnownedZombiesTakeTheSameStride() throws Exception {
        GameServer.server = true;
        ZombieAuthTickInterval.setCurrentTickIntervalForTest(4);
        IsoZombie owned = zombie((short) 7, connection());
        IsoZombie unowned = zombie((short) 7, null);

        int scans = 0;
        for (long frame = 100; frame < 108; frame++) {
            setFrame(frame);
            boolean skip = ZombieAuthTickInterval.shouldSkipThisTick(owned);
            Assertions.assertEquals(!ZombieAuthTickInterval.shouldRunForZombie(4, frame, 7), skip);
            Assertions.assertEquals(skip, ZombieAuthTickInterval.shouldSkipThisTick(unowned));
            if (!skip) {
                scans++;
            }
        }
        Assertions.assertEquals(2, scans);
    }

    @Test
    void aSkipNeverProbesTheComponentMap() throws Exception {
        GameServer.server = true;
        ZombieAuthTickInterval.setCurrentTickIntervalForTest(4);
        IsoZombie zombie = (IsoZombie) unsafe().allocateInstance(IsoZombie.class);
        zombie.onlineId = 7;
        setFrame(8);

        Assertions.assertTrue(ZombieAuthTickInterval.shouldSkipThisTick(zombie));
    }

    @Test
    void strideOneNeverSkips() throws Exception {
        GameServer.server = true;
        IsoZombie zombie = zombie((short) 7, connection());
        setFrame(8);

        Assertions.assertFalse(ZombieAuthTickInterval.shouldSkipThisTick(zombie));
    }

    @Test
    void offTheServerNothingSkips() throws Exception {
        GameServer.server = false;
        ZombieAuthTickInterval.setCurrentTickIntervalForTest(4);
        IsoZombie zombie = zombie((short) 7, connection());
        setFrame(8);

        Assertions.assertFalse(ZombieAuthTickInterval.shouldSkipThisTick(zombie));
    }

    private static UdpConnection connection() throws Exception {
        return (UdpConnection) unsafe().allocateInstance(UdpConnection.class);
    }

    private static IsoZombie zombie(short onlineId, UdpConnection owner) throws Exception {
        IsoZombie zombie = (IsoZombie) unsafe().allocateInstance(IsoZombie.class);
        zombie.onlineId = onlineId;
        HashMap<Class<? extends ECSComponent>, ECSComponent> components = new HashMap<>();
        Field map = IsoObject.class.getDeclaredField("ecsComponentMap");
        map.setAccessible(true);
        map.set(zombie, components);
        NetworkZombieComponent network =
                (NetworkZombieComponent) unsafe().allocateInstance(NetworkZombieComponent.class);
        network.setAuthOwner(owner);
        components.put(ECSComponent.getECSClass(NetworkZombieComponent.class), network);
        return zombie;
    }

    private static void setFrame(long frame) throws Exception {
        Field counter = MovingObjectUpdateScheduler.class.getDeclaredField("frameCounter");
        counter.setAccessible(true);
        counter.setLong(MovingObjectUpdateScheduler.instance, frame);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
