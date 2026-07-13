package com.example.flags.demo;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A fleet of N daemon threads that runs {@link Runnable} tasks submitted via
 * {@link #submit(Runnable)}. Each submission returns a {@link CountDownLatch} that
 * counts down when the task has completed; callers wait on the latch to observe
 * the write's effect on the snapshot. Tasks are executed in submission order
 * within each thread (the demo uses {@code threadCount == 1}, so the global
 * order is total).
 *
 * <p>Designed for the demo's P2 and P4, which need a writer on a separate thread
 * so the reader fleet can observe the SDK's atomic-snapshot propagation
 * concurrently with the writes.
 */
public final class WriterFleet implements AutoCloseable {

    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final Thread[] threads;

    public WriterFleet(int threadCount) {
        this.threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(this::loop, "flag-writer-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
    }

    /** Submits a task. The returned latch counts down when the task has run. */
    public CountDownLatch submit(Runnable task) {
        CountDownLatch latch = new CountDownLatch(1);
        queue.add(new Task(task, latch));
        return latch;
    }

    private void loop() {
        while (true) {
            Task t;
            try {
                t = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (t.task == null) return;  // poison-pill shutdown
            try {
                t.task.run();
            } catch (Throwable ignored) {
                // The demo's writer tasks are SDK writes; their failures (e.g.
                // ConfigValidationException for a decrease) are surfaced by the
                // SDK's own error contract. We never want a write failure to
                // kill the writer thread.
            } finally {
                t.latch.countDown();
            }
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < threads.length; i++) queue.add(new Task(null, null));
        for (Thread t : threads) {
            try { t.join(1_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private record Task(Runnable task, CountDownLatch latch) {}
}
