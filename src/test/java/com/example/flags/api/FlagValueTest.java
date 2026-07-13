package com.example.flags.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagValueTest {
    @Test void typesMap() {
        assertEquals(FlagType.BOOLEAN, FlagValue.of(true).type());
        assertEquals(FlagType.STRING, FlagValue.of("x").type());
        assertEquals(FlagType.INTEGER, FlagValue.of(7L).type());
    }
}
