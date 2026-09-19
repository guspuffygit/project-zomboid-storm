package io.pzstorm.storm.bullet.trace.scenario;

import java.util.List;

/**
 * Every scenario in one library session, in game order: the library is booted once ({@link
 * GameSim#boot} checks {@link ScenarioContext#booted}, as {@code Bullet.init} runs once per game
 * process) and the ragdoll builder is initialised once ({@code RagdollBuilder.instance}), while
 * worlds are created and destroyed between parts as the game does across loads of a save ({@code
 * WorldSimulation.create / destroy}). Tests that state leaks correctly between worlds.
 */
public final class SuiteScenario implements Scenario {

    @Override
    public String name() {
        return "suite";
    }

    @Override
    public String description() {
        return "all scenarios in one session: one boot, one ragdoll builder, a world per part";
    }

    static List<Scenario> parts() {
        return List.of(
                new BasicDriveScenario(),
                new VehiclesScenario(),
                new TowingScenario(),
                new PhysicsObjectsScenario(),
                new RagdollScenario(),
                new BallisticsScenario(),
                new ServerScenario(),
                new RandomScenario());
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        for (Scenario s : parts()) {
            ctx.note("suite: " + s.name());
            // per-world state the game resets on load; JVM-wide state (boot, builder) is kept
            ctx.onImpulse = null;
            ctx.drainImpulses();
            ctx.terrain.meshCount = 0;
            ctx.terrain.density = 0.25;
            s.run(ctx);
        }
    }
}
