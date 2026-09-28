package com.payguard.transaction.rule;

import com.payguard.transaction.Transaction;
import com.payguard.transaction.feature.BehavioralFeatureResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FraudRulesEngineTest {

    private FraudRulesEngine rulesEngine;

    @BeforeEach
    void setUp() {
        rulesEngine = new FraudRulesEngine();
    }

    private Transaction createTransaction(BigDecimal amount) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setTransactionId("TXN-" + UUID.randomUUID());
        t.setAccountId("ACC-001");
        t.setAmount(amount);
        t.setCurrency("INR");
        t.setDeviceId("DEV-001");
        t.setLocation("Delhi");
        t.setMerchantType("RETAIL");
        t.setTransactionTimestamp(OffsetDateTime.parse("2026-09-28T10:00:00+05:30"));
        return t;
    }

    @Test
    @DisplayName("Cold Start: First transaction has newDevice=true and newLocation=true, but rules do NOT trigger (score = 0)")
    void testColdStartRulesDoNotTrigger() {
        Transaction t = createTransaction(new BigDecimal("1500.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                0, 0, new BigDecimal("0.00"), new BigDecimal("0.0000"),
                null, true, true, (short) 10, false, false
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(0, eval.getRuleScore());
        assertTrue(eval.getTriggeredRules().isEmpty());
        assertTrue(eval.getReasons().isEmpty());
    }

    @Test
    @DisplayName("Velocity rule: 4 previous transactions triggers VELOCITY (+30)")
    void testVelocityTriggersAt4Previous() {
        Transaction t = createTransaction(new BigDecimal("1500.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                4, 4, new BigDecimal("1500.00"), new BigDecimal("1.0000"),
                10L, false, false, (short) 10, false, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(30, eval.getRuleScore());
        assertEquals(1, eval.getTriggeredRules().size());
        assertEquals("VELOCITY", eval.getTriggeredRules().get(0));
        assertEquals("5 or more transactions from the account within 2 minutes", eval.getReasons().get(0));
    }

    @Test
    @DisplayName("Velocity rule: 3 previous transactions does NOT trigger VELOCITY")
    void testVelocityDoesNotTriggerAt3Previous() {
        Transaction t = createTransaction(new BigDecimal("1500.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                3, 3, new BigDecimal("1500.00"), new BigDecimal("1.0000"),
                10L, false, false, (short) 10, false, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(0, eval.getRuleScore());
        assertTrue(eval.getTriggeredRules().isEmpty());
    }

    @Test
    @DisplayName("High Amount rule: ratio >= 3.0 and avg > 0 triggers HIGH_AMOUNT (+25)")
    void testHighAmountTriggers() {
        Transaction t = createTransaction(new BigDecimal("4500.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                0, 0, new BigDecimal("1500.00"), new BigDecimal("3.0000"),
                3600L, false, false, (short) 10, false, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(25, eval.getRuleScore());
        assertEquals(1, eval.getTriggeredRules().size());
        assertEquals("HIGH_AMOUNT", eval.getTriggeredRules().get(0));
        assertEquals("Transaction amount is at least 3x the account historical average", eval.getReasons().get(0));
    }

    @Test
    @DisplayName("High Amount rule: ratio < 3.0 does not trigger")
    void testHighAmountDoesNotTriggerBelowThreshold() {
        Transaction t = createTransaction(new BigDecimal("4499.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                0, 0, new BigDecimal("1500.00"), new BigDecimal("2.9993"),
                3600L, false, false, (short) 10, false, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(0, eval.getRuleScore());
        assertTrue(eval.getTriggeredRules().isEmpty());
    }

    @Test
    @DisplayName("New Device rule: newDevice=true and hasPrevious=true triggers NEW_DEVICE (+15)")
    void testNewDeviceTriggersWhenHistoryExists() {
        Transaction t = createTransaction(new BigDecimal("1500.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                0, 0, new BigDecimal("1500.00"), new BigDecimal("1.0000"),
                3600L, true, false, (short) 10, false, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(15, eval.getRuleScore());
        assertEquals(1, eval.getTriggeredRules().size());
        assertEquals("NEW_DEVICE", eval.getTriggeredRules().get(0));
        assertEquals("Transaction uses a device not previously seen for this account", eval.getReasons().get(0));
    }

    @Test
    @DisplayName("New Location rule: newLocation=true and hasPrevious=true triggers NEW_LOCATION (+15)")
    void testNewLocationTriggersWhenHistoryExists() {
        Transaction t = createTransaction(new BigDecimal("1500.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                0, 0, new BigDecimal("1500.00"), new BigDecimal("1.0000"),
                3600L, false, true, (short) 10, false, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(15, eval.getRuleScore());
        assertEquals(1, eval.getTriggeredRules().size());
        assertEquals("NEW_LOCATION", eval.getTriggeredRules().get(0));
        assertEquals("Transaction originates from a location not previously seen for this account", eval.getReasons().get(0));
    }

    @Test
    @DisplayName("Odd-Hour High Value rule: oddHour=true, avg>0, ratio>=2.0 triggers ODD_HOUR_HIGH_VALUE (+15)")
    void testOddHourHighValueTriggers() {
        Transaction t = createTransaction(new BigDecimal("3000.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                0, 0, new BigDecimal("1500.00"), new BigDecimal("2.0000"),
                3600L, false, false, (short) 23, true, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(15, eval.getRuleScore());
        assertEquals(1, eval.getTriggeredRules().size());
        assertEquals("ODD_HOUR_HIGH_VALUE", eval.getTriggeredRules().get(0));
        assertEquals("High-value transaction occurred during an odd hour", eval.getReasons().get(0));
    }

    @Test
    @DisplayName("Odd-Hour High Value rule: ratio < 2.0 does not trigger")
    void testOddHourHighValueDoesNotTriggerWhenRatioLow() {
        Transaction t = createTransaction(new BigDecimal("2900.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                0, 0, new BigDecimal("1500.00"), new BigDecimal("1.9333"),
                3600L, false, false, (short) 23, true, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(0, eval.getRuleScore());
    }

    @Test
    @DisplayName("Multiple rules additive: Velocity (30) + New Device (15) = 45")
    void testAdditiveScoreVelocityAndNewDevice() {
        Transaction t = createTransaction(new BigDecimal("1500.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                4, 4, new BigDecimal("1500.00"), new BigDecimal("1.0000"),
                10L, true, false, (short) 10, false, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(45, eval.getRuleScore());
        assertEquals(2, eval.getTriggeredRules().size());
        assertTrue(eval.getTriggeredRules().contains("VELOCITY"));
        assertTrue(eval.getTriggeredRules().contains("NEW_DEVICE"));
    }

    @Test
    @DisplayName("All rules trigger: 30 + 25 + 15 + 15 + 15 = 100, capped at 100")
    void testAllRulesTriggerAndScoreCappedAt100() {
        Transaction t = createTransaction(new BigDecimal("5000.00"));
        BehavioralFeatureResult features = new BehavioralFeatureResult(
                4, 4, new BigDecimal("1000.00"), new BigDecimal("5.0000"),
                10L, true, true, (short) 23, true, true
        );

        RuleEvaluation eval = rulesEngine.evaluate(t, features);

        assertEquals(100, eval.getRuleScore());
        assertEquals(5, eval.getTriggeredRules().size());
        assertEquals(5, eval.getReasons().size());
        assertEquals(List.of("VELOCITY", "HIGH_AMOUNT", "NEW_DEVICE", "NEW_LOCATION", "ODD_HOUR_HIGH_VALUE"),
                eval.getTriggeredRules());
    }
}
