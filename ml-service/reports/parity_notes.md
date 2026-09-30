# Parity Notes: Java Phase 4 Backend vs. Python Feature Parity Spec

**Status**: Verified - 100% Parity Confirmed. Zero Discrepancies.

This document details the comparative inspection between the Phase 4 Spring Boot / Java reference implementation (`backend/src/main/java`) and the Phase 5d Python feature contract specification.

---

## 1. Architectural & Historical Processing Invariants

| Concept | Java Reference (`BehavioralFeatureService` & `TransactionRepository`) | Python Parity Spec | Parity Status |
| :--- | :--- | :--- | :--- |
| **History Scope** | `accountId = :accountId AND id <> :currentId AND transactionTimestamp <= :currentTimestamp` | Rows of same account stored prior to arrival, different row, `timestamp <= current timestamp`. | **PASS** |
| **Arrival Order** | Accounts serialized via PostgreSQL transaction advisory lock (`pg_advisory_xact_lock`). History query sorts ties by arrival timestamp (`ORDER BY t.transactionTimestamp DESC, t.createdAt DESC`). | Exact arrival order preserved. No sorting by timestamp before feature evaluation. | **PASS** |
| **Out-of-Order Rows** | SQL filters out rows with `transactionTimestamp > currentTimestamp`. | Stored row with a strictly later timestamp is excluded from valid history. | **PASS** |
| **Self-Exclusion** | Excludes current transaction by `id <> :currentId`. | Current transaction excluded from its own history. | **PASS** |

---

## 2. Behavioral Features Parity Matrix

### Feature 1: `amount`
- **Java**: Stored as `NUMERIC(15,2)`, converted to `BigDecimal`.
- **Python**: Converted from `Decimal` to `float`.
- **Parity**: Matches contract type `float` and value.

### Feature 2: `transactions_last_2_min`
- **Java**: `countPreviousTransactionsInRange` with `since = currentTimestamp.minusMinutes(2)`. Boundary is inclusive: `t.transactionTimestamp >= :since AND t.transactionTimestamp <= :currentTimestamp`.
- **Python**: Count of history transactions where `current.timestamp - 2 minutes <= t.timestamp <= current.timestamp`. Inclusive boundaries.
- **Parity**: Exact match.

### Feature 3: `transactions_last_1_hour`
- **Java**: `countPreviousTransactionsInRange` with `since = currentTimestamp.minusHours(1)`. Boundary is inclusive: `t.transactionTimestamp >= :since AND t.transactionTimestamp <= :currentTimestamp`.
- **Python**: Count of history transactions where `current.timestamp - 1 hour <= t.timestamp <= current.timestamp`. Inclusive boundaries.
- **Parity**: Exact match.

### Feature 4: `account_avg_amount`
- **Java**: `findHistoricalAverageAmount` computes SQL `AVG(t.amount)`. If null (no history), returns `0.00` (`BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)`). Otherwise rounds raw average using `rawAvg.setScale(2, RoundingMode.HALF_UP)`.
- **Python**: Mean of all valid history amounts, rounded to 2 decimal places using `ROUND_HALF_UP`; `0.00` (`0.0`) if empty history.
- **Parity**: Exact match (e.g. `[10.00, 10.01]` mean is `10.005`, rounds HALF_UP to `10.01`).

### Feature 5: `amount_ratio`
- **Java**: If `accountAvgAmount.compareTo(BigDecimal.ZERO) == 0`, returns `0.0000`. Otherwise divides `currentAmount` by `accountAvgAmount` (the rounded 2 dp average) with scale 4 and `RoundingMode.HALF_UP`. If ratio > `999999.9999`, capped at `999999.9999`.
- **Python**: Current amount divided by rounded average, scaled to 4 decimal places using `ROUND_HALF_UP`, capped at `999999.9999`; `0.0` if average is `0.0`.
- **Parity**: Exact match.

