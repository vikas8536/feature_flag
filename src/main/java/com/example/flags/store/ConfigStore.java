package com.example.flags.store;

import com.example.flags.config.ConfigValidator;
import com.example.flags.config.FlagConfig;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigStore implements ConfigSource {
    private final AtomicReference<ConfigSnapshot> snapshot = new AtomicReference<>(ConfigSnapshot.empty());

    /** Validates then publishes atomically. Validation inside updateAndGet: function may re-run on CAS contention; validation is pure. */
    @Override
    public void set(FlagConfig c) {
        snapshot.updateAndGet(s -> s.with(ConfigValidator.validate(c)));
    }

    @Override
    public Optional<FlagConfig> get(String flagName, String environment, String tenant) {
        return snapshot.get().get(flagName, environment, tenant);
    }

    /** Internal/test view of the published snapshot; not part of the SDK's consumption surface. */
    ConfigSnapshot current() { return snapshot.get(); }
}
