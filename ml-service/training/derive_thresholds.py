"""Derive operational probability thresholds for PayGuard ML service.

Evaluates calibrated probabilities on the VAL split only to determine
BLOCK_THRESHOLD (precision >= 0.90) and REVIEW_THRESHOLD (recall >= 0.85).
Does NOT touch the TEST split.
"""

import json
from pathlib import Path
from typing import Any, Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import precision_recall_curve

ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
DATA_PATH = ML_SERVICE_ROOT / "data" / "synthetic" / "synthetic_v1.csv"
MODEL_PATH = ML_SERVICE_ROOT / "model_store" / "xgboost_v1" / "model.joblib"
REPORT_PATH = ML_SERVICE_ROOT / "reports" / "production_thresholds.json"

CONTRACT_FEATURES = [
    "amount",
    "transactions_last_2_min",
    "transactions_last_1_hour",
    "account_avg_amount",
    "amount_ratio",
    "time_since_previous_seconds",
    "is_first_transaction",
    "new_device",
    "new_location",
    "transaction_hour",
    "odd_hour",
]


def compute_metrics_at_threshold(y_true: np.ndarray, y_prob: np.ndarray, threshold: float) -> Dict[str, float]:
    """Compute precision, recall, F1, and FPR at a specific threshold."""
    y_pred = (y_prob >= threshold).astype(int)

    tp = int(np.sum((y_pred == 1) & (y_true == 1)))
    fp = int(np.sum((y_pred == 1) & (y_true == 0)))
    fn = int(np.sum((y_pred == 0) & (y_true == 1)))
    tn = int(np.sum((y_pred == 0) & (y_true == 0)))

    prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    rec = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0.0
    fpr = fp / (fp + tn) if (fp + tn) > 0 else 0.0

    return {
        "threshold": round(float(threshold), 5),
        "precision": round(float(prec), 5),
        "recall": round(float(rec), 5),
        "f1": round(float(f1), 5),
        "false_positive_rate": round(float(fpr), 5),
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "tn": tn,
    }


def derive_thresholds() -> Dict[str, Any]:
    """Derive BLOCK and REVIEW thresholds using calibrated predictions on VAL."""
    print("Loading VAL split and calibrated model...")
    df = pd.read_csv(DATA_PATH)
    val_mask = df["split"] == "val"
    X_val = df.loc[val_mask, CONTRACT_FEATURES]
    y_val = df.loc[val_mask, "is_fraud"].to_numpy()

    calibrated_model = joblib.load(MODEL_PATH)
    val_probs = calibrated_model.predict_proba(X_val)[:, 1]

    precision_curve, recall_curve, threshold_points = precision_recall_curve(y_val, val_probs)
    flags: List[str] = []

    # 1. Determine BLOCK_THRESHOLD: lowest threshold with precision >= 0.90
    prec_90_indices = np.where(precision_curve[:-1] >= 0.90)[0]
    if len(prec_90_indices) > 0:
        block_idx = prec_90_indices[0]
        block_threshold = float(threshold_points[block_idx])
        block_method = "Lowest probability threshold with precision >= 0.90 on VAL"
    else:
        best_prec_idx = int(np.argmax(precision_curve[:-1]))
        block_threshold = float(threshold_points[best_prec_idx])
        msg = f"No threshold reached precision 0.90; maximized precision used (prec={precision_curve[best_prec_idx]:.4f})"
        flags.append(msg)
        block_method = msg
        print(f"FLAG: {msg}")

    # 2. Determine REVIEW_THRESHOLD: boundary threshold such that recall >= 0.85
    rec_85_indices = np.where(recall_curve[:-1] >= 0.85)[0]
    if len(rec_85_indices) > 0:
        # recall is non-increasing with threshold; highest index gives the cutoff boundary for recall >= 0.85
        rev_idx = rec_85_indices[-1]
        raw_review_threshold = float(threshold_points[rev_idx])
        review_method = "Boundary probability threshold with recall >= 0.85 on VAL"
    else:
        # If no point has recall >= 0.85, take lowest available threshold
        raw_review_threshold = float(threshold_points[0])
        msg = "No threshold reached recall 0.85; lowest available threshold used"
        flags.append(msg)
        review_method = msg

    # REVIEW_THRESHOLD must be strictly < BLOCK_THRESHOLD
    if raw_review_threshold >= block_threshold:
        review_threshold = block_threshold * 0.90
        msg = f"Computed REVIEW_THRESHOLD ({raw_review_threshold:.5f}) >= BLOCK_THRESHOLD ({block_threshold:.5f}); set to BLOCK_THRESHOLD * 0.90"
        flags.append(msg)
        review_method += f" (adjusted: {msg})"
        print(f"FLAG: {msg}")
    else:
        review_threshold = raw_review_threshold

    # 3. Compute metrics at both thresholds
    block_metrics = compute_metrics_at_threshold(y_val, val_probs, block_threshold)
    block_metrics["method"] = block_method

    review_metrics = compute_metrics_at_threshold(y_val, val_probs, review_threshold)
    review_metrics["method"] = review_method

    # 4. Count transactions in the three probability bands
    # Band 1: < REVIEW_THRESHOLD (APPROVE)
    # Band 2: REVIEW_THRESHOLD to BLOCK_THRESHOLD (REVIEW)
    # Band 3: >= BLOCK_THRESHOLD (BLOCK)
    approve_mask = val_probs < review_threshold
    review_mask = (val_probs >= review_threshold) & (val_probs < block_threshold)
    block_mask = val_probs >= block_threshold

    n_approve = int(np.sum(approve_mask))
    n_review = int(np.sum(review_mask))
    n_block = int(np.sum(block_mask))

    band_counts = {
        "approve_below_review": n_approve,
        "review_between_thresholds": n_review,
        "block_above_or_equal_block": n_block,
        "total_val_transactions": len(val_probs),
    }

    result = {
        "block_threshold": round(block_threshold, 5),
        "review_threshold": round(review_threshold, 5),
        "block_metrics": block_metrics,
        "review_metrics": review_metrics,
        "band_counts": band_counts,
        "flags": flags,
    }

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(REPORT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)

    print("\n" + "=" * 60)
    print("PRODUCTION THRESHOLDS DERIVED (VAL Split)")
    print("=" * 60)
    print(f"BLOCK_THRESHOLD:  {block_threshold:.5f}")
    print(f"  Precision: {block_metrics['precision']:.4f}, Recall: {block_metrics['recall']:.4f}, F1: {block_metrics['f1']:.4f}, FPR: {block_metrics['false_positive_rate']:.4f}")
    print(f"REVIEW_THRESHOLD: {review_threshold:.5f}")
    print(f"  Precision: {review_metrics['precision']:.4f}, Recall: {review_metrics['recall']:.4f}, F1: {review_metrics['f1']:.4f}, FPR: {review_metrics['false_positive_rate']:.4f}")
    print("\nProbability Band Transaction Counts (VAL):")
    print(f"  APPROVE (< {review_threshold:.5f}):              {n_approve} ({n_approve/len(val_probs)*100:.2f}%)")
    print(f"  REVIEW  ([{review_threshold:.5f}, {block_threshold:.5f})): {n_review} ({n_review/len(val_probs)*100:.2f}%)")
    print(f"  BLOCK   (>= {block_threshold:.5f}):             {n_block} ({n_block/len(val_probs)*100:.2f}%)")
    print(f"\nSaved thresholds report to {REPORT_PATH}.")

    return result


if __name__ == "__main__":
    derive_thresholds()