### Feature 6: `time_since_previous_seconds`
- **Java**: Queries `ORDER BY t.transactionTimestamp DESC, t.createdAt DESC LIMIT 1`. If found, `ChronoUnit.SECONDS.between(previousTxn.getTransactionTimestamp(), currentTimestamp)`, clamped to `Math.max(0L, seconds)`. If no history, returns `null` (in DB `NULL`).
- **Python**: Elapsed seconds to previous row with maximum timestamp (ties broken by latest arrival index). Returns `-1.0` if no previous transaction.
- **Parity**: Exact match (database `NULL` maps to `-1` for ML contract).

### Feature 7: `is_first_transaction`
- **Java**: `hasPreviousTransactions = !previousList.isEmpty()`.
- **Python**: `1` if history is empty, `0` if history exists.
- **Parity**: Exact match.

### Feature 8 & 9: `new_device` & `new_location`
- **Java**: `isDevicePreviouslyUsed` / `isLocationPreviouslyUsed` checks `COUNT(t) > 0` with matching device/location and `transactionTimestamp <= currentTimestamp`. `newDevice = !deviceUsedBefore`, `newLocation = !locationUsedBefore`. When history is empty, returns `true`.
- **Python**: `1` if device/location not present in valid history (including when history is empty), else `0`.
- **Parity**: Exact match.

### Feature 10 & 11: `transaction_hour` & `odd_hour`
- **Java**: Converted to zone `Asia/Kolkata` (`ZoneId.of("Asia/Kolkata")`), `transactionHour = zdt.getHour()` (0–23). `oddHour = (transactionHour >= 23 || transactionHour < 6)`.
- **Python**: Instant converted to timezone `Asia/Kolkata`, hour extracted (0–23), `odd_hour = 1 if (hour >= 23 or hour < 6) else 0`.
- **Parity**: Exact match.

---

## 3. Fraud Rules Engine Parity

Java rules in `FraudRulesEngine.java` are evaluated in strict order with scores capped at 100:

1. **`VELOCITY`** (Score: 30)
   - Java: `(features.getTransactionsLast2Min() + 1) >= 5`
   - Python: `(features["transactions_last_2_min"] + 1) >= 5`
   - Parity: Identical.

2. **`HIGH_AMOUNT`** (Score: 25)
   - Java: `features.getAccountAvgAmount().compareTo(BigDecimal.ZERO) > 0 && features.getAmountRatio().compareTo(new BigDecimal("3.0")) >= 0`
   - Python: `features["account_avg_amount"] > 0 and features["amount_ratio"] >= 3.0`
   - Parity: Identical.

3. **`NEW_DEVICE`** (Score: 15)
   - Java: `Boolean.TRUE.equals(features.getNewDevice()) && features.hasPreviousTransactions()`
   - Python: `features["new_device"] == 1 and features["is_first_transaction"] == 0`
   - Note: Cold start protection: does NOT trigger on account's very first transaction.
   - Parity: Identical.

4. **`NEW_LOCATION`** (Score: 15)
   - Java: `Boolean.TRUE.equals(features.getNewLocation()) && features.hasPreviousTransactions()`
   - Python: `features["new_location"] == 1 and features["is_first_transaction"] == 0`
   - Note: Cold start protection: does NOT trigger on account's very first transaction.
   - Parity: Identical.

5. **`ODD_HOUR_HIGH_VALUE`** (Score: 15)
   - Java: `Boolean.TRUE.equals(features.getOddHour()) && features.getAccountAvgAmount().compareTo(BigDecimal.ZERO) > 0 && features.getAmountRatio().compareTo(new BigDecimal("2.0")) >= 0`
   - Python: `features["odd_hour"] == 1 and features["account_avg_amount"] > 0 and features["amount_ratio"] >= 2.0`
   - Parity: Identical.

Total score calculation: `min(100, sum_of_scores)`.
Rule triggers list order: `["VELOCITY", "HIGH_AMOUNT", "NEW_DEVICE", "NEW_LOCATION", "ODD_HOUR_HIGH_VALUE"]`.

---

## 4. Conclusion
There are **zero discrepancies** between the Phase 4 Java implementation and the specification. All calculations, boundary conditions, rounding rules (`HALF_UP`), capping, cold start handling, and rules score aggregation in Python will mirror Java exactly.
