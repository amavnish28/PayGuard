package com.payguard.transaction.rule;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class FraudRulesEngine {

    private final List<FraudRule> rules;

    public FraudRulesEngine() {
        this.rules = List.of(
                new VelocityRule(),
                new HighAmountRule(),
                new NewDeviceRule(),
                new NewLocationRule(),
                new OddHourHighValueRule()
        );
    }

    public FraudRulesEngine(List<FraudRule> rules) {
        this.rules = rules;
    }

    public RuleEvaluation evaluate(Transaction transaction, BehavioralFeatureResult features) {
        int totalScore = 0;
        List<String> triggeredRules = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (FraudRule rule : rules) {
            if (rule.evaluate(transaction, features)) {
                totalScore += rule.getScore();
                triggeredRules.add(rule.getName());
                reasons.add(rule.getReason());
            }
        }

        int cappedScore = Math.min(100, totalScore);
        return new RuleEvaluation(cappedScore, triggeredRules, reasons);
    }
}
