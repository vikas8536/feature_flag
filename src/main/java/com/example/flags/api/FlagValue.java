package com.example.flags.api;

public sealed interface FlagValue permits FlagValue.BoolValue, FlagValue.StringValue, FlagValue.IntValue {
    FlagType type();
    record BoolValue(boolean value) implements FlagValue { public FlagType type() { return FlagType.BOOLEAN; } }
    record StringValue(String value) implements FlagValue { public FlagType type() { return FlagType.STRING; } }
    record IntValue(long value) implements FlagValue { public FlagType type() { return FlagType.INTEGER; } }
    static FlagValue of(boolean v) { return new BoolValue(v); }
    static FlagValue of(String v) { return new StringValue(v); }
    static FlagValue of(long v) { return new IntValue(v); }
}
