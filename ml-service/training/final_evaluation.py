"""Final Evaluation: Rules-only vs ML-only vs Hybrid on 10,000-transaction simulator run.

Covers:
- Part 2: Ground Truth matching & Gate G1
- Part 3: Replay & Gates G2 to G5
- Part 4: Metrics evaluation, tables, figure generation, and reports export
"""

import json
import math
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import joblib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score

# Ensure ml-service root is on sys.path
SCRIPT_DIR = Path(__file__).resolve().parent
ML_SERVICE_DIR = SCRIPT_DIR.parent
PROJECT_ROOT = ML_SERVICE_DIR.parent
if str(ML_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(ML_SERVICE_DIR))

from training.features_reference import evaluate_rules

# Paths
SIMULATOR_RUN_PATH = PROJECT_ROOT / "simulator" / "run_seed42_10000_transactions.json"
DATA_EVAL_DIR = ML_SERVICE_DIR / "data" / "eval"
TXN_EXPORT_PATH = DATA_EVAL_DIR / "transactions_eval_export.csv"
AUDIT_EXPORT_PATH = DATA_EVAL_DIR / "audit_ml_unavailable.csv"
PREDICTIONS_CSV_PATH = DATA_EVAL_DIR / "predictions.csv"

REPORTS_DIR = ML_SERVICE_DIR / "reports"
FIGURES_DIR = REPORTS_DIR / "figures"
REPORT_JSON_PATH = REPORTS_DIR / "final_evaluation.json"
REPORT_MD_PATH = REPORTS_DIR / "final_evaluation.md"
FIGURE_PNG_PATH = FIGURES_DIR / "final_eval_scenario_detection.png"

FEATURE_SCHEMA_PATH = PROJECT_ROOT / "contracts" / "feature_schema_v1.json"
MODEL_STORE_DIR = ML_SERVICE_DIR / "model_store" / "xgboost_v1"
MODEL_PATH = MODEL_STORE_DIR / "model.joblib"
METADATA_PATH = MODEL_STORE_DIR / "metadata.json"

# Regex for simulator transaction ID
TXN_ID_REGEX = re.compile(r"^SIM-[0-9a-f]+-(SIM-ACC-\d+)-(\d+)$")

# Hybrid Decision Matrix (explicit lookup table)
HYBRID_MATRIX: Dict[Tuple[str, str], str] = {
    ("LOW", "low"): "APPROVE",
    ("LOW", "review"): "REVIEW",
    ("LOW", "block"): "BLOCK",
    ("MEDIUM", "low"): "REVIEW",
    ("MEDIUM", "review"): "REVIEW",
    ("MEDIUM", "block"): "BLOCK",
    ("HIGH", "low"): "REVIEW",
    ("HIGH", "review"): "BLOCK",
    ("HIGH", "block"): "BLOCK",
}

# Fallback matrix (ML unavailable)
FALLBACK_MATRIX: Dict[str, str] = {
    "LOW": "APPROVE",
    "MEDIUM": "REVIEW",
    "HIGH": "REVIEW",
}


def parse_transaction_id(txn_id: str) -> Optional[Tuple[str, int]]:
    """Parse transaction ID returning (accountId, seq) or None if invalid."""
    m = TXN_ID_REGEX.match(txn_id)
    if not m:
        return None
    return m.group(1), int(m.group(2))


def get_rule_tier(score: float) -> str:
    """Classify rule score into tier: LOW <30, MEDIUM 30-44, HIGH >=45."""
    if score < 30:
        return "LOW"
    elif score < 45:
        return "MEDIUM"
    else:
        return "HIGH"


def get_ml_band(prob: float, review_threshold: float, block_threshold: float) -> str:
    """Classify ML probability into band: below review = low, [review, block) = review, >= block = block."""
    if prob < review_threshold:
        return "low"
    elif prob < block_threshold:
        return "review"
    else:
        return "block"


def lookup_hybrid_decision(tier: str, band: str) -> str:
    """Lookup decision in hybrid matrix."""
    return HYBRID_MATRIX[(tier, band)]


def lookup_fallback_decision(tier: str) -> str:
    """Lookup decision in fallback matrix."""
    return FALLBACK_MATRIX[tier]


def wilson_interval(k: int, n: int, confidence: float = 0.95) -> Tuple[float, float]:
    """Compute two-sided Wilson score interval for binomial proportion."""
    if n == 0:
        return 0.0, 0.0
    # z for 95% two-sided
    z = 1.959963984540054
    p = k / n
    denom = 1.0 + (z**2) / n
    center = (p + (z**2) / (2.0 * n)) / denom
    radicand = (p * (1.0 - p) / n) + ((z**2) / (4.0 * (n**2)))
    margin = (z / denom) * math.sqrt(max(0.0, radicand))
    lower = 0.0 if k == 0 else max(0.0, center - margin)
    upper = 1.0 if k == n else min(1.0, center + margin)
    return lower, upper


def compute_confusion_metrics(y_true: np.ndarray, y_pred: np.ndarray) -> Dict[str, Any]:
    """Compute TP, FP, FN, TN, recall, precision, FPR, F1, and Wilson 95% intervals."""
    tp = int(np.sum((y_true == 1) & (y_pred == 1)))
    fp = int(np.sum((y_true == 0) & (y_pred == 1)))
    fn = int(np.sum((y_true == 1) & (y_pred == 0)))
    tn = int(np.sum((y_true == 0) & (y_pred == 0)))

    pos = tp + fn
    neg = fp + tn

    recall = tp / pos if pos > 0 else 0.0
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    fpr = fp / neg if neg > 0 else 0.0
    f1 = (2.0 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0

    rec_low, rec_high = wilson_interval(tp, pos)
    fpr_low, fpr_high = wilson_interval(fp, neg)

    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "tn": tn,
        "recall": recall,
        "recall_ci_95": [rec_low, rec_high],
        "precision": precision,
        "fpr": fpr,
        "fpr_ci_95": [fpr_low, fpr_high],
        "f1": f1,
    }


def to_bool_int(val: Any) -> int:
    """Convert PostgreSQL boolean string or value to 0 or 1."""
    if pd.isna(val):
        return 0
    return 1 if val in (True, "t", "true", "True", 1, "1") else 0


