package com.example.flags.config;

import com.example.flags.api.FlagValue;

public record Rollout(String bucketingKey, String bucketingGroup, int percentage, FlagValue value) {}
