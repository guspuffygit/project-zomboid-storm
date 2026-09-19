package io.pzstorm.storm.bullet.trace.scenario;

/**
 * A synthetic, game-free driver of the physics library, modelled on how the game calls it. A
 * scenario must be deterministic for a given seed <em>and</em> given library outputs: it may react
 * to readbacks (as the game does), which is fine because replays feed the recorded inputs back, not
 * the scenario.
 */
public interface Scenario {

    /** Short unique name used on the command line. */
    String name();

    /** One line: what the scenario exercises. */
    String description();

    void run(ScenarioContext ctx) throws Exception;
}
