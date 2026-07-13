package com.example.flags.store;

import com.example.flags.config.FlagConfig;
import java.util.Optional;

/**
 * The configuration surface the SDK consumes — the requirement-given store
 * interface: {@code set(flag_config)} and {@code get(flag_name, env)},
 * extended with the tenant scoping dimension (spec §2).
 *
 * The SDK reads through {@link #get} on every evaluation; implementations
 * must make reads cheap and thread-safe. {@link ConfigStore} backs this with
 * an immutable snapshot in an {@code AtomicReference} (one volatile read,
 * zero-latency propagation). A remote-backed source is a drop-in swap.
 */
public interface ConfigSource {
    /** Validates and publishes a flag configuration; visible to readers immediately. */
    void set(FlagConfig config);

    /** Current configuration for the scoped flag, or empty if not configured. */
    Optional<FlagConfig> get(String flagName, String environment, String tenant);

    /** Removes the scoped flag configuration; no-op if absent. Readers see the removal immediately. */
    void delete(String flagName, String environment, String tenant);
}
