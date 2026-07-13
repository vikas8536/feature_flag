package com.example.flags.store;

import com.example.flags.config.ConfigValidator;
import com.example.flags.config.FlagConfig;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigStore {
    private final AtomicReference<ConfigSnapshot> snapshot = new AtomicReference<>(ConfigSnapshot.empty());

    /** Validates then publishes atomically. Validation inside updateAndGet: function may re-run on CAS contention; validation is pure. */
    public void set(FlagConfig c) {
        snapshot.updateAndGet(s -> s.with(ConfigValidator.validate(c)));
    }

    public Optional<FlagConfig> get(String flagName, String environment, String tenant) {
        return snapshot.get().get(flagName, environment, tenant);
    }

    public ConfigSnapshot current() { return snapshot.get(); }
}
