package com.example.flags;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FeatureFlagClient;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.FlagConfig;
import com.example.flags.log.RecordingSink;
import com.example.flags.store.ConfigStore;
import org.junit.jupiter.api.RepeatedTest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyStressTest {

    private static FlagConfig intFlag(long value) {
        return new FlagConfig("counter", "prod", "acme", FlagType.INTEGER,
                FlagValue.of(value), true, null, List.of());
    }

    /** 8 readers hammer evaluations while 2 writers republish config.
     *  Invariant: every observed value was legally published at some point; no exceptions, no torn reads. */
    @RepeatedTest(5)
    void readersNeverSeeIllegalValuesUnderWrites() throws Exception {
        var store = new ConfigStore();
        store.set(intFlag(0));
        var sink = new RecordingSink();
        var client = FeatureFlagClient.builder()
                .store(store).environment("prod").tenant("acme").errorSink(sink).build();
        var ctx = EvaluationContext.builder().attribute("user", Map.of("id", "u-1")).build();

        var stop = new AtomicBoolean(false);
        var failures = new ConcurrentLinkedQueue<String>();
        try (var pool = Executors.newFixedThreadPool(10)) {
            for (int w = 0; w < 2; w++) {
                final int writer = w;
                pool.submit(() -> {
                    long v = writer * 1_000_000L;
                    while (!stop.get()) store.set(intFlag(v++));
                });
            }
            for (int r = 0; r < 8; r++) {
                pool.submit(() -> {
                    while (!stop.get()) {
                        long got = client.intValue("counter", ctx, -1);
                        if (got < 0) failures.add("saw illegal value " + got);
                    }
                });
            }
            Thread.sleep(2_000);
            stop.set(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(0, sink.entries().size(), "no errors expected: " + sink.entries());
    }
}
