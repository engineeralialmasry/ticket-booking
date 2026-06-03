/*
 * Decompiled with CFR 0.152.
 */
package com.ulfg.booking.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class Metrics {
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final long startNanos = System.nanoTime();
    private final ReentrantLock latLock = new ReentrantLock();
    private long[] latencies = new long[1024];
    private int latCount = 0;

    public void recordCompleted(long latencyMs) {
        this.completed.incrementAndGet();
        this.addLatency(latencyMs);
    }

    public void recordFailed(long latencyMs) {
        this.failed.incrementAndGet();
        this.addLatency(latencyMs);
    }

    public void recordRejected() {
        this.rejected.incrementAndGet();
    }

    public void queueDepth(int depth) {
        this.queueDepth.set(depth);
    }

    private void addLatency(long ms) {
        this.latLock.lock();
        try {
            if (this.latCount == this.latencies.length) {
                this.latencies = Arrays.copyOf(this.latencies, this.latencies.length * 2);
            }
            this.latencies[this.latCount++] = ms;
        }
        finally {
            this.latLock.unlock();
        }
    }

    private long percentile(long[] sorted, int n, double p) {
        if (n == 0) {
            return 0L;
        }
        int idx = (int)Math.ceil(p / 100.0 * (double)n) - 1;
        idx = Math.max(0, Math.min(idx, n - 1));
        return sorted[idx];
    }

    public Snapshot snapshot() {
        long[] copy;
        int n;
        this.latLock.lock();
        try {
            n = this.latCount;
            copy = Arrays.copyOf(this.latencies, n);
        }
        finally {
            this.latLock.unlock();
        }
        Arrays.sort(copy, 0, n);
        double elapsedSec = (double)(System.nanoTime() - this.startNanos) / 1.0E9;
        long done = this.completed.get();
        double throughput = elapsedSec > 0.0 ? (double)done / elapsedSec : 0.0;
        return new Snapshot(done, this.failed.get(), this.rejected.get(), this.queueDepth.get(), throughput, this.percentile(copy, n, 50.0), this.percentile(copy, n, 95.0), this.percentile(copy, n, 99.0));
    }

    public record Snapshot(long completed, long failed, long rejected, int queueDepth, double throughputPerSec, long p50ms, long p95ms, long p99ms) {
        public String pretty() {
            return String.format("completed=%d failed=%d rejected=%d queueDepth=%d | throughput=%.1f/s  p50=%dms p95=%dms p99=%dms", this.completed, this.failed, this.rejected, this.queueDepth, this.throughputPerSec, this.p50ms, this.p95ms, this.p99ms);
        }
    }
}
