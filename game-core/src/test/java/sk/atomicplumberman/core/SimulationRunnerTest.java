package sk.atomicplumberman.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SimulationRunnerTest {
    private static final long MS = 1_000_000L;
    private int steps;

    @ParameterizedTest @ValueSource(ints = {30, 60, 144})
    void frameRateDoesNotChangeTheTickCount(int fps) {
        var runner = new SimulationRunner(8);
        runner.resume(0);
        for (int frame = 1; frame <= fps * 10; frame++) runner.advance(frame * 1_000_000_000L / fps, () -> steps++);
        assertEquals(600, steps);
    }

    @Test void pauseIsNotCaughtUp() {
        var runner = new SimulationRunner(8);
        runner.resume(0);
        runner.advance(100 * MS, () -> steps++);
        runner.pause();
        assertEquals(0, runner.advance(5_000 * MS, () -> steps++));
        runner.resume(5_000 * MS);
        runner.advance(5_100 * MS, () -> steps++);
        assertEquals(12, steps, "100 ms before and 100 ms after the pause");
    }

    @Test void aShortHitchIsWorkedOffInBatches() {
        var runner = new SimulationRunner(8);
        runner.resume(0);
        assertEquals(8, runner.advance(400 * MS, () -> steps++), "24 ticks due, one batch of 8");
        assertTrue(runner.lagNanos(400 * MS) > 250 * MS);
        for (int frame = 1; frame <= 10; frame++) runner.advance(400 * MS + frame * 16_666_667L, () -> steps++);
        assertFalse(runner.consumeStall());
        assertEquals(34, steps, "caught up with the clock, nothing skipped");
    }

    @Test void aSustainedLagOrALongFreezeStopsInsteadOfFastForwarding() {
        var slow = new SimulationRunner(1); // can only run one tick per frame, two are due
        slow.resume(0);
        long now = 0;
        while (slow.running() && now < 5_000 * MS) slow.advance(now += 33 * MS, () -> steps++);
        assertTrue(slow.consumeStall());
        assertFalse(slow.consumeStall());
        assertTrue(now < 1_000 * MS, "lag above 250 ms for 250 ms is diagnosed early, at " + now / MS + " ms");

        steps = 0;
        var frozen = new SimulationRunner(8);
        frozen.resume(0);
        assertEquals(0, frozen.advance(2_000 * MS, () -> steps++));
        assertTrue(frozen.consumeStall());
        assertEquals(0, steps, "two unseen seconds are not played");
    }

    @Test void alphaInterpolatesBetweenTicks() {
        var runner = new SimulationRunner(8);
        runner.resume(0);
        runner.advance(25 * MS, () -> steps++);
        assertEquals(1, steps);
        assertEquals(.5f, runner.alpha(25 * MS), .01f);
    }
}
