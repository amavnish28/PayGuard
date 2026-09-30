package com.payguard.transaction.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleTierTest {

    @ParameterizedTest(name = "Rule score {0} should map to RuleTier {1}")
    @CsvSource({
            "0, LOW",
            "29, LOW",
            "30, MEDIUM",
            "44, MEDIUM",
            "45, HIGH",
            "100, HIGH"
    })
    @DisplayName("Verify RuleTier boundaries at exact threshold boundaries")
    void testRuleTierBoundaries(int score, RuleTier expectedTier) {
        assertEquals(expectedTier, RuleTier.fromScore(score));
    }

    @Test
    @DisplayName("Boundary checks: exactly 29 is LOW, 30 is MEDIUM, 44 is MEDIUM, 45 is HIGH")
    void testExactBoundaries() {
        assertEquals(RuleTier.LOW, RuleTier.fromScore(0));
        assertEquals(RuleTier.LOW, RuleTier.fromScore(29));
        assertEquals(RuleTier.MEDIUM, RuleTier.fromScore(30));
        assertEquals(RuleTier.MEDIUM, RuleTier.fromScore(44));
        assertEquals(RuleTier.HIGH, RuleTier.fromScore(45));
        assertEquals(RuleTier.HIGH, RuleTier.fromScore(100));
    }
}
