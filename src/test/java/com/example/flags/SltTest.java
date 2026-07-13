package com.example.flags;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FeatureFlagClient;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.*;
import com.example.flags.store.ConfigStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("slt")
class SltTest {

    private static ConfigStore storeWithRuleAndRollout() {
        var store = new ConfigStore();
        var rule = new Rule(List.of(new Clause("user.address.country", Operator.EQUALS, List.of("IN"))),
                null, new Rollout("user.id", null, 50, FlagValue.of(true)));
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, List.of(rule)));
        return store;
    }

    private static FeatureFlagClient client(ConfigStore store) {
        return FeatureFlagClient.builder().store(store).environment("prod").tenant("acme").build();
    }

    private static EvaluationContext ctx(int i) {
        return EvaluationContext.builder()
                .attribute("user", Map.of("id", "u-" + i, "address", Map.of("country", "IN")))
                .build();
    }

    @Test void evalLatencySingleThread() {
        var client = client(storeWithRuleAndRollout());
        var contexts = new EvaluationContext[1024];
        for (int i = 0; i < contexts.length; i++) contexts[i] = ctx(i);

        for (int i = 0; i < 200_000; i++) client.boolValue("f", contexts[i & 1023], false); // warmup

        int n = 1_000_000;
        long[] samples = new long[n / 100];                       // sample every 100th eval
        for (int i = 0, s = 0; i < n; i++) {
            if (i % 100 == 0) {
                long t0 = System.nanoTime();
                client.boolValue("f", contexts[i & 1023], false);
                samples[s++] = System.nanoTime() - t0;
            } else {
                client.boolValue("f", contexts[i & 1023], false);
            }
        }
        Arrays.sort(samples);
        long p99 = samples[(int) (samples.length * 0.99)];
        long p999 = samples[(int) (samples.length * 0.999)];
        assertTrue(p99 < 50_000, "p99=" + p99 + "ns (budget 50us)");
        assertTrue(p999 < 1_000_000, "p999=" + p999 + "ns (budget 1ms)");
    }

    @Test void throughputFloor() {
        var client = client(storeWithRuleAndRollout());
        var c = ctx(7);
        for (int i = 0; i < 200_000; i++) client.boolValue("f", c, false); // warmup
        int n = 2_000_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) client.boolValue("f", c, false);
        double perSec = n / ((System.nanoTime() - t0) / 1e9);
        assertTrue(perSec > 1_000_000, "throughput=" + (long) perSec + "/s (floor 1M/s)");
    }

    @Test void evalLatencyEightThreads() throws Exception {
        var client = client(storeWithRuleAndRollout());
        int threads = 8, perThread = 250_000;
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = pool.submit(() -> {
                    var c = ctx(ThreadLocalRandom.current().nextInt(1024));
                    for (int i = 0; i < perThread; i++) client.boolValue("f", c, false);
                });
            }
            long t0 = System.nanoTime();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            double totalPerSec = (double) threads * perThread / ((System.nanoTime() - t0) / 1e9);
            assertTrue(totalPerSec > 1_000_000, "8-thread aggregate=" + (long) totalPerSec + "/s");
        }
    }

    @Test void propagationUnderFiveSeconds() throws Exception {
        var store = new ConfigStore();
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, List.of()));
        var client = client(store);
        var c = EvaluationContext.builder().attribute("user", Map.of("id", "u-1")).build();
        assertFalse(client.boolValue("f", c, true));

        var observed = new CompletableFuture<Long>();
        Thread reader = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (client.boolValue("f", c, false)) { observed.complete(System.nanoTime()); return; }
            }
            observed.completeExceptionally(new AssertionError("update not observed within 5s"));
        });
        reader.start();
        long t0 = System.nanoTime();
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(true), true, null, List.of()));
        long seenAt = observed.get(6, TimeUnit.SECONDS);
        reader.join();
        long lagMillis = TimeUnit.NANOSECONDS.toMillis(seenAt - t0);
        assertTrue(lagMillis < 5_000, "propagation took " + lagMillis + "ms");
    }

    /**
     * A flag whose single empty-clause rule matches everyone, so ACTIVE returns
     * {@code value} for every user and STOPPED returns {@code defaultValue}.
     * That asymmetry is what makes the propagation tests below observable.
     */
    private static ConfigStore storeWithFullRollout() {
        var store = new ConfigStore();
        var rule = new Rule(List.of(), null, new Rollout("user.id", null, 100, FlagValue.of(true)));
        store.set(new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of(false), true, null, List.of(rule)));
        return store;
    }

    @Test void stopRolloutAndResumeLatency() {
        var store = storeWithFullRollout();
        for (int i = 0; i < 200_000; i++) { store.stopRollout("f", "prod", "acme"); store.resumeRollout("f", "prod", "acme"); } // warmup

        int n = 200_000;
        long[] stopSamples = new long[n / 100];
        long[] resumeSamples = new long[n / 100];
        int sIdx = 0, rIdx = 0;
        for (int i = 0; i < n; i++) {
            if (i % 100 == 0) {
                long t0 = System.nanoTime();
                store.stopRollout("f", "prod", "acme");
                stopSamples[sIdx++] = System.nanoTime() - t0;
            } else {
                store.stopRollout("f", "prod", "acme");
            }
            if (i % 100 == 0) {
                long t0 = System.nanoTime();
                store.resumeRollout("f", "prod", "acme");
                resumeSamples[rIdx++] = System.nanoTime() - t0;
            } else {
                store.resumeRollout("f", "prod", "acme");
            }
        }
        Arrays.sort(stopSamples);
        Arrays.sort(resumeSamples);
        long stopP99 = stopSamples[(int) (stopSamples.length * 0.99)];
        long resumeP99 = resumeSamples[(int) (resumeSamples.length * 0.99)];
        // stopRollout / resumeRollout are pure state-only CAS swaps — no validator runs.
        // The same atomic-snapshot mechanism as `set`, without the validation cost.
        assertTrue(stopP99 < 50_000, "stopRollout p99=" + stopP99 + "ns (budget 50us)");
        assertTrue(resumeP99 < 50_000, "resumeRollout p99=" + resumeP99 + "ns (budget 50us)");
    }

    @Test void stopResumeCycleThroughput() {
        var store = storeWithFullRollout();
        for (int i = 0; i < 10_000; i++) { store.stopRollout("f", "prod", "acme"); store.resumeRollout("f", "prod", "acme"); } // warmup

        int n = 100_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            store.stopRollout("f", "prod", "acme");
            store.resumeRollout("f", "prod", "acme");
        }
        double perSec = n / ((System.nanoTime() - t0) / 1e9);
        // 100K stop+resume cycles per second is a generous floor; the SDK does
        // millions per second on dev hardware.
        assertTrue(perSec > 100_000, "stop+resume cycle=" + (long) perSec + "/s (floor 100K/s)");
    }

    @Test void stopRolloutPropagationUnderFiveSeconds() throws Exception {
        var store = storeWithFullRollout();
        var client = client(store);
        var c = EvaluationContext.builder().attribute("user", Map.of("id", "u-1")).build();
        // ACTIVE state: empty-clause rule matches everyone, returns rollout's value (true).
        assertTrue(client.boolValue("f", c, false));

        var observed = new CompletableFuture<Long>();
        Thread reader = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (!client.boolValue("f", c, true)) {
                    // STOPPED bypasses the rule and returns defaultValue (false).
                    observed.complete(System.nanoTime());
                    return;
                }
            }
            observed.completeExceptionally(new AssertionError("stop not observed within 5s"));
        });
        reader.start();
        long t0 = System.nanoTime();
        store.stopRollout("f", "prod", "acme");
        long seenAt = observed.get(6, TimeUnit.SECONDS);
        reader.join();
        long lagMillis = TimeUnit.NANOSECONDS.toMillis(seenAt - t0);
        assertTrue(lagMillis < 5_000, "stopRollout propagation took " + lagMillis + "ms");
    }

    @Test void resumeRolloutPropagationUnderFiveSeconds() throws Exception {
        var store = storeWithFullRollout();
        store.stopRollout("f", "prod", "acme");  // start in STOPPED so resume is the visible event
        var client = client(store);
        var c = EvaluationContext.builder().attribute("user", Map.of("id", "u-1")).build();
        // STOPPED state: rule bypassed, returns defaultValue (false).
        assertFalse(client.boolValue("f", c, true));

        var observed = new CompletableFuture<Long>();
        Thread reader = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (client.boolValue("f", c, false)) {
                    // After resume, the empty-clause rule fires again and returns rollout's value (true).
                    observed.complete(System.nanoTime());
                    return;
                }
            }
            observed.completeExceptionally(new AssertionError("resume not observed within 5s"));
        });
        reader.start();
        long t0 = System.nanoTime();
        store.resumeRollout("f", "prod", "acme");
        long seenAt = observed.get(6, TimeUnit.SECONDS);
        reader.join();
        long lagMillis = TimeUnit.NANOSECONDS.toMillis(seenAt - t0);
        assertTrue(lagMillis < 5_000, "resumeRollout propagation took " + lagMillis + "ms");
    }
}
