package com.example.flags.log;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RateLimitedStderrSinkTest {

    @Test void rateLimitsPerFlagAndKind() {
        AtomicLong now = new AtomicLong(0);
        var sink = new RateLimitedStderrSink(60_000, now::get);
        assertTrue(sink.shouldLog(ErrorKind.NOT_FOUND, "f1"));
        assertFalse(sink.shouldLog(ErrorKind.NOT_FOUND, "f1"));   // same window suppressed
        assertTrue(sink.shouldLog(ErrorKind.EVAL_ERROR, "f1"));   // different kind allowed
        assertTrue(sink.shouldLog(ErrorKind.NOT_FOUND, "f2"));    // different flag allowed
        now.set(61_000);
        assertTrue(sink.shouldLog(ErrorKind.NOT_FOUND, "f1"));    // window expired
    }

    @Test void singleWinnerUnderSameMillisecondContention() throws InterruptedException {
        int n = 32;
        AtomicLong now = new AtomicLong(1_000);   // pinned: every thread sees the same millisecond
        var sink = new RateLimitedStderrSink(60_000, now::get);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(n);
        var winners = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (sink.shouldLog(ErrorKind.NOT_FOUND, "f")) winners.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(1, winners.get());
    }

    @Test void recordingSinkRecords() {
        var rec = new RecordingSink();
        rec.log(ErrorKind.NOT_FOUND, "f", "prod", "acme", "missing");
        assertEquals(1, rec.count(ErrorKind.NOT_FOUND));
        assertEquals("f", rec.entries().get(0).flag());
    }
}
