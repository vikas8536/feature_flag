package com.example.flags.demo;

import com.example.flags.api.EvaluationContext;
import com.example.flags.api.FeatureFlagClient;
import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.FlagConfig;
import com.example.flags.store.ConfigStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderFleetTest {

    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private static FlagConfig greeting(String value) {
        return new FlagConfig("greeting", "prod", "acme", FlagType.STRING,
                FlagValue.of(value), true, null, List.of());
    }

    private static EvaluationContext ctx() {
        return EvaluationContext.builder().attribute("user", Map.of("id", "u-0")).build();
    }

    @Test
    void readersConvergeOnEachNewValueAndNeverSeeAnUnpublishedOne() {
        ConfigStore store = new ConfigStore();
        store.set(greeting("v1"));

        FeatureFlagClient client = FeatureFlagClient.builder()
                .store(store).environment("prod").tenant("acme").errorSink(new CountingSink()).build();

        try (ReaderFleet fleet = new ReaderFleet(client, "greeting", ctx(), 4)) {
            fleet.start();
            assertTrue(fleet.awaitAll("v1", ONE_SECOND_NANOS) >= 0, "readers never converged on v1");

            store.set(greeting("v2"));
            assertTrue(fleet.awaitAll("v2", ONE_SECOND_NANOS) >= 0, "readers never converged on v2");

            assertTrue(Set.of("v1", "v2").containsAll(fleet.observedValues()),
                    "readers observed a value that was never published: " + fleet.observedValues());
            assertEquals(0, fleet.escapedThrowables(), "a Throwable escaped into a reader thread");
            assertTrue(fleet.reads() > 0, "readers did not read anything");
        }
    }

    @Test
    void awaitAllReturnsMinusOneWhenTheValueNeverArrives() {
        ConfigStore store = new ConfigStore();
        store.set(greeting("v1"));

        FeatureFlagClient client = FeatureFlagClient.builder()
                .store(store).environment("prod").tenant("acme").errorSink(new CountingSink()).build();

        try (ReaderFleet fleet = new ReaderFleet(client, "greeting", ctx(), 2)) {
            fleet.start();
            assertEquals(-1, fleet.awaitAll("never-published", 50_000_000L),
                    "awaitAll must report a timeout rather than passing silently");
        }
    }

    @Test
    void observedValuesResetsOnClear() {
        ConfigStore store = new ConfigStore();
        store.set(greeting("v1"));

        FeatureFlagClient client = FeatureFlagClient.builder()
                .store(store).environment("prod").tenant("acme").errorSink(new CountingSink()).build();

        try (ReaderFleet fleet = new ReaderFleet(client, "greeting", ctx(), 2)) {
            fleet.start();
            assertTrue(fleet.awaitAll("v1", ONE_SECOND_NANOS) >= 0);
            assertEquals(Set.of("v1"), fleet.observedValues());

            fleet.clearObserved();
            store.delete("greeting", "prod", "acme");
            assertTrue(fleet.awaitAll(ReaderFleet.CALLER_DEFAULT, ONE_SECOND_NANOS) >= 0,
                    "readers did not fall back to the caller default after delete");
            assertTrue(fleet.observedValues().contains(ReaderFleet.CALLER_DEFAULT));
        }
    }

    @Test
    void readersConvergeOnBoolValues() {
        ConfigStore store = new ConfigStore();
        store.set(greeting("v1"));

        FeatureFlagClient client = FeatureFlagClient.builder()
                .store(store).environment("prod").tenant("acme").errorSink(new CountingSink()).build();

        // The flag is a STRING; boolValue returns the caller's default (false)
        // because the SDK serves the caller's default on type mismatch. All
        // bool-mode readers should observe "false".
        try (ReaderFleet fleet = new ReaderFleet(client, "greeting", ctx(), 4, /*boolMode=*/true)) {
            fleet.start();
            assertTrue(fleet.awaitAll("false", ONE_SECOND_NANOS) >= 0,
                    "bool-mode readers did not converge on the type-mismatch fallback");
            assertEquals(0, fleet.escapedThrowables(),
                    "a Throwable escaped into a bool-mode reader thread");
        }
    }
}
