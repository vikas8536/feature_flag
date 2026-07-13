package com.example.flags.log;

import org.junit.jupiter.api.Test;
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

    @Test void recordingSinkRecords() {
        var rec = new RecordingSink();
        rec.log(ErrorKind.NOT_FOUND, "f", "prod", "acme", "missing");
        assertEquals(1, rec.count(ErrorKind.NOT_FOUND));
        assertEquals("f", rec.entries().get(0).flag());
    }
}
