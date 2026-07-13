package com.example.flags.config;

import com.example.flags.api.FlagValue;
import java.util.List;

public record Rule(List<Clause> clauses, FlagValue value, Rollout rollout) {}
