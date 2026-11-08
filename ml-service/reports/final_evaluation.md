# PayGuard Final Evaluation: Rules-Only vs ML-Only vs Hybrid

Evaluation conducted on the 10,000-transaction simulator benchmark dataset (`run_seed42_10000_transactions.json`).

## 1. Feature Mapping & Replay Configuration

The 11 behavioral features defined in `contracts/feature_schema_v1.json` are mapped from database tables as follows:
- `is_first_transaction`: `1` if `time_since_previous_transaction_seconds IS NULL` else `0`
- `time_since_previous_seconds`: `-1.0` if `time_since_previous_transaction_seconds IS NULL` else the float value
- Boolean indicators (`new_device`, `new_location`, `odd_hour`): mapped to `0` or `1`
- `amount`: taken directly from `transactions.amount`
- Remaining features (`transactions_last_2_min`, `transactions_last_1_hour`, `account_avg_amount`, `amount_ratio`, `transaction_hour`): taken directly from `transaction_features`

Model configuration:
- Model version: `xgboost-v1`
- Review threshold: `0.18657`
- Block threshold: `0.56667`

Decision Matrix:
- Low rule tier (<30): low ML band -> APPROVE; review band -> REVIEW; block band -> BLOCK
- Medium rule tier (30-44): low ML band -> REVIEW; review band -> REVIEW; block band -> BLOCK
- High rule tier (>=45): low ML band -> REVIEW; review band -> BLOCK; block band -> BLOCK
- Fallback (ML unavailable): LOW -> APPROVE; MEDIUM -> REVIEW; HIGH -> REVIEW

---

## 2. Validation Gates (G1 to G5)

| Gate | Description | Checked | Mismatches / Failures | Status |
| :--- | :--- | :---: | :---: | :---: |
| **G1** | Ground truth matching to simulator run log | 10,000 txns | 0 unmatched, 0 duplicates | **PASS** |
| **G2** | Rule score recomputation matches stored alert `rule_score` (within 0.01) | 722 alerts | 0 | **PASS** |
| **G3** | ML probability recomputation matches stored `fraud_probability` (within 0.0001) | 722 alerts | 0 | **PASS** |
| **G4** | Hybrid decision recomputation matches stored alert `decision` (excl. exceptions) | 722 alerts | 0 | **PASS** |
| **G5** | Transactions without alerts have recomputed hybrid decision `APPROVE` | 9276 txns | 0 | **PASS** |

### Known Exceptions (Audit Log: `ML_SERVICE_UNAVAILABLE`)
Two transactions encountered simulated ML service downtime:
1. `SIM-6adb3870-SIM-ACC-0006-004346`: Stored decision: `APPROVE` (no alert generated). Recomputed Tier: `LOW`, ML Band: `low`. Recomputed Hybrid: `APPROVE`, Fallback Decision: `APPROVE`. Stored equals fallback: `True`.
2. `SIM-6adb3870-SIM-ACC-0013-004347`: Stored decision: `APPROVE` (no alert generated). Recomputed Tier: `LOW`, ML Band: `low`. Recomputed Hybrid: `APPROVE`, Fallback Decision: `APPROVE`. Stored equals fallback: `True`.

Sanity check: Recomputed Hybrid (C) and Live Hybrid (D) decisions differ by exactly **0** rows across all 10,000 transactions.

---

## 3. Flagged-Level Evaluation Metrics (Predicted Positive = REVIEW or BLOCK)

Ground truth fraud count: **1,143** (Positive: `scenario != 'LEGIT'`), Legitimate count: **8,857**.

| System | TP | FP | FN | TN | Recall (95% CI) | Precision | FPR (95% CI) | F1 Score |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A: Rules-only (score >= 30)** | 488 | 2 | 655 | 8855 | 0.4269 [0.3986, 0.4558] | 0.9959 | 0.0002 [0.0001, 0.0008] | 0.5977 |
| **A2: Rules-any (score > 0)** | 648 | 119 | 495 | 8738 | 0.5669 [0.5380, 0.5954] | 0.8449 | 0.0134 [0.0112, 0.0161] | 0.6785 |
| **B: ML-only (prob >= 0.18657)** | 560 | 1 | 583 | 8856 | 0.4899 [0.4610, 0.5189] | 0.9982 | 0.0001 [0.0000, 0.0006] | 0.6573 |
| **C: Hybrid Recomputed** | 719 | 3 | 424 | 8854 | 0.6290 [0.6007, 0.6566] | 0.9958 | 0.0003 [0.0001, 0.0010] | 0.7710 |
| **D: Hybrid Live** | 719 | 3 | 424 | 8854 | 0.6290 [0.6007, 0.6566] | 0.9958 | 0.0003 [0.0001, 0.0010] | 0.7710 |

