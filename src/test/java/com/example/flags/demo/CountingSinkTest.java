package com.example.flags.demo;

import com.example.flags.log.ErrorKind;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountingSinkTest {

    @Test
    void countsPerKindAndKeepsFirstDetail() {
        CountingSink sink = new CountingSink();

        assertEquals(0, sink.count(ErrorKind.NOT_FOUND));
        assertNull(sink.firstDetail(ErrorKind.NOT_FOUND));

        sink.log(ErrorKind.NOT_FOUND, "greeting", "prod", "acme", "no config for scope");
        sink.log(ErrorKind.NOT_FOUND, "greeting", "prod", "acme", "second detail, must be ignored");
        sink.log(ErrorKind.TYPE_MISMATCH, "greeting", "prod", "acme", "flag is INTEGER, requested STRING");

        assertEquals(2, sink.count(ErrorKind.NOT_FOUND));
        assertEquals(1, sink.count(ErrorKind.TYPE_MISMATCH));
        assertEquals(0, sink.count(ErrorKind.EVAL_ERROR));
        assertTrue(sink.firstDetail(ErrorKind.NOT_FOUND).contains("no config for scope"));
        assertTrue(sink.firstDetail(ErrorKind.TYPE_MISMATCH).contains("requested STRING"));
    }

    @Test
    void countsAreAtomicUnderConcurrentLogging() throws InterruptedException {
        CountingSink sink = new CountingSink();
        int threads = 8;
        int logsPerThread = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < logsPerThread; n++)
                        sink.log(ErrorKind.NOT_FOUND, "greeting", "prod", "acme", "miss");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "logging threads did not finish");
        assertEquals((long) threads * logsPerThread, sink.count(ErrorKind.NOT_FOUND));
    }
}
