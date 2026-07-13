package com.example.flags.config;

import com.example.flags.api.FlagType;
import com.example.flags.api.FlagValue;
import java.util.List;

public record FlagConfig(String flagName, String environment, String tenant, FlagType type,
                         FlagValue defaultValue, boolean enabled, FlagValue offValue, List<Rule> rules) {}
