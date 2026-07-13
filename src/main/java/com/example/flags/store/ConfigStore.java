package com.example.flags.store;

import com.example.flags.config.ConfigValidator;
import com.example.flags.config.ConfigValidationException;
import com.example.flags.config.FlagConfig;
import com.example.flags.config.RolloutState;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigStore implements ConfigSource {
    private final AtomicReference<ConfigSnapshot> snapshot = new AtomicReference<>(ConfigSnapshot.empty());

    /** Validates then publishes atomically; transition validation re-runs against the winning snapshot on CAS contention. */
    @Override
    public void set(FlagConfig c) {
        FlagConfig next = ConfigValidator.validate(c);
        snapshot.updateAndGet(s -> {
            s.get(next.flagName(), next.environment(), next.tenant())
                    .ifPresent(previous -> ConfigValidator.validateTransition(previous, next));
            return s.with(next);
        });
    }

    @Override
    public void stopRollout(String flagName, String environment, String tenant) {
        changeRolloutState(flagName, environment, tenant, RolloutState.STOPPED);
    }

    @Override
    public void resumeRollout(String flagName, String environment, String tenant) {
        changeRolloutState(flagName, environment, tenant, RolloutState.ACTIVE);
    }

    @Override
    public Optional<FlagConfig> get(String flagName, String environment, String tenant) {
        return snapshot.get().get(flagName, environment, tenant);
    }

    @Override
    public void delete(String flagName, String environment, String tenant) {
        snapshot.updateAndGet(s -> s.without(flagName, environment, tenant));
    }

    private void changeRolloutState(String flagName, String environment, String tenant, RolloutState state) {
        snapshot.updateAndGet(s -> {
            FlagConfig current = s.get(flagName, environment, tenant)
                    .orElseThrow(() -> new ConfigValidationException(flagName + ": flag not found"));
            return s.with(current.withRolloutState(state));
        });
    }

    /** Internal/test view of the published snapshot; not part of the SDK's consumption surface. */
    ConfigSnapshot current() { return snapshot.get(); }
}
