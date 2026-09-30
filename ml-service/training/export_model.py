"""Export and package the PayGuard production model artifacts and metadata.

Validates that model.joblib and explainer.joblib are in place, compiles metadata.json
containing all model hyperparameters, contract versions, thresholds, and validation metrics.
Does NOT touch the TEST split.
"""

from datetime import datetime, timezone
import json
from pathlib import Path
from typing import Any, Dict

import joblib

ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ML_SERVICE_ROOT.parent
CONTRACT_SCHEMA_PATH = REPO_ROOT / "contracts" / "feature_schema_v1.json"
MODEL_STORE_DIR = ML_SERVICE_ROOT / "model_store" / "xgboost_v1"
REPORT_DIR = ML_SERVICE_ROOT / "reports"

TRAIN_METRICS_PATH = REPORT_DIR / "train_metrics.json"
THRESHOLDS_PATH = REPORT_DIR / "production_thresholds.json"
SYNTHETIC_SUMMARY_PATH = REPORT_DIR / "synthetic_summary.json"

MODEL_FILE = MODEL_STORE_DIR / "model.joblib"
EXPLAINER_FILE = MODEL_STORE_DIR / "explainer.joblib"
METADATA_FILE = MODEL_STORE_DIR / "metadata.json"

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


def export_model_artifacts() -> Dict[str, Any]:
    """Compile and write model_store/xgboost_v1/metadata.json without touching the test split."""
    MODEL_STORE_DIR.mkdir(parents=True, exist_ok=True)

    if not MODEL_FILE.exists():
        raise FileNotFoundError(f"Calibrated model not found at {MODEL_FILE}. Run train_production_model first.")
    if not EXPLAINER_FILE.exists():
        raise FileNotFoundError(f"Explainer not found at {EXPLAINER_FILE}. Run explain_model first.")
    if not THRESHOLDS_PATH.exists():
        raise FileNotFoundError(f"Thresholds not found at {THRESHOLDS_PATH}. Run derive_thresholds first.")

    # 1. Read schema version from contract
    with open(CONTRACT_SCHEMA_PATH, "r", encoding="utf-8") as f:
        schema_data = json.load(f)
    schema_version = schema_data.get("schema_version", "1")

    # 2. Read thresholds and validation metrics
    with open(THRESHOLDS_PATH, "r", encoding="utf-8") as f:
        thresholds_data = json.load(f)

    block_thresh = thresholds_data["block_threshold"]
    review_thresh = thresholds_data["review_threshold"]

    # 3. Read train metrics
    with open(TRAIN_METRICS_PATH, "r", encoding="utf-8") as f:
        train_metrics = json.load(f)

    # 4. Read split row counts from synthetic_summary.json (no loading of test data)
    with open(SYNTHETIC_SUMMARY_PATH, "r", encoding="utf-8") as f:
        synthetic_summary = json.load(f)

    splits_info = synthetic_summary.get("splits", {})
    training_row_counts = {
        "train": {
            "total_rows": splits_info.get("train", {}).get("total", train_metrics["train_rows"]),
            "fraud_rows": splits_info.get("train", {}).get("fraud", train_metrics["train_fraud"]),
        },
        "val": {
            "total_rows": splits_info.get("val", {}).get("total", train_metrics["val_rows"]),
            "fraud_rows": splits_info.get("val", {}).get("fraud", train_metrics["val_fraud"]),
        },
        "test": {
            "total_rows": splits_info.get("test", {}).get("total"),
            "fraud_rows": splits_info.get("test", {}).get("fraud"),
        },
    }

    val_metrics = {
        "pr_auc": train_metrics["val_pr_auc"],
        "roc_auc": train_metrics["val_roc_auc"],
        "brier_score": train_metrics["val_brier"],
        "block_threshold_metrics": {
            "precision": thresholds_data["block_metrics"]["precision"],
            "recall": thresholds_data["block_metrics"]["recall"],
            "f1": thresholds_data["block_metrics"]["f1"],
            "false_positive_rate": thresholds_data["block_metrics"]["false_positive_rate"],
        },
        "review_threshold_metrics": {
            "precision": thresholds_data["review_metrics"]["precision"],
            "recall": thresholds_data["review_metrics"]["recall"],
            "f1": thresholds_data["review_metrics"]["f1"],
            "false_positive_rate": thresholds_data["review_metrics"]["false_positive_rate"],
        },
    }

    metadata = {
        "model_version": "xgboost-v1",
        "trained_at": train_metrics.get("trained_at", datetime.now(timezone.utc).isoformat()),
        "feature_order": CONTRACT_FEATURES,
        "feature_schema_version": schema_version,
        "block_threshold": block_thresh,
        "review_threshold": review_thresh,
        "val_metrics": val_metrics,
        "cv_pr_auc_mean": train_metrics["cv_pr_auc_mean"],
        "cv_pr_auc_std": train_metrics["cv_pr_auc_std"],
        "training_row_counts": training_row_counts,
        "random_seed": 42,
        "scale_pos_weight_used": train_metrics["scale_pos_weight"],
        "calibration_method_used": train_metrics["calibration_method_used"],
    }

    print(f"Writing metadata to {METADATA_FILE}...")
    with open(METADATA_FILE, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    print("Model export completed successfully.")
    return metadata


if __name__ == "__main__":
    export_model_artifacts()
