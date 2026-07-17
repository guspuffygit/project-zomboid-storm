package io.pzstorm.storm.diagnostics;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RamAllocationLagKeywordTest implements UnitTest {

    @Test
    void shouldMatchLagComplaints() {
        String[] complaints = {
            "lag",
            "the server is lagging?",
            "I am lagging",
            "why is it so LAGGY",
            "lags so much rn",
            "lagged out again",
            "constant stutter",
            "game keeps stuttering",
            "my game is freezing",
            "it just freezes",
            "so choppy today",
            "im rubberbanding everywhere",
            "rubber banding again",
            "zombies desync all the time",
            "my fps is terrible",
            "frame rate is awful",
            "framerate tanked",
            "game is a slideshow",
            "this is unplayable",
        };
        for (String text : complaints) {
            Assertions.assertTrue(
                    RamAllocationTracker.mentionsLag(text), "expected match: " + text);
        }
    }

    @Test
    void shouldNotMatchOrdinaryChat() {
        String[] ordinary = {
            "meet me at the lake",
            "found a flag in the school",
            "anyone have nails?",
            "the antifreeze is in the garage",
            "slagheap street is clear",
            "nice base",
        };
        for (String text : ordinary) {
            Assertions.assertFalse(
                    RamAllocationTracker.mentionsLag(text), "expected no match: " + text);
        }
    }
}
