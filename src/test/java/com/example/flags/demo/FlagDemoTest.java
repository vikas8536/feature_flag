package com.example.flags.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagDemoTest {

    @Test
    void demoRunsWithNoInvariantViolations() {
        List<String> violations = new FlagDemo().run();
        assertEquals(List.of(), violations, "the demo reported invariant violations");
    }
}
