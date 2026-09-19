package io.pzstorm.storm.bullet.trace.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry of the built-in scenarios. */
public final class Scenarios {

    private Scenarios() {}

    public static List<Scenario> all() {
        return List.of(
                new BasicDriveScenario(),
                new VehiclesScenario(),
                new TowingScenario(),
                new PhysicsObjectsScenario(),
                new RagdollScenario(),
                new BallisticsScenario(),
                new ServerScenario(),
                new RandomScenario(),
                new SuiteScenario());
    }

    public static Map<String, Scenario> byName() {
        Map<String, Scenario> m = new LinkedHashMap<>();
        for (Scenario s : all()) {
            m.put(s.name(), s);
        }
        return m;
    }

    public static Scenario get(String name) {
        Scenario s = byName().get(name);
        if (s == null) {
            throw new IllegalArgumentException(
                    "unknown scenario '" + name + "', known: " + byName().keySet());
        }
        return s;
    }
}
