package com.example.flags;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {
    @Test
    void toolchainWorks() {
        assertTrue(Runtime.version().feature() >= 21);
    }
}
