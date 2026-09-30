package com.payguard.decision;

import com.payguard.decision.HybridDecisionEngine.MlBand;
import com.payguard.ml.MLPredictionResponse;
import com.payguard.transaction.rule.RuleEvaluation;
import com.payguard.transaction.rule.RuleTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HybridDecisionEngineTest {

    private final HybridDecisionEngine engine = new HybridDecisionEngine();

    @Test
    @DisplayName("Normal matrix cell 1: LOW tier + ML low -> APPROVE")
    void testNormalMatrixCell1_LowTier_MlLow() {
        assertEquals(Decision.APPROVE, HybridDecisionEngine.getMatrixDecision(RuleTier.LOW, MlBand.LOW));
    }

    @Test
    @DisplayName("Normal matrix cell 2: LOW tier + ML review -> REVIEW")
    void testNormalMatrixCell2_LowTier_MlReview() {
        assertEquals(Decision.REVIEW, HybridDecisionEngine.getMatrixDecision(RuleTier.LOW, MlBand.REVIEW));
    }

    @Test
    @DisplayName("Normal matrix cell 3: LOW tier + ML block -> BLOCK")
    void testNormalMatrixCell3_LowTier_MlBlock() {
        assertEquals(Decision.BLOCK, HybridDecisionEngine.getMatrixDecision(RuleTier.LOW, MlBand.BLOCK));
    }

    @Test
    @DisplayName("Normal matrix cell 4: MEDIUM tier + ML low -> REVIEW")
    void testNormalMatrixCell4_MediumTier_MlLow() {
        assertEquals(Decision.REVIEW, HybridDecisionEngine.getMatrixDecision(RuleTier.MEDIUM, MlBand.LOW));
    }

    @Test
    @DisplayName("Normal matrix cell 5: MEDIUM tier + ML review -> REVIEW")
    void testNormalMatrixCell5_MediumTier_MlReview() {
        assertEquals(Decision.REVIEW, HybridDecisionEngine.getMatrixDecision(RuleTier.MEDIUM, MlBand.REVIEW));
    }

    @Test
    @DisplayName("Normal matrix cell 6: MEDIUM tier + ML block -> BLOCK")
    void testNormalMatrixCell6_MediumTier_MlBlock() {
        assertEquals(Decision.BLOCK, HybridDecisionEngine.getMatrixDecision(RuleTier.MEDIUM, MlBand.BLOCK));
    }

    @Test
    @DisplayName("Normal matrix cell 7: HIGH tier + ML low -> REVIEW")
    void testNormalMatrixCell7_HighTier_MlLow() {
        assertEquals(Decision.REVIEW, HybridDecisionEngine.getMatrixDecision(RuleTier.HIGH, MlBand.LOW));
    }

    @Test
    @DisplayName("Normal matrix cell 8: HIGH tier + ML review -> BLOCK")
    void testNormalMatrixCell8_HighTier_MlReview() {
        assertEquals(Decision.BLOCK, HybridDecisionEngine.getMatrixDecision(RuleTier.HIGH, MlBand.REVIEW));
    }

    @Test
    @DisplayName("Normal matrix cell 9: HIGH tier + ML block -> BLOCK")
    void testNormalMatrixCell9_HighTier_MlBlock() {
        assertEquals(Decision.BLOCK, HybridDecisionEngine.getMatrixDecision(RuleTier.HIGH, MlBand.BLOCK));
    }

    @Test
    @DisplayName("Fallback matrix cell 1: LOW tier (ML unavailable) -> APPROVE")
    void testFallbackMatrixCell1_LowTier() {
        assertEquals(Decision.APPROVE, HybridDecisionEngine.getFallbackDecision(RuleTier.LOW));
    }

    @Test
    @DisplayName("Fallback matrix cell 2: MEDIUM tier (ML unavailable) -> REVIEW")
    void testFallbackMatrixCell2_MediumTier() {
        assertEquals(Decision.REVIEW, HybridDecisionEngine.getFallbackDecision(RuleTier.MEDIUM));
    }

    @Test
    @DisplayName("Fallback matrix cell 3: HIGH tier (ML unavailable) -> REVIEW")
    void testFallbackMatrixCell3_HighTier() {
        assertEquals(Decision.REVIEW, HybridDecisionEngine.getFallbackDecision(RuleTier.HIGH));
    }

    @Test
    @DisplayName("Full evaluate with ML response populates finalScore and mlAvailable=true")
    void testEvaluateWithMLAvailable() {
        RuleEvaluation ruleEval = new RuleEvaluation(35, Collections.singletonList("VELOCITY"), Collections.singletonList("velocity alert"));
        MLPredictionResponse mlResp = new MLPredictionResponse(0.85, "xgboost-v1", 0.18, 0.56, "block", null, 15.0);

        HybridDecisionResult result = engine.evaluate(ruleEval, Optional.of(mlResp));

        assertNotNull(result);
        assertEquals(Decision.BLOCK, result.getDecision());
        assertEquals(RuleTier.MEDIUM, result.getRuleTier());
        assertEquals(35, result.getRuleScore());
        assertTrue(result.isMlAvailable());
        assertEquals(0.85, result.getFraudProbability());
        assertEquals(0.85, result.getFinalScore());
        assertEquals("block", result.getMlBand());
    }

    @Test
    @DisplayName("Full evaluate without ML response uses fallback matrix and sets finalScore=null")
    void testEvaluateWithoutML() {
        RuleEvaluation ruleEval = new RuleEvaluation(55, Collections.singletonList("HIGH_AMOUNT"), Collections.singletonList("high amount alert"));

        HybridDecisionResult result = engine.evaluate(ruleEval, Optional.empty());

        assertNotNull(result);
        assertEquals(Decision.REVIEW, result.getDecision());
        assertEquals(RuleTier.HIGH, result.getRuleTier());
        assertEquals(55, result.getRuleScore());
        assertFalse(result.isMlAvailable());
        assertNull(result.getFraudProbability());
        assertNull(result.getFinalScore());
        assertNull(result.getMlBand());
    }
}
