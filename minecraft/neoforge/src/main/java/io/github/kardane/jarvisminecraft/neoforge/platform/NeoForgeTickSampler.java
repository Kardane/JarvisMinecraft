package io.github.kardane.jarvisminecraft.neoforge.platform;

public final class NeoForgeTickSampler {
    private static final int WINDOW = 100;

    private final double[] samplesMs = new double[WINDOW];
    private int count;
    private int index;
    private long tickStartedNanos;

    public synchronized void beginTick(long nowNanos) {
        tickStartedNanos = nowNanos;
    }

    public synchronized void endTick(long nowNanos) {
        if (tickStartedNanos <= 0L || nowNanos < tickStartedNanos) {
            tickStartedNanos = 0L;
            return;
        }
        double elapsedMs = (nowNanos - tickStartedNanos) / 1_000_000.0;
        tickStartedNanos = 0L;
        samplesMs[index] = Math.max(0.0, elapsedMs);
        index = (index + 1) % WINDOW;
        if (count < WINDOW) {
            count += 1;
        }
    }

    public synchronized double averageMspt() {
        if (count == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < count; i += 1) {
            sum += samplesMs[i];
        }
        return sum / count;
    }

    public synchronized double estimatedTps() {
        double mspt = averageMspt();
        if (mspt <= 0.0) {
            return 20.0;
        }
        return Math.min(20.0, 1000.0 / Math.max(50.0, mspt));
    }
}
