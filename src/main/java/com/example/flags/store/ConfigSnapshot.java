package com.example.flags.store;

import com.example.flags.config.FlagConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ConfigSnapshot {
    private static final ConfigSnapshot EMPTY = new ConfigSnapshot(Map.of());
    private final Map<ScopeKey, FlagConfig> flags;

    private ConfigSnapshot(Map<ScopeKey, FlagConfig> flags) { this.flags = flags; }

    public static ConfigSnapshot empty() { return EMPTY; }

    // ponytail: O(all flags) copy per write; nest env→tenant maps if config writes ever get hot.
    public ConfigSnapshot with(FlagConfig c) {
        Map<ScopeKey, FlagConfig> next = new HashMap<>(flags);
        next.put(new ScopeKey(c.environment(), c.tenant(), c.flagName()), c);
        return new ConfigSnapshot(Map.copyOf(next));
    }

    public Optional<FlagConfig> get(String flagName, String environment, String tenant) {
        return Optional.ofNullable(flags.get(new ScopeKey(environment, tenant, flagName)));
    }

    public int size() { return flags.size(); }
}
