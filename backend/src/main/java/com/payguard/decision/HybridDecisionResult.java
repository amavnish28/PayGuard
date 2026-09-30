package com.payguard.decision;

import com.payguard.ml.ShapReason;
import com.payguard.transaction.rule.RuleTier;

import java.util.ArrayList;
import java.util.List;

public class HybridDecisionResult {

    private final Decision decision;
    private final RuleTier ruleTier;
    private final int ruleScore;
    private final List<String> triggeredRules;
    private final List<String> ruleReasons;
    private final boolean mlAvailable;
    private final Double fraudProbability;
    private final String mlBand;
    private final List<ShapReason> shapReasons;
    private final Double finalScore;

    public HybridDecisionResult(Decision decision,
                                RuleTier ruleTier,
                                int ruleScore,
                                List<String> triggeredRules,
                                List<String> ruleReasons,
                                boolean mlAvailable,
                                Double fraudProbability,
                                String mlBand,
                                List<ShapReason> shapReasons,
                                Double finalScore) {
        this.decision = decision;
        this.ruleTier = ruleTier;
        this.ruleScore = ruleScore;
        this.triggeredRules = triggeredRules != null ? triggeredRules : new ArrayList<>();
        this.ruleReasons = ruleReasons != null ? ruleReasons : new ArrayList<>();
        this.mlAvailable = mlAvailable;
        this.fraudProbability = fraudProbability;
        this.mlBand = mlBand;
        this.shapReasons = shapReasons;
        this.finalScore = finalScore;
    }

    public Decision getDecision() {
        return decision;
    }

    public RuleTier getRuleTier() {
        return ruleTier;
    }

    public int getRuleScore() {
        return ruleScore;
    }

    public List<String> getTriggeredRules() {
        return triggeredRules;
    }

    public List<String> getRuleReasons() {
        return ruleReasons;
    }

    public boolean isMlAvailable() {
        return mlAvailable;
    }

    public Double getFraudProbability() {
        return fraudProbability;
    }

    public String getMlBand() {
        return mlBand;
    }

    public List<ShapReason> getShapReasons() {
        return shapReasons;
    }

    public Double getFinalScore() {
        return finalScore;
    }
}
