package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StormMaxPlayersConfigTest implements UnitTest {

    @AfterEach
    void restoreDefaults() {
        StormMaxPlayersConfig.setOverride(
                StormMaxPlayersConfig.DEFAULT_OVERRIDE_ENABLED,
                StormMaxPlayersConfig.DEFAULT_MAX_PLAYERS);
    }

    @Test
    void disabledOverridePassesTheVanillaValueThrough() {
        StormMaxPlayersConfig.setOverride(false, 250);
        assertFalse(StormMaxPlayersConfig.isOverrideEnabled());
        assertEquals(37, StormMaxPlayersConfig.overrideOrVanilla(37));
    }

    @Test
    void enabledOverrideReplacesTheVanillaValue() {
        StormMaxPlayersConfig.setOverride(true, 250);
        assertTrue(StormMaxPlayersConfig.isOverrideEnabled());
        assertEquals(250, StormMaxPlayersConfig.overrideOrVanilla(37));
    }

    @Test
    void valueIsClampedToTheDeclaredRange() {
        assertEquals(
                StormMaxPlayersConfig.MIN_MAX_PLAYERS, StormMaxPlayersConfig.setOverride(true, 0));
        assertEquals(
                StormMaxPlayersConfig.MAX_MAX_PLAYERS,
                StormMaxPlayersConfig.setOverride(true, 9999));
        assertEquals(
                StormMaxPlayersConfig.MAX_MAX_PLAYERS,
                StormMaxPlayersConfig.getConfiguredMaxPlayers());
    }

    @Test
    void defaultsLeaveTheIniValueInControl() {
        assertFalse(StormMaxPlayersConfig.DEFAULT_OVERRIDE_ENABLED);
        assertEquals(100, StormMaxPlayersConfig.DEFAULT_MAX_PLAYERS);
        assertEquals(64, StormMaxPlayersConfig.overrideOrVanilla(64));
    }
}
