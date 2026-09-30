"""Final evaluation of the PayGuard production model on the held-out TEST split.

This script executes exactly once, evaluating model.joblib against the TEST split
at pre-derived BLOCK and REVIEW thresholds. Refuses re-runs unless --force is given.
"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
from typing import Any, Dict

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import (
    average_precision_score,
    confusion_matrix,
    roc_auc_score,
)

ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
DATA_PATH = ML_SERVICE_ROOT / "data" / "synthetic" / "synthetic_v1.csv"
MODEL_PATH = ML_SERVICE_ROOT / "model_store" / "xgboost_v1" / "model.joblib"
METADATA_PATH = ML_SERVICE_ROOT / "model_store" / "xgboost_v1" / "metadata.json"
REPORT_PATH = ML_SERVICE_ROOT / "reports" / "production_test_result.json"

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


def compute_metrics(y_true: np.ndarray, y_prob: np.ndarray, threshold: float) -> Dict[str, Any]:
    """Compute precision, recall, F1, and FPR at a given threshold."""
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


def evaluate_test_split(force: bool = False, report_file: Path = REPORT_PATH) -> Dict[str, Any]:
    """Evaluate calibrated production model on the held-out TEST split."""
    if report_file.exists() and not force:
        raise RuntimeError(
            f"Production test result already exists at {report_file}. "
            "Re-running final test evaluation is prohibited to prevent data snooping. Use --force to override."
        )

    if not MODEL_PATH.exists():
        raise FileNotFoundError(f"Calibrated model not found at {MODEL_PATH}")
    if not METADATA_PATH.exists():
        raise FileNotFoundError(f"Metadata not found at {METADATA_PATH}")

    # Read thresholds from metadata (do not re-derive)
    with open(METADATA_PATH, "r", encoding="utf-8") as f:
        metadata = json.load(f)

    block_threshold = metadata["block_threshold"]
    review_threshold = metadata["review_threshold"]

    print("Loading held-out TEST split...")
    df = pd.read_csv(DATA_PATH)
    test_mask = df["split"] == "test"
    X_test = df.loc[test_mask, CONTRACT_FEATURES]
    y_test = df.loc[test_mask, "is_fraud"].to_numpy()

    print(f"TEST set size: {len(X_test)} (fraud count: {int(np.sum(y_test))})")

    print(f"Loading calibrated model from {MODEL_PATH}...")
    model = joblib.load(MODEL_PATH)
    test_probs = model.predict_proba(X_test)[:, 1]

    # Global discrimination metrics
    test_pr_auc = float(average_precision_score(y_test, test_probs))
    test_roc_auc = float(roc_auc_score(y_test, test_probs))

    # Threshold metrics
    block_metrics = compute_metrics(y_test, test_probs, block_threshold)
    review_metrics = compute_metrics(y_test, test_probs, review_threshold)

    # Confusion matrix at BLOCK_THRESHOLD
    y_pred_block = (test_probs >= block_threshold).astype(int)
    cm_block = confusion_matrix(y_test, y_pred_block).tolist()

    result = {
        "evaluated_at": datetime.now(timezone.utc).isoformat(),
        "model_version": metadata.get("model_version", "xgboost-v1"),
        "test_rows": len(X_test),
        "test_fraud_rows": int(np.sum(y_test)),
        "pr_auc": round(test_pr_auc, 5),
        "roc_auc": round(test_roc_auc, 5),
        "block_threshold": block_threshold,
        "review_threshold": review_threshold,
        "block_metrics": block_metrics,
        "review_metrics": review_metrics,
        "confusion_matrix_at_block": {
            "tn": cm_block[0][0],
            "fp": cm_block[0][1],
            "fn": cm_block[1][0],
            "tp": cm_block[1][1],
            "matrix": cm_block,
        },
    }

    report_file.parent.mkdir(parents=True, exist_ok=True)
    with open(report_file, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)

    print("\n" + "=" * 60)
    print("FINAL TEST EVALUATION RESULTS (Held-Out TEST Split)")
    print("=" * 60)
    print(f"PR-AUC:   {test_pr_auc:.5f}")
    print(f"ROC-AUC:  {test_roc_auc:.5f}")
    print(f"\nBLOCK_THRESHOLD = {block_threshold:.5f}:")
    print(f"  Precision: {block_metrics['precision']:.4f}")
    print(f"  Recall:    {block_metrics['recall']:.4f}")
    print(f"  F1 Score:  {block_metrics['f1']:.4f}")
    print(f"  FPR:       {block_metrics['false_positive_rate']:.4f}")
    print(f"\nREVIEW_THRESHOLD = {review_threshold:.5f}:")
    print(f"  Precision: {review_metrics['precision']:.4f}")
    print(f"  Recall:    {review_metrics['recall']:.4f}")
    print(f"  F1 Score:  {review_metrics['f1']:.4f}")
    print(f"  FPR:       {review_metrics['false_positive_rate']:.4f}")
    print("\nConfusion Matrix at BLOCK_THRESHOLD:")
    print(f"  TN: {cm_block[0][0]:<6} FP: {cm_block[0][1]:<6}")
    print(f"  FN: {cm_block[1][0]:<6} TP: {cm_block[1][1]:<6}")
    print("=" * 60)
    print(f"Saved test evaluation report to {report_file}.")

    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate PayGuard production model on held-out test split.")
    parser.add_argument("--force", action="store_true", help="Force rerun even if production_test_result.json exists.")
    args = parser.parse_args()

    evaluate_test_split(force=args.force)


if __name__ == "__main__":
    main()
