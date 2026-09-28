package com.payguard.transaction.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RuleEvaluation {

    private final int ruleScore;
    private final List<String> triggeredRules;
    private final List<String> reasons;

    public RuleEvaluation(int ruleScore, List<String> triggeredRules, List<String> reasons) {
        this.ruleScore = ruleScore;
        this.triggeredRules = triggeredRules != null ? Collections.unmodifiableList(new ArrayList<>(triggeredRules)) : Collections.emptyList();
        this.reasons = reasons != null ? Collections.unmodifiableList(new ArrayList<>(reasons)) : Collections.emptyList();
    }

    public int getRuleScore() {
        return ruleScore;
    }

    public List<String> getTriggeredRules() {
        return triggeredRules;
    }

    public List<String> getReasons() {
        return reasons;
    }
}
