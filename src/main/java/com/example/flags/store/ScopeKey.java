package com.example.flags.store;

public record ScopeKey(String environment, String tenant, String flagName) {}