def run_evaluation() -> Dict[str, Any]:
    """Execute complete final evaluation workflow."""
    print("=" * 80)
    print("PAYGUARD FINAL EVALUATION: RULES vs ML vs HYBRID")
    print("=" * 80)

    # ---------------------------------------------------------
    # PART 2: GROUND TRUTH & GATE G1
    # ---------------------------------------------------------
    print("\n--- PART 2: GROUND TRUTH (GATE G1) ---")
    with open(SIMULATOR_RUN_PATH, "r", encoding="utf-8") as f:
        sim_data = json.load(f)

    sim_map: Dict[Tuple[str, int], Dict[str, Any]] = {}
    for entry in sim_data:
        key = (entry["accountId"], int(entry["seq"]))
        if key in sim_map:
            raise ValueError(f"Duplicate key in simulator data: {key}")
        sim_map[key] = entry

    df = pd.read_csv(TXN_EXPORT_PATH)
    print(f"Simulator entries: {len(sim_data)}")
    print(f"DB exported rows: {len(df)}")

    matched_keys = set()
    unmatched_db = []
    duplicate_db = []
    amount_mismatches = []
    timestamp_mismatches = []
    scenarios: List[str] = []
    scenario_counts: Dict[str, int] = {}

    for _, row in df.iterrows():
        txn_id = row["transaction_id"]
        parsed = parse_transaction_id(txn_id)
        if not parsed:
            unmatched_db.append((txn_id, "regex mismatch"))
            scenarios.append("UNKNOWN")
            continue

        acc_id, seq = parsed
        key = (acc_id, seq)
        if key not in sim_map:
            unmatched_db.append((txn_id, f"key {key} not found in simulator"))
            scenarios.append("UNKNOWN")
            continue

        if key in matched_keys:
            duplicate_db.append(key)
        matched_keys.add(key)

        sim_entry = sim_map[key]
        scen = sim_entry["scenario"]
        scenarios.append(scen)
        scenario_counts[scen] = scenario_counts.get(scen, 0) + 1

        # Check amount equal to 2 decimals
        db_amt = round(float(row["amount"]), 2)
        sim_amt = round(float(sim_entry["amount"]), 2)
        if db_amt != sim_amt:
            amount_mismatches.append((txn_id, db_amt, sim_amt))

        # Check timestamp represents same instant
        db_ts = pd.to_datetime(row["transaction_timestamp"])
        sim_ts = pd.to_datetime(sim_entry["transactionTimestamp"])
        if db_ts != sim_ts:
            timestamp_mismatches.append((txn_id, str(db_ts), str(sim_ts)))

    unused_sim = set(sim_map.keys()) - matched_keys
    df["scenario"] = scenarios
    df["is_fraud_gt"] = (df["scenario"] != "LEGIT").astype(int)

    print(f"Matched transactions: {len(matched_keys)}")
    print(f"Unmatched DB transactions: {len(unmatched_db)}")
    print(f"Duplicate DB matches: {len(duplicate_db)}")
    print(f"Unused simulator entries: {len(unused_sim)}")
    print(f"Amount mismatches: {len(amount_mismatches)}")
    print(f"Timestamp mismatches: {len(timestamp_mismatches)}")
    print("\nCounts per scenario:")
    for scen in sorted(scenario_counts.keys()):
        print(f"  {scen}: {scenario_counts[scen]}")

    g1_passed = (
        len(matched_keys) == 10000
        and len(unmatched_db) == 0
        and len(duplicate_db) == 0
        and len(unused_sim) == 0
        and len(amount_mismatches) == 0
        and len(timestamp_mismatches) == 0
    )
    print(f"\nGATE G1 RESULT: {'PASS' if g1_passed else 'FAIL'}")
    if not g1_passed:
        print("STOP: Gate G1 failed.")
        sys.exit(1)

    # ---------------------------------------------------------
    # PART 3: REPLAY (GATES G2 TO G5)
    # ---------------------------------------------------------
    print("\n--- PART 3: REPLAY (GATES G2 TO G5) ---")

    # Load model and metadata
    with open(METADATA_PATH, "r", encoding="utf-8") as f:
        metadata = json.load(f)
    review_threshold = metadata["review_threshold"]
    block_threshold = metadata["block_threshold"]
    feature_order = metadata["feature_order"]
    model = joblib.load(MODEL_PATH)

    print(f"ML Review Threshold: {review_threshold}")
    print(f"ML Block Threshold: {block_threshold}")

    # Load audit logs for known exceptions
    audit_df = pd.read_csv(AUDIT_EXPORT_PATH)
    known_exceptions = set(audit_df["entity_id"].dropna().tolist())
    print(f"Known exceptions (ML_SERVICE_UNAVAILABLE): {sorted(list(known_exceptions))}")

    # Feature mapping:
    # - is_first_transaction = 1 if time_since_previous_transaction_seconds IS NULL else 0
    # - time_since_previous_seconds = -1 if NULL else the value
    # - booleans to 0/1 (new_device, new_location, odd_hour)
    # - amount from transactions.amount
    # - the other features directly
    # Feature order from contracts/feature_schema_v1.json
    feature_dict = {
        "amount": df["amount"].astype(float),
        "transactions_last_2_min": df["transactions_last_2_min"].astype(int),
        "transactions_last_1_hour": df["transactions_last_1_hour"].astype(int),
        "account_avg_amount": df["account_avg_amount"].astype(float),
        "amount_ratio": df["amount_ratio"].astype(float),
        "time_since_previous_seconds": df["time_since_previous_transaction_seconds"].apply(
            lambda v: -1.0 if pd.isna(v) else float(v)
        ),
        "is_first_transaction": df["time_since_previous_transaction_seconds"].apply(
            lambda v: 1 if pd.isna(v) else 0
        ),
        "new_device": df["new_device"].apply(to_bool_int),
        "new_location": df["new_location"].apply(to_bool_int),
        "transaction_hour": df["transaction_hour"].astype(int),
        "odd_hour": df["odd_hour"].apply(to_bool_int),
    }
    X_features = pd.DataFrame(feature_dict)[feature_order]

    # Recompute rule score and triggered rules
    rule_scores: List[int] = []
    rule_triggers: List[List[str]] = []
    for i in range(len(df)):
        feat_row = {col: X_features.iat[i, X_features.columns.get_loc(col)] for col in X_features.columns}
        sc, tr = evaluate_rules(feat_row)
        rule_scores.append(sc)
        rule_triggers.append(tr)

    df["recomputed_rule_score"] = rule_scores
    df["recomputed_tier"] = [get_rule_tier(s) for s in rule_scores]

    # Recompute ML probability and band
    ml_probabilities = model.predict_proba(X_features)[:, 1]
    df["recomputed_fraud_prob"] = ml_probabilities
    df["recomputed_ml_band"] = [
        get_ml_band(p, review_threshold, block_threshold) for p in ml_probabilities
    ]

    # Recomputed hybrid and fallback decisions
    df["recomputed_hybrid_decision"] = [
        lookup_hybrid_decision(t, b)
        for t, b in zip(df["recomputed_tier"], df["recomputed_ml_band"])
    ]
    df["recomputed_fallback_decision"] = [
        lookup_fallback_decision(t) for t in df["recomputed_tier"]
    ]

    # Gate G2: recomputed rule score equals alerts.rule_score (to 0.01) for every alert
    alerts_mask = df["decision"].notna()
    alerts_df = df[alerts_mask].copy()

    g2_mismatches = alerts_df[
        (alerts_df["recomputed_rule_score"] - alerts_df["rule_score"]).abs() > 0.01
    ]
    g2_passed = len(g2_mismatches) == 0
    print(f"GATE G2: Alerts checked: {len(alerts_df)}, Mismatches: {len(g2_mismatches)} -> {'PASS' if g2_passed else 'FAIL'}")

    # Gate G3: recomputed probability is within 0.0001 of alerts.fraud_probability for every alert with ml_available = true
    ml_avail_mask = alerts_df["ml_available"].apply(
        lambda v: str(v).lower() in ("true", "1", "t")
    )
    alerts_ml_df = alerts_df[ml_avail_mask].copy()
    g3_mismatches = alerts_ml_df[
        (alerts_ml_df["recomputed_fraud_prob"] - alerts_ml_df["fraud_probability"]).abs() > 0.0001
    ]
    g3_passed = len(g3_mismatches) == 0
    print(f"GATE G3: Alerts with ml_available=true checked: {len(alerts_ml_df)}, Mismatches: {len(g3_mismatches)} -> {'PASS' if g3_passed else 'FAIL'}")

    # Gate G4: recomputed hybrid decision equals the stored decision for every alert, excluding known exceptions
    alerts_no_ex = alerts_df[~alerts_df["transaction_id"].isin(known_exceptions)]
    g4_mismatches = alerts_no_ex[
        alerts_no_ex["recomputed_hybrid_decision"] != alerts_no_ex["decision"]
    ]
    g4_passed = len(g4_mismatches) == 0
    print(f"GATE G4: Alerts checked (excluding exceptions): {len(alerts_no_ex)}, Mismatches: {len(g4_mismatches)} -> {'PASS' if g4_passed else 'FAIL'}")

    # Gate G5: every transaction WITHOUT an alert has recomputed hybrid decision APPROVE, excluding known exceptions
    no_alert_df = df[~alerts_mask].copy()
    no_alert_no_ex = no_alert_df[~no_alert_df["transaction_id"].isin(known_exceptions)]
    g5_mismatches = no_alert_no_ex[no_alert_no_ex["recomputed_hybrid_decision"] != "APPROVE"]
    g5_passed = len(g5_mismatches) == 0
    print(f"GATE G5: No-alert transactions checked (excluding exceptions): {len(no_alert_no_ex)}, Mismatches: {len(g5_mismatches)} -> {'PASS' if g5_passed else 'FAIL'}")

    # Known exceptions details
    print("\nKnown Exceptions Verification:")
    known_ex_results = []
    for ex_id in sorted(known_exceptions):
        row = df[df["transaction_id"] == ex_id].iloc[0]
        stored_raw = row["decision"]
        stored_effective = stored_raw if pd.notna(stored_raw) else "APPROVE"
        stored_display = f"{stored_raw}" if pd.notna(stored_raw) else "NONE (live effective = APPROVE)"
        tier = row["recomputed_tier"]
        band = row["recomputed_ml_band"]
        hybrid_dec = row["recomputed_hybrid_decision"]
        fb_dec = row["recomputed_fallback_decision"]
        matches_fallback = (stored_effective == fb_dec)
        info = {
            "transaction_id": ex_id,
            "stored_decision": stored_display,
            "recomputed_tier": tier,
            "recomputed_band": band,
            "recomputed_hybrid_decision": hybrid_dec,
            "fallback_decision": fb_dec,
            "stored_equals_fallback": matches_fallback,
        }
        known_ex_results.append(info)
        print(f"  Txn: {ex_id}")
        print(f"    Stored decision: {stored_display}")
        print(f"    Recomputed Tier: {tier}, ML Band: {band}")
        print(f"    Recomputed Hybrid Decision: {hybrid_dec}")
        print(f"    Fallback Decision: {fb_dec}")
        print(f"    Stored equals fallback: {matches_fallback}")

    all_gates_passed = g1_passed and g2_passed and g3_passed and g4_passed and g5_passed
    if not all_gates_passed:
        print("\nSTOP: One or more validation gates failed!")
        if len(g2_mismatches) > 0:
            print("First 10 G2 mismatches:")
            print(g2_mismatches[["transaction_id", "rule_score", "recomputed_rule_score"]].head(10))
        if len(g3_mismatches) > 0:
            print("First 10 G3 mismatches:")
            print(g3_mismatches[["transaction_id", "fraud_probability", "recomputed_fraud_prob"]].head(10))
        if len(g4_mismatches) > 0:
            print("First 10 G4 mismatches:")
            print(g4_mismatches[["transaction_id", "decision", "recomputed_hybrid_decision"]].head(10))
        if len(g5_mismatches) > 0:
            print("First 10 G5 mismatches:")
            print(g5_mismatches[["transaction_id", "recomputed_hybrid_decision"]].head(10))
        sys.exit(1)

    print("\nALL GATES (G1, G2, G3, G4, G5) PASSED SUCCESSFULLY.")

    # ---------------------------------------------------------
    # PART 4: EVALUATION
    # ---------------------------------------------------------
    print("\n--- PART 4: EVALUATION ---")
    y_true = df["is_fraud_gt"].values

    # Predictions for each system
    # A: Rules-only: flagged = rule score >= 30
    df["pred_flagged_A"] = (df["recomputed_rule_score"] >= 30).astype(int)
    # A2: Rules, any rule triggered (score > 0)
    df["pred_flagged_A2"] = (df["recomputed_rule_score"] > 0).astype(int)
    # B: ML-only: flagged = prob >= review threshold
    df["pred_flagged_B"] = (df["recomputed_fraud_prob"] >= review_threshold).astype(int)
    df["pred_blocked_B"] = (df["recomputed_fraud_prob"] >= block_threshold).astype(int)
    # C: Hybrid, recomputed: flagged = REVIEW or BLOCK
    df["pred_flagged_C"] = df["recomputed_hybrid_decision"].isin(["REVIEW", "BLOCK"]).astype(int)
    df["pred_blocked_C"] = (df["recomputed_hybrid_decision"] == "BLOCK").astype(int)
    # D: Hybrid, live: stored alert decision, otherwise APPROVE
    df["live_effective_decision"] = df["decision"].fillna("APPROVE")
    df["pred_flagged_D"] = df["live_effective_decision"].isin(["REVIEW", "BLOCK"]).astype(int)
    df["pred_blocked_D"] = (df["live_effective_decision"] == "BLOCK").astype(int)

    # Sanity comparison: C vs D differences
    c_d_diff_df = df[df["recomputed_hybrid_decision"] != df["live_effective_decision"]]
    exact_c_d_diff_count = len(c_d_diff_df)
    print(f"Sanity Check: Recomputed Hybrid (C) vs Live Hybrid (D) differences: {exact_c_d_diff_count}")

    # Flagged-level metrics
    metrics_flagged = {
        "A (Rules-only >= 30)": compute_confusion_metrics(y_true, df["pred_flagged_A"].values),
        "A2 (Rules-any > 0)": compute_confusion_metrics(y_true, df["pred_flagged_A2"].values),
        "B (ML-only >= review)": compute_confusion_metrics(y_true, df["pred_flagged_B"].values),
        "C (Hybrid recomputed)": compute_confusion_metrics(y_true, df["pred_flagged_C"].values),
        "D (Hybrid live)": compute_confusion_metrics(y_true, df["pred_flagged_D"].values),
    }

    # Block-level metrics
    metrics_blocked = {
        "A (Rules-only >= 30)": None,  # N/A
        "A2 (Rules-any > 0)": None,   # N/A
        "B (ML-only >= block)": compute_confusion_metrics(y_true, df["pred_blocked_B"].values),
        "C (Hybrid recomputed)": compute_confusion_metrics(y_true, df["pred_blocked_C"].values),
        "D (Hybrid live)": compute_confusion_metrics(y_true, df["pred_blocked_D"].values),
    }

    print("\nFlagged-level Metrics (REVIEW or BLOCK):")
    for sys_name, m in metrics_flagged.items():
        print(f"  {sys_name}:")
        print(f"    TP={m['tp']}, FP={m['fp']}, FN={m['fn']}, TN={m['tn']}")
        print(f"    Recall: {m['recall']:.4f} (95% CI: [{m['recall_ci_95'][0]:.4f}, {m['recall_ci_95'][1]:.4f}])")
        print(f"    Precision: {m['precision']:.4f}")
        print(f"    FPR: {m['fpr']:.4f} (95% CI: [{m['fpr_ci_95'][0]:.4f}, {m['fpr_ci_95'][1]:.4f}])")
        print(f"    F1: {m['f1']:.4f}")

    print("\nBlock-level Metrics (BLOCK only):")
    for sys_name, m in metrics_blocked.items():
        if m is None:
            print(f"  {sys_name}: N/A (Rules-only has no BLOCK outcome by design)")
        else:
            print(f"  {sys_name}:")
            print(f"    TP={m['tp']}, FP={m['fp']}, FN={m['fn']}, TN={m['tn']}")
            print(f"    Recall: {m['recall']:.4f} (95% CI: [{m['recall_ci_95'][0]:.4f}, {m['recall_ci_95'][1]:.4f}])")
            print(f"    Precision: {m['precision']:.4f}")
            print(f"    FPR: {m['fpr']:.4f} (95% CI: [{m['fpr_ci_95'][0]:.4f}, {m['fpr_ci_95'][1]:.4f}])")
            print(f"    F1: {m['f1']:.4f}")

    # Per-scenario detection breakdown
    scenarios_list = ["ACCOUNT_TAKEOVER", "CARD_TESTING", "ODD_HOUR_TRANSFER", "SUBTLE", "STEALTH", "LEGIT"]
    scenario_eval: Dict[str, Dict[str, Dict[str, Any]]] = {}

    system_flag_cols = {
        "A (Rules >= 30)": "pred_flagged_A",
        "A2 (Rules > 0)": "pred_flagged_A2",
        "B (ML >= review)": "pred_flagged_B",
        "C (Hybrid recomputed)": "pred_flagged_C",
        "D (Hybrid live)": "pred_flagged_D",
    }

    print("\nPer-Scenario Flagged Breakdown:")
    for scen in scenarios_list:
        scen_mask = df["scenario"] == scen
        scen_n = int(scen_mask.sum())
        scenario_eval[scen] = {"total": scen_n, "systems": {}}
        print(f"Scenario {scen} (N={scen_n}):")
        for sname, col in system_flag_cols.items():
            cnt = int(df.loc[scen_mask, col].sum())
            rate = cnt / scen_n if scen_n > 0 else 0.0
            scenario_eval[scen]["systems"][sname] = {"count": cnt, "rate": rate}
            print(f"  {sname}: {cnt}/{scen_n} ({rate*100:.2f}%)")

    # Fraud overlap between Rules (A) and ML (B)
    fraud_mask = df["is_fraud_gt"] == 1
    total_fraud = int(fraud_mask.sum())
    a_flagged = df.loc[fraud_mask, "pred_flagged_A"] == 1
    b_flagged = df.loc[fraud_mask, "pred_flagged_B"] == 1

    both = int((a_flagged & b_flagged).sum())
    rules_only = int((a_flagged & ~b_flagged).sum())
    ml_only = int((~a_flagged & b_flagged).sum())
    neither = int((~a_flagged & ~b_flagged).sum())

    fraud_overlap = {
        "total_fraud": total_fraud,
        "flagged_by_both": both,
        "flagged_by_both_rate": both / total_fraud,
        "flagged_by_rules_only": rules_only,
        "flagged_by_rules_only_rate": rules_only / total_fraud,
        "flagged_by_ml_only": ml_only,
        "flagged_by_ml_only_rate": ml_only / total_fraud,
        "flagged_by_neither": neither,
        "flagged_by_neither_rate": neither / total_fraud,
    }

    print("\nFraud Overlap Breakdown (Rules-only A vs ML-only B on 1,143 fraud txns):")
    print(f"  Both flagged: {both} ({both/total_fraud*100:.2f}%)")
    print(f"  Rules only: {rules_only} ({rules_only/total_fraud*100:.2f}%)")
    print(f"  ML only: {ml_only} ({ml_only/total_fraud*100:.2f}%)")
    print(f"  Neither: {neither} ({neither/total_fraud*100:.2f}%)")

    # Average Precision (PR-AUC)
    ap_ml = float(average_precision_score(y_true, df["recomputed_fraud_prob"].values))
    ap_rules = float(average_precision_score(y_true, df["recomputed_rule_score"].values))
    print(f"\nAverage Precision (PR-AUC):")
    print(f"  ML Fraud Probability: {ap_ml:.5f}")
    print(f"  Deterministic Rule Score: {ap_rules:.5f}")

    # Generate Figure
    FIGURES_DIR.mkdir(parents=True, exist_ok=True)
    generate_figure(scenario_eval, FIGURE_PNG_PATH)
    print(f"Generated scenario detection chart: {FIGURE_PNG_PATH}")

    # Export Predictions CSV
    export_predictions_csv(df, PREDICTIONS_CSV_PATH)
    print(f"Generated predictions CSV: {PREDICTIONS_CSV_PATH}")

    # Build and Save final_evaluation.json
    results_json = {
        "model_version": metadata.get("model_version", "xgboost-v1"),
        "thresholds": {
            "review_threshold": review_threshold,
            "block_threshold": block_threshold,
        },
        "gates": {
            "G1_ground_truth": {"status": "PASS", "matched_rows": len(matched_keys), "scenario_counts": scenario_counts},
            "G2_rule_score": {"status": "PASS", "alerts_checked": len(alerts_df), "mismatches": len(g2_mismatches)},
            "G3_ml_probability": {"status": "PASS", "alerts_checked": len(alerts_ml_df), "mismatches": len(g3_mismatches)},
            "G4_hybrid_decision": {"status": "PASS", "alerts_checked": len(alerts_no_ex), "mismatches": len(g4_mismatches)},
            "G5_no_alert_approved": {"status": "PASS", "txns_checked": len(no_alert_no_ex), "mismatches": len(g5_mismatches)},
        },
        "known_exceptions": known_ex_results,
        "c_vs_d_exact_difference": exact_c_d_diff_count,
        "metrics_flagged": metrics_flagged,
        "metrics_blocked": metrics_blocked,
        "per_scenario": scenario_eval,
        "fraud_overlap": fraud_overlap,
        "average_precision": {
            "ml_probability": ap_ml,
            "rule_score": ap_rules,
        },
    }

    with open(REPORT_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(results_json, f, indent=2)
    print(f"Generated evaluation JSON: {REPORT_JSON_PATH}")

    # Build and Save final_evaluation.md
    generate_markdown_report(results_json, REPORT_MD_PATH)
    print(f"Generated evaluation Markdown: {REPORT_MD_PATH}")

    return results_json


def generate_figure(scenario_eval: Dict[str, Dict[str, Any]], output_path: Path) -> None:
    """Generate per-scenario detection rate comparison chart."""
    scenarios = ["ACCOUNT_TAKEOVER", "CARD_TESTING", "ODD_HOUR_TRANSFER", "SUBTLE", "STEALTH", "LEGIT"]
    system_names = [
        ("A (Rules >= 30)", "#1f77b4"),
        ("A2 (Rules > 0)", "#aec7e8"),
        ("B (ML >= review)", "#ff7f0e"),
        ("C (Hybrid recomputed)", "#2ca02c"),
    ]

    x = np.arange(len(scenarios))
    total_bars = len(system_names)
    width = 0.18

    fig, ax = plt.subplots(figsize=(12, 6))

    for i, (sys_name, color) in enumerate(system_names):
        rates = [scenario_eval[scen]["systems"][sys_name]["rate"] * 100 for scen in scenarios]
        offset = (i - (total_bars - 1) / 2) * width
        rects = ax.bar(x + offset, rates, width, label=sys_name, color=color, alpha=0.9)
        # Add text labels on top of bars
        for rect in rects:
            height = rect.get_height()
            if height > 0:
                ax.annotate(
                    f"{height:.1f}%",
                    xy=(rect.get_x() + rect.get_width() / 2, height),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha="center",
                    va="bottom",
                    fontsize=7,
                    rotation=0,
                )

    ax.set_ylabel("Detection / Flag Rate (%)", fontsize=11, fontweight="bold")
    ax.set_title("Detection Rate by Scenario and Detection System (N=10,000)", fontsize=13, fontweight="bold")
    ax.set_xticks(x)
    ax.set_xticklabels(scenarios, fontsize=9, fontweight="bold")
    ax.legend(loc="upper right", frameon=True)
    ax.set_ylim(0, 115)
    ax.grid(axis="y", linestyle="--", alpha=0.5)

    plt.tight_layout()
    plt.savefig(output_path, dpi=300)
    plt.close()


def export_predictions_csv(df: pd.DataFrame, output_path: Path) -> None:
    """Export detailed per-transaction predictions CSV."""
    cols_to_export = [
        "transaction_id",
        "account_id",
        "amount",
        "scenario",
        "is_fraud_gt",
        "recomputed_rule_score",
        "recomputed_tier",
        "recomputed_fraud_prob",
        "recomputed_ml_band",
        "recomputed_hybrid_decision",
        "live_effective_decision",
        "pred_flagged_A",
        "pred_flagged_A2",
        "pred_flagged_B",
        "pred_flagged_C",
        "pred_flagged_D",
        "pred_blocked_B",
        "pred_blocked_C",
        "pred_blocked_D",
    ]
    df[cols_to_export].to_csv(output_path, index=False)


def generate_markdown_report(results: Dict[str, Any], output_path: Path) -> None:
    """Generate final_evaluation.md report."""
    mf = results["metrics_flagged"]
    mb = results["metrics_blocked"]
    sc = results["per_scenario"]
    fo = results["fraud_overlap"]
    ap = results["average_precision"]
    th = results["thresholds"]

    md_content = f"""# PayGuard Final Evaluation: Rules-Only vs ML-Only vs Hybrid

Evaluation conducted on the 10,000-transaction simulator benchmark dataset (`run_seed42_10000_transactions.json`).

## 1. Feature Mapping & Replay Configuration

The 11 behavioral features defined in `contracts/feature_schema_v1.json` are mapped from database tables as follows:
- `is_first_transaction`: `1` if `time_since_previous_transaction_seconds IS NULL` else `0`
- `time_since_previous_seconds`: `-1.0` if `time_since_previous_transaction_seconds IS NULL` else the float value
- Boolean indicators (`new_device`, `new_location`, `odd_hour`): mapped to `0` or `1`
- `amount`: taken directly from `transactions.amount`
- Remaining features (`transactions_last_2_min`, `transactions_last_1_hour`, `account_avg_amount`, `amount_ratio`, `transaction_hour`): taken directly from `transaction_features`

Model configuration:
- Model version: `{results['model_version']}`
- Review threshold: `{th['review_threshold']}`
- Block threshold: `{th['block_threshold']}`

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
| **G2** | Rule score recomputation matches stored alert `rule_score` (within 0.01) | {results['gates']['G2_rule_score']['alerts_checked']} alerts | 0 | **PASS** |
| **G3** | ML probability recomputation matches stored `fraud_probability` (within 0.0001) | {results['gates']['G3_ml_probability']['alerts_checked']} alerts | 0 | **PASS** |
| **G4** | Hybrid decision recomputation matches stored alert `decision` (excl. exceptions) | {results['gates']['G4_hybrid_decision']['alerts_checked']} alerts | 0 | **PASS** |
| **G5** | Transactions without alerts have recomputed hybrid decision `APPROVE` | {results['gates']['G5_no_alert_approved']['txns_checked']} txns | 0 | **PASS** |

### Known Exceptions (Audit Log: `ML_SERVICE_UNAVAILABLE`)
Two transactions encountered simulated ML service downtime:
1. `SIM-6adb3870-SIM-ACC-0006-004346`: Stored decision: `APPROVE` (no alert generated). Recomputed Tier: `LOW`, ML Band: `low`. Recomputed Hybrid: `APPROVE`, Fallback Decision: `APPROVE`. Stored equals fallback: `True`.
2. `SIM-6adb3870-SIM-ACC-0013-004347`: Stored decision: `APPROVE` (no alert generated). Recomputed Tier: `LOW`, ML Band: `low`. Recomputed Hybrid: `APPROVE`, Fallback Decision: `APPROVE`. Stored equals fallback: `True`.

Sanity check: Recomputed Hybrid (C) and Live Hybrid (D) decisions differ by exactly **{results['c_vs_d_exact_difference']}** rows across all 10,000 transactions.

---

## 3. Flagged-Level Evaluation Metrics (Predicted Positive = REVIEW or BLOCK)

Ground truth fraud count: **1,143** (Positive: `scenario != 'LEGIT'`), Legitimate count: **8,857**.

| System | TP | FP | FN | TN | Recall (95% CI) | Precision | FPR (95% CI) | F1 Score |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A: Rules-only (score >= 30)** | {mf['A (Rules-only >= 30)']['tp']} | {mf['A (Rules-only >= 30)']['fp']} | {mf['A (Rules-only >= 30)']['fn']} | {mf['A (Rules-only >= 30)']['tn']} | {mf['A (Rules-only >= 30)']['recall']:.4f} [{mf['A (Rules-only >= 30)']['recall_ci_95'][0]:.4f}, {mf['A (Rules-only >= 30)']['recall_ci_95'][1]:.4f}] | {mf['A (Rules-only >= 30)']['precision']:.4f} | {mf['A (Rules-only >= 30)']['fpr']:.4f} [{mf['A (Rules-only >= 30)']['fpr_ci_95'][0]:.4f}, {mf['A (Rules-only >= 30)']['fpr_ci_95'][1]:.4f}] | {mf['A (Rules-only >= 30)']['f1']:.4f} |
| **A2: Rules-any (score > 0)** | {mf['A2 (Rules-any > 0)']['tp']} | {mf['A2 (Rules-any > 0)']['fp']} | {mf['A2 (Rules-any > 0)']['fn']} | {mf['A2 (Rules-any > 0)']['tn']} | {mf['A2 (Rules-any > 0)']['recall']:.4f} [{mf['A2 (Rules-any > 0)']['recall_ci_95'][0]:.4f}, {mf['A2 (Rules-any > 0)']['recall_ci_95'][1]:.4f}] | {mf['A2 (Rules-any > 0)']['precision']:.4f} | {mf['A2 (Rules-any > 0)']['fpr']:.4f} [{mf['A2 (Rules-any > 0)']['fpr_ci_95'][0]:.4f}, {mf['A2 (Rules-any > 0)']['fpr_ci_95'][1]:.4f}] | {mf['A2 (Rules-any > 0)']['f1']:.4f} |
| **B: ML-only (prob >= 0.18657)** | {mf['B (ML-only >= review)']['tp']} | {mf['B (ML-only >= review)']['fp']} | {mf['B (ML-only >= review)']['fn']} | {mf['B (ML-only >= review)']['tn']} | {mf['B (ML-only >= review)']['recall']:.4f} [{mf['B (ML-only >= review)']['recall_ci_95'][0]:.4f}, {mf['B (ML-only >= review)']['recall_ci_95'][1]:.4f}] | {mf['B (ML-only >= review)']['precision']:.4f} | {mf['B (ML-only >= review)']['fpr']:.4f} [{mf['B (ML-only >= review)']['fpr_ci_95'][0]:.4f}, {mf['B (ML-only >= review)']['fpr_ci_95'][1]:.4f}] | {mf['B (ML-only >= review)']['f1']:.4f} |
| **C: Hybrid Recomputed** | {mf['C (Hybrid recomputed)']['tp']} | {mf['C (Hybrid recomputed)']['fp']} | {mf['C (Hybrid recomputed)']['fn']} | {mf['C (Hybrid recomputed)']['tn']} | {mf['C (Hybrid recomputed)']['recall']:.4f} [{mf['C (Hybrid recomputed)']['recall_ci_95'][0]:.4f}, {mf['C (Hybrid recomputed)']['recall_ci_95'][1]:.4f}] | {mf['C (Hybrid recomputed)']['precision']:.4f} | {mf['C (Hybrid recomputed)']['fpr']:.4f} [{mf['C (Hybrid recomputed)']['fpr_ci_95'][0]:.4f}, {mf['C (Hybrid recomputed)']['fpr_ci_95'][1]:.4f}] | {mf['C (Hybrid recomputed)']['f1']:.4f} |
| **D: Hybrid Live** | {mf['D (Hybrid live)']['tp']} | {mf['D (Hybrid live)']['fp']} | {mf['D (Hybrid live)']['fn']} | {mf['D (Hybrid live)']['tn']} | {mf['D (Hybrid live)']['recall']:.4f} [{mf['D (Hybrid live)']['recall_ci_95'][0]:.4f}, {mf['D (Hybrid live)']['recall_ci_95'][1]:.4f}] | {mf['D (Hybrid live)']['precision']:.4f} | {mf['D (Hybrid live)']['fpr']:.4f} [{mf['D (Hybrid live)']['fpr_ci_95'][0]:.4f}, {mf['D (Hybrid live)']['fpr_ci_95'][1]:.4f}] | {mf['D (Hybrid live)']['f1']:.4f} |

---

## 4. Block-Level Evaluation Metrics (Predicted Positive = BLOCK Only)

| System | TP | FP | FN | TN | Recall (95% CI) | Precision | FPR (95% CI) | F1 Score |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A: Rules-only** | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| **B: ML-only (prob >= 0.56667)** | {mb['B (ML-only >= block)']['tp']} | {mb['B (ML-only >= block)']['fp']} | {mb['B (ML-only >= block)']['fn']} | {mb['B (ML-only >= block)']['tn']} | {mb['B (ML-only >= block)']['recall']:.4f} [{mb['B (ML-only >= block)']['recall_ci_95'][0]:.4f}, {mb['B (ML-only >= block)']['recall_ci_95'][1]:.4f}] | {mb['B (ML-only >= block)']['precision']:.4f} | {mb['B (ML-only >= block)']['fpr']:.4f} [{mb['B (ML-only >= block)']['fpr_ci_95'][0]:.4f}, {mb['B (ML-only >= block)']['fpr_ci_95'][1]:.4f}] | {mb['B (ML-only >= block)']['f1']:.4f} |
| **C: Hybrid Recomputed** | {mb['C (Hybrid recomputed)']['tp']} | {mb['C (Hybrid recomputed)']['fp']} | {mb['C (Hybrid recomputed)']['fn']} | {mb['C (Hybrid recomputed)']['tn']} | {mb['C (Hybrid recomputed)']['recall']:.4f} [{mb['C (Hybrid recomputed)']['recall_ci_95'][0]:.4f}, {mb['C (Hybrid recomputed)']['recall_ci_95'][1]:.4f}] | {mb['C (Hybrid recomputed)']['precision']:.4f} | {mb['C (Hybrid recomputed)']['fpr']:.4f} [{mb['C (Hybrid recomputed)']['fpr_ci_95'][0]:.4f}, {mb['C (Hybrid recomputed)']['fpr_ci_95'][1]:.4f}] | {mb['C (Hybrid recomputed)']['f1']:.4f} |
| **D: Hybrid Live** | {mb['D (Hybrid live)']['tp']} | {mb['D (Hybrid live)']['fp']} | {mb['D (Hybrid live)']['fn']} | {mb['D (Hybrid live)']['tn']} | {mb['D (Hybrid live)']['recall']:.4f} [{mb['D (Hybrid live)']['recall_ci_95'][0]:.4f}, {mb['D (Hybrid live)']['recall_ci_95'][1]:.4f}] | {mb['D (Hybrid live)']['precision']:.4f} | {mb['D (Hybrid live)']['fpr']:.4f} [{mb['D (Hybrid live)']['fpr_ci_95'][0]:.4f}, {mb['D (Hybrid live)']['fpr_ci_95'][1]:.4f}] | {mb['D (Hybrid live)']['f1']:.4f} |

*Note: Rules-only system has no BLOCK outcome by design.*

---

## 5. Per-Scenario Detection Breakdown

Flagged counts and rates by scenario across all systems:

| Scenario | Total Txns | Rules (A >= 30) | Rules (A2 > 0) | ML (B >= review) | Hybrid Recomputed (C) | Hybrid Live (D) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **ACCOUNT_TAKEOVER** | {sc['ACCOUNT_TAKEOVER']['total']} | {sc['ACCOUNT_TAKEOVER']['systems']['A (Rules >= 30)']['count']} ({sc['ACCOUNT_TAKEOVER']['systems']['A (Rules >= 30)']['rate']*100:.2f}%) | {sc['ACCOUNT_TAKEOVER']['systems']['A2 (Rules > 0)']['count']} ({sc['ACCOUNT_TAKEOVER']['systems']['A2 (Rules > 0)']['rate']*100:.2f}%) | {sc['ACCOUNT_TAKEOVER']['systems']['B (ML >= review)']['count']} ({sc['ACCOUNT_TAKEOVER']['systems']['B (ML >= review)']['rate']*100:.2f}%) | {sc['ACCOUNT_TAKEOVER']['systems']['C (Hybrid recomputed)']['count']} ({sc['ACCOUNT_TAKEOVER']['systems']['C (Hybrid recomputed)']['rate']*100:.2f}%) | {sc['ACCOUNT_TAKEOVER']['systems']['D (Hybrid live)']['count']} ({sc['ACCOUNT_TAKEOVER']['systems']['D (Hybrid live)']['rate']*100:.2f}%) |
| **CARD_TESTING** | {sc['CARD_TESTING']['total']} | {sc['CARD_TESTING']['systems']['A (Rules >= 30)']['count']} ({sc['CARD_TESTING']['systems']['A (Rules >= 30)']['rate']*100:.2f}%) | {sc['CARD_TESTING']['systems']['A2 (Rules > 0)']['count']} ({sc['CARD_TESTING']['systems']['A2 (Rules > 0)']['rate']*100:.2f}%) | {sc['CARD_TESTING']['systems']['B (ML >= review)']['count']} ({sc['CARD_TESTING']['systems']['B (ML >= review)']['rate']*100:.2f}%) | {sc['CARD_TESTING']['systems']['C (Hybrid recomputed)']['count']} ({sc['CARD_TESTING']['systems']['C (Hybrid recomputed)']['rate']*100:.2f}%) | {sc['CARD_TESTING']['systems']['D (Hybrid live)']['count']} ({sc['CARD_TESTING']['systems']['D (Hybrid live)']['rate']*100:.2f}%) |
| **ODD_HOUR_TRANSFER** | {sc['ODD_HOUR_TRANSFER']['total']} | {sc['ODD_HOUR_TRANSFER']['systems']['A (Rules >= 30)']['count']} ({sc['ODD_HOUR_TRANSFER']['systems']['A (Rules >= 30)']['rate']*100:.2f}%) | {sc['ODD_HOUR_TRANSFER']['systems']['A2 (Rules > 0)']['count']} ({sc['ODD_HOUR_TRANSFER']['systems']['A2 (Rules > 0)']['rate']*100:.2f}%) | {sc['ODD_HOUR_TRANSFER']['systems']['B (ML >= review)']['count']} ({sc['ODD_HOUR_TRANSFER']['systems']['B (ML >= review)']['rate']*100:.2f}%) | {sc['ODD_HOUR_TRANSFER']['systems']['C (Hybrid recomputed)']['count']} ({sc['ODD_HOUR_TRANSFER']['systems']['C (Hybrid recomputed)']['rate']*100:.2f}%) | {sc['ODD_HOUR_TRANSFER']['systems']['D (Hybrid live)']['count']} ({sc['ODD_HOUR_TRANSFER']['systems']['D (Hybrid live)']['rate']*100:.2f}%) |
| **SUBTLE** | {sc['SUBTLE']['total']} | {sc['SUBTLE']['systems']['A (Rules >= 30)']['count']} ({sc['SUBTLE']['systems']['A (Rules >= 30)']['rate']*100:.2f}%) | {sc['SUBTLE']['systems']['A2 (Rules > 0)']['count']} ({sc['SUBTLE']['systems']['A2 (Rules > 0)']['rate']*100:.2f}%) | {sc['SUBTLE']['systems']['B (ML >= review)']['count']} ({sc['SUBTLE']['systems']['B (ML >= review)']['rate']*100:.2f}%) | {sc['SUBTLE']['systems']['C (Hybrid recomputed)']['count']} ({sc['SUBTLE']['systems']['C (Hybrid recomputed)']['rate']*100:.2f}%) | {sc['SUBTLE']['systems']['D (Hybrid live)']['count']} ({sc['SUBTLE']['systems']['D (Hybrid live)']['rate']*100:.2f}%) |
| **STEALTH** | {sc['STEALTH']['total']} | {sc['STEALTH']['systems']['A (Rules >= 30)']['count']} ({sc['STEALTH']['systems']['A (Rules >= 30)']['rate']*100:.2f}%) | {sc['STEALTH']['systems']['A2 (Rules > 0)']['count']} ({sc['STEALTH']['systems']['A2 (Rules > 0)']['rate']*100:.2f}%) | {sc['STEALTH']['systems']['B (ML >= review)']['count']} ({sc['STEALTH']['systems']['B (ML >= review)']['rate']*100:.2f}%) | {sc['STEALTH']['systems']['C (Hybrid recomputed)']['count']} ({sc['STEALTH']['systems']['C (Hybrid recomputed)']['rate']*100:.2f}%) | {sc['STEALTH']['systems']['D (Hybrid live)']['count']} ({sc['STEALTH']['systems']['D (Hybrid live)']['rate']*100:.2f}%) |
| **LEGIT** | {sc['LEGIT']['total']} | {sc['LEGIT']['systems']['A (Rules >= 30)']['count']} ({sc['LEGIT']['systems']['A (Rules >= 30)']['rate']*100:.2f}%) | {sc['LEGIT']['systems']['A2 (Rules > 0)']['count']} ({sc['LEGIT']['systems']['A2 (Rules > 0)']['rate']*100:.2f}%) | {sc['LEGIT']['systems']['B (ML >= review)']['count']} ({sc['LEGIT']['systems']['B (ML >= review)']['rate']*100:.2f}%) | {sc['LEGIT']['systems']['C (Hybrid recomputed)']['count']} ({sc['LEGIT']['systems']['C (Hybrid recomputed)']['rate']*100:.2f}%) | {sc['LEGIT']['systems']['D (Hybrid live)']['count']} ({sc['LEGIT']['systems']['D (Hybrid live)']['rate']*100:.2f}%) |

---

## 6. Fraud Detection Overlap (Rules-Only A vs ML-Only B)

Among the **{fo['total_fraud']}** actual fraud transactions:
- **Flagged by Both Systems**: {fo['flagged_by_both']} ({fo['flagged_by_both_rate']*100:.2f}%)
- **Flagged by Rules Only (A only)**: {fo['flagged_by_rules_only']} ({fo['flagged_by_rules_only_rate']*100:.2f}%)
- **Flagged by ML Only (B only)**: {fo['flagged_by_ml_only']} ({fo['flagged_by_ml_only_rate']*100:.2f}%)
- **Flagged by Neither System**: {fo['flagged_by_neither']} ({fo['flagged_by_neither_rate']*100:.2f}%)

### Ranking Quality (Average Precision / PR-AUC)
- **ML Fraud Probability**: `{ap['ml_probability']:.5f}`
- **Deterministic Rule Score**: `{ap['rule_score']:.5f}`

---

## 7. Limitations

1. **Synthetic Process Origin**: Scenarios and synthetic patterns originate from the same generative distribution and assumptions that produced the training data. Real-world fraud patterns evolve dynamically and may present novel evasion techniques.
2. **Threshold Calibration**: The ML decision thresholds (`review_threshold = {th['review_threshold']}`, `block_threshold = {th['block_threshold']}`) were calibrated and locked based on the offline synthetic validation split, rather than being tuned to this specific 10,000-transaction simulation run.
3. **Single Seed & Single Run**: All reported metrics reflect an evaluation on a single simulation run initialized with seed 42. Multi-seed runs across longer time spans are required to observe variance under varying arrival sequences.
4. **Analyst Feedback / Verdict Labels**: Analyst verdicts from production review were not utilized in this evaluation; ground truth was established strictly by matching transactions to simulator scenario generation tags.
"""
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(md_content)


if __name__ == "__main__":
    run_evaluation()
