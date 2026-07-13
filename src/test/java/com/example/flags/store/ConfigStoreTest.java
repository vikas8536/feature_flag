package com.example.flags.store;

import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import com.example.flags.config.ConfigValidationException;
import com.example.flags.config.FlagConfig;
import com.example.flags.config.Rollout;
import com.example.flags.config.RolloutState;
import com.example.flags.config.Rule;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {

    private static FlagConfig flag(String name, String env, String tenant, boolean dflt) {
        return new FlagConfig(name, env, tenant, FlagType.BOOLEAN,
                FlagValue.of(dflt), true, null, List.of());
    }

    private static FlagConfig rolloutFlag(String env, String tenant, int percentage, RolloutState state) {
        var rollout = new Rollout("user.id", null, percentage, FlagValue.of(true));
        return new FlagConfig("f", env, tenant, FlagType.BOOLEAN, FlagValue.of(false),
                true, null, List.of(new Rule(List.of(), null, rollout)), state);
    }

    @Test void setThenGet() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        assertTrue(store.get("f", "prod", "acme").isPresent());
        assertTrue(store.get("f", "dev", "acme").isEmpty());     // env isolation
        assertTrue(store.get("f", "prod", "globex").isEmpty());  // tenant isolation
    }

    @Test void setOverwritesSameScope() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        store.set(flag("f", "prod", "acme", false));
        assertEquals(FlagValue.of(false), store.get("f", "prod", "acme").get().defaultValue());
        assertEquals(1, store.current().size());
    }

    @Test void invalidConfigRejectedAndNothingPublished() {
        var store = new ConfigStore();
        var bad = new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN,
                FlagValue.of("wrong type"), true, null, List.of());
        assertThrows(ConfigValidationException.class, () -> store.set(bad));
        assertTrue(store.get("f", "prod", "acme").isEmpty());
    }

    @Test void snapshotIsStableWhileStoreMoves() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        ConfigSnapshot snap = store.current();
        store.set(flag("f", "prod", "acme", false));
        assertEquals(FlagValue.of(true), snap.get("f", "prod", "acme").get().defaultValue());
        assertEquals(FlagValue.of(false), store.current().get("f", "prod", "acme").get().defaultValue());
    }

    @Test void deleteRemovesOnlyTargetScope() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        store.set(flag("f", "dev", "acme", false));
        store.delete("f", "prod", "acme");
        assertTrue(store.get("f", "prod", "acme").isEmpty());
        assertTrue(store.get("f", "dev", "acme").isPresent());   // other scope untouched
        assertEquals(1, store.current().size());
    }

    @Test void deleteMissingIsNoOp() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        assertDoesNotThrow(() -> store.delete("ghost", "prod", "acme"));
        assertEquals(1, store.current().size());
    }

    @Test void deleteDoesNotDisturbEarlierSnapshot() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        ConfigSnapshot snap = store.current();
        store.delete("f", "prod", "acme");
        assertTrue(snap.get("f", "prod", "acme").isPresent());   // old snapshot keeps it
        assertTrue(store.current().get("f", "prod", "acme").isEmpty());
    }

    @Test void sameFlagDifferentScopesCoexist() {
        var store = new ConfigStore();
        store.set(flag("f", "prod", "acme", true));
        store.set(flag("f", "dev", "acme", false));
        store.set(flag("f", "prod", "globex", false));
        assertEquals(3, store.current().size());
        assertEquals(FlagValue.of(true), store.get("f", "prod", "acme").get().defaultValue());
    }

    @Test void rolloutPercentageCanStayEqualOrIncrease() {
        var store = new ConfigStore();
        store.set(rolloutFlag("prod", "acme", 25, RolloutState.ACTIVE));
        assertDoesNotThrow(() -> store.set(rolloutFlag("prod", "acme", 25, RolloutState.ACTIVE)));
        assertDoesNotThrow(() -> store.set(rolloutFlag("prod", "acme", 50, RolloutState.ACTIVE)));
        assertEquals(50, storedPercentage(store, "prod", "acme"));
    }

    @Test void rolloutPercentageCannotDecreaseAndPreviousSnapshotRemainsPublished() {
        var store = new ConfigStore();
        store.set(rolloutFlag("prod", "acme", 50, RolloutState.ACTIVE));

        assertThrows(ConfigValidationException.class,
                () -> store.set(rolloutFlag("prod", "acme", 25, RolloutState.ACTIVE)));
        assertEquals(50, storedPercentage(store, "prod", "acme"));
    }

    @Test void stoppedRolloutRetainsHighWaterMarkForResume() {
        var store = new ConfigStore();
        FlagConfig active = rolloutFlag("prod", "acme", 50, RolloutState.ACTIVE);
        store.set(active);
        assertThrows(ConfigValidationException.class,
                () -> store.set(active.withRolloutState(RolloutState.STOPPED)));
        store.stopRollout("f", "prod", "acme");

        assertThrows(ConfigValidationException.class,
                () -> store.set(rolloutFlag("prod", "acme", 40, RolloutState.STOPPED)));
        assertThrows(ConfigValidationException.class,
                () -> store.set(rolloutFlag("prod", "acme", 40, RolloutState.ACTIVE)));
        assertThrows(ConfigValidationException.class,
                () -> store.set(rolloutFlag("prod", "acme", 50, RolloutState.ACTIVE)));
        assertEquals(RolloutState.STOPPED,
                store.get("f", "prod", "acme").orElseThrow().rolloutState());

        store.resumeRollout("f", "prod", "acme");
        assertDoesNotThrow(() -> store.set(rolloutFlag("prod", "acme", 75, RolloutState.ACTIVE)));
    }

    @Test void staleActiveConfigCannotUndoStop() {
        var store = new ConfigStore();
        store.set(rolloutFlag("prod", "acme", 50, RolloutState.ACTIVE));
        FlagConfig staleActive = store.get("f", "prod", "acme").orElseThrow();

        store.set(rolloutFlag("prod", "acme", 75, RolloutState.ACTIVE));
        store.stopRollout("f", "prod", "acme");

        assertThrows(ConfigValidationException.class, () -> store.set(staleActive));
        FlagConfig stopped = store.get("f", "prod", "acme").orElseThrow();
        assertEquals(RolloutState.STOPPED, stopped.rolloutState());
        assertEquals(75, stopped.rules().getFirst().rollout().percentage());
    }

    @Test void stopAndResumeRejectMissingFlag() {
        var store = new ConfigStore();
        assertThrows(ConfigValidationException.class, () -> store.stopRollout("f", "prod", "acme"));
        assertThrows(ConfigValidationException.class, () -> store.resumeRollout("f", "prod", "acme"));
    }

    @Test void existingRolloutCannotBeRemovedOrRebucketed() {
        var store = new ConfigStore();
        store.set(rolloutFlag("prod", "acme", 50, RolloutState.ACTIVE));
        var removed = flag("f", "prod", "acme", false);
        var rebucketed = new FlagConfig("f", "prod", "acme", FlagType.BOOLEAN, FlagValue.of(false),
                true, null, List.of(new Rule(List.of(), null,
                new Rollout("account.id", null, 50, FlagValue.of(true)))));

        assertThrows(ConfigValidationException.class, () -> store.set(removed));
        assertThrows(ConfigValidationException.class, () -> store.set(rebucketed));
        assertEquals(50, storedPercentage(store, "prod", "acme"));
    }

    @Test void rolloutHistoryIsIsolatedByScopeAndResetByDelete() {
        var store = new ConfigStore();
        store.set(rolloutFlag("prod", "acme", 75, RolloutState.ACTIVE));
        assertDoesNotThrow(() -> store.set(rolloutFlag("dev", "acme", 10, RolloutState.ACTIVE)));
        assertDoesNotThrow(() -> store.set(rolloutFlag("prod", "globex", 5, RolloutState.ACTIVE)));

        store.delete("f", "prod", "acme");
        assertDoesNotThrow(() -> store.set(rolloutFlag("prod", "acme", 10, RolloutState.ACTIVE)));
    }

    @Test void concurrentWritersCannotOverwriteHigherPercentage() throws Exception {
        var store = new ConfigStore();
        store.set(rolloutFlag("prod", "acme", 0, RolloutState.ACTIVE));
        var failures = new ConcurrentLinkedQueue<Throwable>();

        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int percentage = 1; percentage <= 100; percentage++) {
                int candidate = percentage;
                pool.submit(() -> {
                    try {
                        store.set(rolloutFlag("prod", "acme", candidate, RolloutState.ACTIVE));
                    } catch (ConfigValidationException expected) {
                        // Another writer already published a higher percentage.
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(100, storedPercentage(store, "prod", "acme"));
    }

    private static int storedPercentage(ConfigStore store, String env, String tenant) {
        return store.get("f", env, tenant).orElseThrow().rules().getFirst().rollout().percentage();
    }
}
