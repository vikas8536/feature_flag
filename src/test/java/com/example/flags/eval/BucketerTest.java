package com.example.flags.eval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BucketerTest {

    @Test void deterministic() {
        int b = Bucketer.bucket("flagA", "acme", "u-1");
        for (int i = 0; i < 1000; i++) assertEquals(b, Bucketer.bucket("flagA", "acme", "u-1"));
    }

    @Test void inRange() {
        for (int i = 0; i < 10_000; i++) {
            int b = Bucketer.bucket("flagA", "acme", "u-" + i);
            assertTrue(b >= 0 && b < 100);
        }
    }

    @Test void distributionRoughlyUniform() {
        int n = 100_000, in = 0;
        for (int i = 0; i < n; i++) if (Bucketer.inRollout("flagA", "acme", "u-" + i, 20)) in++;
        double pct = 100.0 * in / n;
        assertTrue(Math.abs(pct - 20.0) < 1.5, "got " + pct);
    }

    @Test void rampUpNeverEvicts() {
        for (int i = 0; i < 10_000; i++) {
            String u = "u-" + i;
            if (Bucketer.inRollout("f", "t", u, 10))
                assertTrue(Bucketer.inRollout("f", "t", u, 20));
        }
    }

    @Test void sharedSaltSameBucket_independentSaltsDiffer() {
        int agree = 0;
        for (int i = 0; i < 10_000; i++) {
            String u = "u-" + i;
            assertEquals(Bucketer.bucket("groupG", "t", u), Bucketer.bucket("groupG", "t", u));
            if (Bucketer.bucket("flagA", "t", u) == Bucketer.bucket("flagB", "t", u)) agree++;
        }
        assertTrue(agree < 300, "independent salts agreed " + agree + "/10000");  // ~1% expected
    }

    @Test void nestedCohorts_sameSaltDifferentPercentages() {
        for (int i = 0; i < 10_000; i++) {
            String u = "u-" + i;
            if (Bucketer.inRollout("groupG", "t", u, 10))
                assertTrue(Bucketer.inRollout("groupG", "t", u, 20));
        }
    }

    @Test void tenantScopesBuckets() {
        int agree = 0;
        for (int i = 0; i < 10_000; i++)
            if (Bucketer.bucket("f", "tenantA", "u-" + i) == Bucketer.bucket("f", "tenantB", "u-" + i)) agree++;
        assertTrue(agree < 300, "cross-tenant agreement " + agree + "/10000");
    }

    @Test void percentageEdges() {
        assertFalse(Bucketer.inRollout("f", "t", "u-1", 0));
        assertTrue(Bucketer.inRollout("f", "t", "u-1", 100));
    }
}
