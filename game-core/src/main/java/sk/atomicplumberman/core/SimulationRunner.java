package sk.atomicplumberman.core;

/**
 * Schedules fixed ticks against a monotonic clock that the caller passes in, so the core reads no clock.
 * It never drops a tick silently: due ticks run in bounded batches. A short hitch is worked off; a lag
 * above {@link #MAX_LAG_NANOS} that persists for as long again, or a freeze above {@link #FREEZE_NANOS},
 * stops the runner and reports a stall instead of fast-forwarding unseen play. Paused time is never caught up.
 */
// ponytail: runs on the caller's thread (render thread locally, the loop thread of the LAN host); no snapshots needed yet
public final class SimulationRunner {
    public static final long MAX_LAG_NANOS = 250_000_000L;
    public static final long FREEZE_NANOS = 1_000_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private final int maxBatch;
    private long anchorNanos;
    private long anchorTicks;
    private long ticks;
    private boolean running;
    private boolean stalled;
    private long laggingSince = -1;

    public SimulationRunner(int maxBatch) {
        if (maxBatch < 1) throw new IllegalArgumentException("maxBatch: expected at least 1");
        this.maxBatch = maxBatch;
    }

    public void resume(long nowNanos) {
        anchorNanos = nowNanos;
        anchorTicks = ticks;
        running = true;
        stalled = false;
        laggingSince = -1;
    }

    public void pause() { running = false; }
    public boolean running() { return running; }
    public long ticks() { return ticks; }

    /** True once after the runner stopped itself because the simulation fell too far behind. */
    public boolean consumeStall() {
        boolean value = stalled;
        stalled = false;
        return value;
    }

    /** Runs the ticks that are due, at most one batch; returns how many ran. */
    public int advance(long nowNanos, Runnable step) {
        if (!running) return 0;
        long lag = (due(nowNanos) - ticks) * SECOND / Simulation.TICKS_PER_SECOND;
        if (lag <= MAX_LAG_NANOS) laggingSince = -1;
        else if (laggingSince < 0) laggingSince = nowNanos;
        if (lag > FREEZE_NANOS || laggingSince >= 0 && nowNanos - laggingSince > MAX_LAG_NANOS) {
            running = false;
            stalled = true;
            return 0;
        }
        long behind = due(nowNanos) - ticks;
        int count = (int) Math.min(behind, maxBatch);
        for (int i = 0; i < count && running; i++) {
            step.run();
            ticks++;
        }
        return count;
    }

    /** Time owed to the simulation, for diagnostics. */
    public long lagNanos(long nowNanos) {
        return running ? Math.max(0, (due(nowNanos) - ticks) * SECOND / Simulation.TICKS_PER_SECOND) : 0;
    }

    /** Progress from the last tick toward the next one, 0..1, for render interpolation. */
    public float alpha(long nowNanos) {
        if (!running) return 1;
        long elapsed = (nowNanos - anchorNanos) * Simulation.TICKS_PER_SECOND - (ticks - anchorTicks) * SECOND;
        return Math.clamp(elapsed / (float) SECOND, 0f, 1f);
    }

    private long due(long nowNanos) {
        return anchorTicks + (nowNanos - anchorNanos) * Simulation.TICKS_PER_SECOND / SECOND;
    }
}
