package com.example.flags.demo;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FeatureFlagClient;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A fleet of daemon threads calling {@code stringValue} on one flag in a tight loop, recording
 * what they observe so the main thread can assert on it.
 *
 * <p>Readers pass {@link #CALLER_DEFAULT} as their caller default, so observing that sentinel
 * means the SDK could not serve a config (the flag is missing, or its type does not match).
 */
public final class ReaderFleet implements AutoCloseable {

    public static final String CALLER_DEFAULT = "__CALLER_DEFAULT__";

    private final FeatureFlagClient client;
    private final String flag;
    private final EvaluationContext ctx;
    private final Thread[] threads;
    private final AtomicReferenceArray<String> lastObserved;
    private final Set<String> observed = ConcurrentHashMap.newKeySet();
    private final AtomicLong escaped = new AtomicLong();
    private final AtomicLong reads = new AtomicLong();
    private volatile boolean running = true;

    public ReaderFleet(FeatureFlagClient client, String flag, EvaluationContext ctx, int threadCount) {
        this.client = client;
        this.flag = flag;
        this.ctx = ctx;
        this.threads = new Thread[threadCount];
        this.lastObserved = new AtomicReferenceArray<>(threadCount);
    }

    public void start() {
        for (int i = 0; i < threads.length; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> loop(idx), "flag-reader-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
    }

    private void loop(int idx) {
        while (running) {
            try {
                String value = client.stringValue(flag, ctx, CALLER_DEFAULT);
                lastObserved.set(idx, value);
                observed.add(value);
                reads.incrementAndGet();
            } catch (Throwable t) {
                // The SDK contract says this is unreachable. Count it rather than dying, so the
                // demo can report the contract violation instead of losing a thread to it.
                escaped.incrementAndGet();
            }
            Thread.onSpinWait();
        }
    }

    /**
     * Spins until every reader's last-observed value equals {@code value}.
     *
     * @return nanoseconds elapsed, or -1 if the timeout expired first
     */
    public long awaitAll(String value, long timeoutNanos) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < timeoutNanos) {
            if (allObserving(value)) return System.nanoTime() - start;
            Thread.onSpinWait();
        }
        return -1;
    }

    private boolean allObserving(String value) {
        for (int i = 0; i < lastObserved.length(); i++)
            if (!value.equals(lastObserved.get(i))) return false;
        return true;
    }

    /** Every distinct value any reader has observed since the last {@link #clearObserved()}. */
    public Set<String> observedValues() {
        return Set.copyOf(observed);
    }

    public void clearObserved() {
        observed.clear();
    }

    /** Always 0 if the SDK holds its never-throws contract. */
    public long escapedThrowables() {
        return escaped.get();
    }

    public long reads() {
        return reads.get();
    }

    @Override
    public void close() {
        running = false;
        for (Thread t : threads) {
            if (t == null) continue;
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
