package com.example.flags.config;

import java.util.List;

public record Clause(String path, Operator op, List<Object> values) {}
