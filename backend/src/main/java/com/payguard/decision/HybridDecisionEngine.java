package com.payguard.decision;

import com.payguard.ml.MLPredictionResponse;
import com.payguard.transaction.rule.RuleEvaluation;
import com.payguard.transaction.rule.RuleTier;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

@Component
public class HybridDecisionEngine {

    public enum MlBand {
        LOW,
        REVIEW,
        BLOCK;

        public static MlBand fromString(String bandStr) {
            if (bandStr == null) {
                return null;
            }
            return MlBand.valueOf(bandStr.trim().toUpperCase());
        }
    }

    private static final Map<RuleTier, Map<MlBand, Decision>> NORMAL_MATRIX = new EnumMap<>(RuleTier.class);
    private static final Map<RuleTier, Decision> FALLBACK_MATRIX = new EnumMap<>(RuleTier.class);

    static {
        // LOW (<30)
        Map<MlBand, Decision> lowTier = new EnumMap<>(MlBand.class);
        lowTier.put(MlBand.LOW, Decision.APPROVE);
        lowTier.put(MlBand.REVIEW, Decision.REVIEW);
        lowTier.put(MlBand.BLOCK, Decision.BLOCK);
        NORMAL_MATRIX.put(RuleTier.LOW, lowTier);

        // MEDIUM (30-44)
        Map<MlBand, Decision> mediumTier = new EnumMap<>(MlBand.class);
        mediumTier.put(MlBand.LOW, Decision.REVIEW);
        mediumTier.put(MlBand.REVIEW, Decision.REVIEW);
        mediumTier.put(MlBand.BLOCK, Decision.BLOCK);
        NORMAL_MATRIX.put(RuleTier.MEDIUM, mediumTier);

        // HIGH (>=45)
        Map<MlBand, Decision> highTier = new EnumMap<>(MlBand.class);
        highTier.put(MlBand.LOW, Decision.REVIEW);
        highTier.put(MlBand.REVIEW, Decision.BLOCK);
        highTier.put(MlBand.BLOCK, Decision.BLOCK);
        NORMAL_MATRIX.put(RuleTier.HIGH, highTier);

        // Fallback matrix (ML unavailable)
        FALLBACK_MATRIX.put(RuleTier.LOW, Decision.APPROVE);
        FALLBACK_MATRIX.put(RuleTier.MEDIUM, Decision.REVIEW);
        FALLBACK_MATRIX.put(RuleTier.HIGH, Decision.REVIEW);
    }

    public static Decision getMatrixDecision(RuleTier tier, MlBand band) {
        return NORMAL_MATRIX.get(tier).get(band);
    }

    public static Decision getFallbackDecision(RuleTier tier) {
        return FALLBACK_MATRIX.get(tier);
    }

    public HybridDecisionResult evaluate(RuleEvaluation ruleEvaluation, Optional<MLPredictionResponse> mlResponseOpt) {
        int ruleScore = ruleEvaluation.getRuleScore();
        RuleTier ruleTier = RuleTier.fromScore(ruleScore);

        if (mlResponseOpt.isPresent()) {
            MLPredictionResponse mlResponse = mlResponseOpt.get();
            MlBand mlBand = MlBand.fromString(mlResponse.getMlBand());
            Decision decision = NORMAL_MATRIX.get(ruleTier).get(mlBand);
            Double fraudProb = mlResponse.getFraudProbability();

            return new HybridDecisionResult(
                    decision,
                    ruleTier,
                    ruleScore,
                    ruleEvaluation.getTriggeredRules(),
                    ruleEvaluation.getReasons(),
                    true,
                    fraudProb,
                    mlResponse.getMlBand(),
                    mlResponse.getShapReasons(),
                    fraudProb // finalScore = fraudProbability when mlAvailable
            );
        } else {
            Decision fallbackDecision = FALLBACK_MATRIX.get(ruleTier);

            return new HybridDecisionResult(
                    fallbackDecision,
                    ruleTier,
                    ruleScore,
                    ruleEvaluation.getTriggeredRules(),
                    ruleEvaluation.getReasons(),
                    false,
                    null,
                    null,
                    null,
                    null // finalScore = null when ML is unavailable
            );
        }
    }
}
