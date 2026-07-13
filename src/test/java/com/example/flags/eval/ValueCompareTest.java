package com.example.flags.eval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueCompareTest {
    @Test void integralEquality()  { assertTrue(ValueCompare.numericEquals(20, 20L)); }
    @Test void mixedEquality()     { assertTrue(ValueCompare.numericEquals(1, 1.0)); }
    @Test void inequality()        { assertFalse(ValueCompare.numericEquals(1, 2)); }
    @Test void nonNumericIsNull()  { assertNull(ValueCompare.numericCompare("a", 1)); }
    @Test void compareWorks() {
        assertTrue(ValueCompare.numericCompare(25, 18L) > 0);
        assertTrue(ValueCompare.numericCompare(24.5, 25) < 0);
        assertEquals(0, ValueCompare.numericCompare(25, 25.0));
    }
}