---

## 4. Block-Level Evaluation Metrics (Predicted Positive = BLOCK Only)

| System | TP | FP | FN | TN | Recall (95% CI) | Precision | FPR (95% CI) | F1 Score |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A: Rules-only** | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| **B: ML-only (prob >= 0.56667)** | 451 | 1 | 692 | 8856 | 0.3946 [0.3666, 0.4232] | 0.9978 | 0.0001 [0.0000, 0.0006] | 0.5655 |
| **C: Hybrid Recomputed** | 454 | 1 | 689 | 8856 | 0.3972 [0.3692, 0.4259] | 0.9978 | 0.0001 [0.0000, 0.0006] | 0.5682 |
| **D: Hybrid Live** | 454 | 1 | 689 | 8856 | 0.3972 [0.3692, 0.4259] | 0.9978 | 0.0001 [0.0000, 0.0006] | 0.5682 |

*Note: Rules-only system has no BLOCK outcome by design.*

---

## 5. Per-Scenario Detection Breakdown

Flagged counts and rates by scenario across all systems:

| Scenario | Total Txns | Rules (A >= 30) | Rules (A2 > 0) | ML (B >= review) | Hybrid Recomputed (C) | Hybrid Live (D) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **ACCOUNT_TAKEOVER** | 206 | 146 (70.87%) | 196 (95.15%) | 155 (75.24%) | 181 (87.86%) | 181 (87.86%) |
| **CARD_TESTING** | 618 | 252 (40.78%) | 308 (49.84%) | 287 (46.44%) | 408 (66.02%) | 408 (66.02%) |
| **ODD_HOUR_TRANSFER** | 121 | 90 (74.38%) | 121 (100.00%) | 101 (83.47%) | 113 (93.39%) | 113 (93.39%) |
| **SUBTLE** | 113 | 0 (0.00%) | 23 (20.35%) | 17 (15.04%) | 17 (15.04%) | 17 (15.04%) |
| **STEALTH** | 85 | 0 (0.00%) | 0 (0.00%) | 0 (0.00%) | 0 (0.00%) | 0 (0.00%) |
| **LEGIT** | 8857 | 2 (0.02%) | 119 (1.34%) | 1 (0.01%) | 3 (0.03%) | 3 (0.03%) |

---

## 6. Fraud Detection Overlap (Rules-Only A vs ML-Only B)

Among the **1143** actual fraud transactions:
- **Flagged by Both Systems**: 329 (28.78%)
- **Flagged by Rules Only (A only)**: 159 (13.91%)
- **Flagged by ML Only (B only)**: 231 (20.21%)
- **Flagged by Neither System**: 424 (37.10%)

### Ranking Quality (Average Precision / PR-AUC)
- **ML Fraud Probability**: `0.84485`
- **Deterministic Rule Score**: `0.59964`

---

## 7. Limitations

1. **Synthetic Process Origin**: Scenarios and synthetic patterns originate from the same generative distribution and assumptions that produced the training data. Real-world fraud patterns evolve dynamically and may present novel evasion techniques.
2. **Threshold Calibration**: The ML decision thresholds (`review_threshold = 0.18657`, `block_threshold = 0.56667`) were calibrated and locked based on the offline synthetic validation split, rather than being tuned to this specific 10,000-transaction simulation run.
3. **Single Seed & Single Run**: All reported metrics reflect an evaluation on a single simulation run initialized with seed 42. Multi-seed runs across longer time spans are required to observe variance under varying arrival sequences.
4. **Analyst Feedback / Verdict Labels**: Analyst verdicts from production review were not utilized in this evaluation; ground truth was established strictly by matching transactions to simulator scenario generation tags.
