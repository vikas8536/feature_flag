package com.example.flags.config;

import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import java.util.List;

public record FlagConfig(String flagName, String environment, String tenant, FlagType type,
                         FlagValue defaultValue, boolean enabled, FlagValue offValue, List<Rule> rules,
                         RolloutState rolloutState) {
    public FlagConfig(String flagName, String environment, String tenant, FlagType type,
                      FlagValue defaultValue, boolean enabled, FlagValue offValue, List<Rule> rules) {
        this(flagName, environment, tenant, type, defaultValue, enabled, offValue, rules, RolloutState.ACTIVE);
    }

    public FlagConfig withRolloutState(RolloutState state) {
        return new FlagConfig(flagName, environment, tenant, type, defaultValue, enabled, offValue, rules, state);
    }
}
