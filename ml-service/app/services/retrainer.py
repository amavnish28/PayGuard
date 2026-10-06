"""Retraining service for PayGuard ML service.

Implements candidate model training with synthetic augmentation,
isotonic calibration, threshold derivation, read-only test evaluation,
and safe model export.
"""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd
import shap
from sklearn.calibration import CalibratedClassifierCV
from sklearn.frozen import FrozenEstimator
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    confusion_matrix,
    precision_recall_curve,
    roc_auc_score,
)
from sklearn.model_selection import train_test_split
from xgboost import XGBClassifier

from app.schemas.predict import PredictionRequest

logger = logging.getLogger(__name__)

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


def resolve_paths() -> Tuple[Path, Path, Path]:
    """Resolve paths to synthetic dataset, contract schema, and model store."""
    service_root = Path(__file__).resolve().parents[2]
    repo_root = service_root.parent

    # 1. Dataset path
    candidate_data_paths = [
        service_root / "data" / "synthetic" / "synthetic_v1.csv",
        repo_root / "ml-service" / "data" / "synthetic" / "synthetic_v1.csv",
    ]
    data_path = None
    for p in candidate_data_paths:
        if p.exists():
            data_path = p
            break
    if data_path is None:
        raise FileNotFoundError(f"Could not find synthetic_v1.csv in {[str(p) for p in candidate_data_paths]}")

    # 2. Schema path
    candidate_schema_paths = [
        repo_root / "contracts" / "feature_schema_v1.json",
        service_root.parent / "contracts" / "feature_schema_v1.json",
    ]
    schema_path = None
    for p in candidate_schema_paths:
        if p.exists():
            schema_path = p
            break
    if schema_path is None:
        raise FileNotFoundError(f"Could not find feature_schema_v1.json in {[str(p) for p in candidate_schema_paths]}")

    # 3. Model store root
    model_store_dir = service_root / "model_store"
    model_store_dir.mkdir(parents=True, exist_ok=True)

    return data_path, schema_path, model_store_dir


def compute_metrics_at_threshold(y_true: np.ndarray, y_prob: np.ndarray, threshold: float) -> Dict[str, Any]:
    """Compute precision, recall, F1, FPR, and confusion counts at a specific threshold."""
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


def derive_thresholds(y_val: np.ndarray, val_probs: np.ndarray) -> Tuple[float, float, Dict[str, Any], Dict[str, Any]]:
    """Derive BLOCK and REVIEW thresholds using calibrated predictions on validation split."""
    precision_curve, recall_curve, threshold_points = precision_recall_curve(y_val, val_probs)

    # 1. BLOCK_THRESHOLD: lowest threshold with precision >= 0.90
    prec_90_indices = np.where(precision_curve[:-1] >= 0.90)[0]
    if len(prec_90_indices) > 0:
        block_idx = prec_90_indices[0]
        block_threshold = float(threshold_points[block_idx])
        block_method = "Lowest probability threshold with precision >= 0.90 on VAL"
    else:
        best_prec_idx = int(np.argmax(precision_curve[:-1]))
        block_threshold = float(threshold_points[best_prec_idx])
        block_method = f"No threshold reached precision 0.90; maximized precision used (prec={precision_curve[best_prec_idx]:.4f})"

    # 2. REVIEW_THRESHOLD: boundary threshold with recall >= 0.85
    rec_85_indices = np.where(recall_curve[:-1] >= 0.85)[0]
    if len(rec_85_indices) > 0:
        rev_idx = rec_85_indices[-1]
        raw_review_threshold = float(threshold_points[rev_idx])
        review_method = "Boundary probability threshold with recall >= 0.85 on VAL"
    else:
        raw_review_threshold = float(threshold_points[0])
        review_method = "No threshold reached recall 0.85; lowest available threshold used"

    # REVIEW_THRESHOLD must be strictly < BLOCK_THRESHOLD
    if raw_review_threshold >= block_threshold:
        review_threshold = block_threshold * 0.90
        review_method += " (adjusted: review >= block; set to block * 0.90)"
    else:
        review_threshold = raw_review_threshold

    block_metrics = compute_metrics_at_threshold(y_val, val_probs, block_threshold)
    block_metrics["method"] = block_method

    review_metrics = compute_metrics_at_threshold(y_val, val_probs, review_threshold)
    review_metrics["method"] = review_method

    return block_threshold, review_threshold, block_metrics, review_metrics


def evaluate_test_split(calibrated_model: Any, block_threshold: float, review_threshold: float, data_path: Path) -> Dict[str, Any]:
    """Perform read-only evaluation of candidate model on the held-out TEST split."""
    df = pd.read_csv(data_path)
    test_mask = df["split"] == "test"
    X_test = df.loc[test_mask, CONTRACT_FEATURES]
    y_test = df.loc[test_mask, "is_fraud"].to_numpy()

    test_probs = calibrated_model.predict_proba(X_test)[:, 1]
    test_pr_auc = float(average_precision_score(y_test, test_probs))
    test_roc_auc = float(roc_auc_score(y_test, test_probs))

    block_metrics = compute_metrics_at_threshold(y_test, test_probs, block_threshold)
    review_metrics = compute_metrics_at_threshold(y_test, test_probs, review_threshold)

    y_pred_block = (test_probs >= block_threshold).astype(int)
    cm_block = confusion_matrix(y_test, y_pred_block).tolist()

    return {
        "pr_auc": round(test_pr_auc, 5),
        "roc_auc": round(test_roc_auc, 5),
        "block_threshold": round(block_threshold, 5),
        "review_threshold": round(review_threshold, 5),
        "block_metrics": block_metrics,
        "review_metrics": review_metrics,
        "confusion_matrix_at_block": {
            "tn": cm_block[0][0],
            "fp": cm_block[0][1],
            "fn": cm_block[1][0],
            "tp": cm_block[1][1],
            "matrix": cm_block,
        },
        "test_rows": len(X_test),
        "test_fraud_rows": int(np.sum(y_test)),
    }


def validate_training_examples(training_examples: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Validate incoming training examples using Phase 7 dynamic PredictionRequest logic.

    Raises ValueError with descriptive message if any item is malformed.
    """
    if not isinstance(training_examples, list):
        raise ValueError("training_examples must be a list")

    validated_items: List[Dict[str, Any]] = []
    for idx, item in enumerate(training_examples):
        if not isinstance(item, dict):
            raise ValueError(f"training_examples[{idx}] must be an object")

        if "features" not in item:
            raise ValueError(f"training_examples[{idx}] missing 'features' field")
        features_dict = item["features"]
        if not isinstance(features_dict, dict):
            raise ValueError(f"training_examples[{idx}].features must be an object")

        if "label" not in item:
            raise ValueError(f"training_examples[{idx}] missing 'label' field")
        label = item["label"]
        if label not in (0, 1):
            raise ValueError(f"training_examples[{idx}].label must be 0 or 1, got {label}")

        # Reuse existing dynamic PredictionRequest model validation directly
        try:
            req_model = PredictionRequest(**features_dict)
        except Exception as e:
            raise ValueError(f"Feature validation failed at training_examples[{idx}]: {e}")

        # Extract features strictly in contract order
        feature_vals = {name: getattr(req_model, name) for name in CONTRACT_FEATURES}
        validated_items.append({"features": feature_vals, "label": int(label)})

    return validated_items


def run_candidate_retraining(
    model_version_candidate: str,
    training_examples: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """Execute complete retraining pipeline for a candidate model version.

    1. Validate training examples using dynamic PredictionRequest.
    2. Load synthetic train+val augmentation base and tag verdict rows.
    3. Fresh 80/20 train/val split with version-derived seed.
    4. Fit raw XGBoost model.
    5. Calibrate via FrozenEstimator on fresh val split.
    6. Derive thresholds on fresh val split.
    7. Read-only evaluation on original Phase 5d/5e test split.
    8. Export candidate artifacts to model_store/{model_version_candidate}/.
    9. Return metrics, data summary, and model path.
    """
    # 1. Validation
    if not isinstance(model_version_candidate, str) or not model_version_candidate.strip():
        raise ValueError("model_version_candidate must be a non-empty string")
    if "/" in model_version_candidate or "\\" in model_version_candidate or ".." in model_version_candidate:
        raise ValueError("model_version_candidate contains invalid path characters")

    validated_examples = validate_training_examples(training_examples)
    data_path, schema_path, model_store_dir = resolve_paths()

    candidate_dir = model_store_dir / model_version_candidate
    candidate_dir.mkdir(parents=True, exist_ok=True)

    # 2. Load synthetic train+val splits (TEST split is NOT loaded here)
    logger.info("Loading synthetic train and val splits from %s...", data_path)
    df_synthetic = pd.read_csv(data_path)
    train_val_mask = df_synthetic["split"].isin(["train", "val"])
    df_base = df_synthetic.loc[train_val_mask, CONTRACT_FEATURES + ["is_fraud"]].copy()
    df_base["source"] = "synthetic"

    synthetic_count = len(df_base)
    synthetic_fraud_count = int((df_base["is_fraud"] == 1).sum())

    # Format new verdict examples into DataFrame
    verdict_rows = []
    for ex in validated_examples:
        row = dict(ex["features"])
        row["is_fraud"] = ex["label"]
        row["source"] = "verdict"
        verdict_rows.append(row)

    df_verdicts = pd.DataFrame(verdict_rows)
    verdict_count = len(df_verdicts)
    verdict_fraud_count = int((df_verdicts["is_fraud"] == 1).sum()) if verdict_count > 0 else 0
    verdict_legit_count = int((df_verdicts["is_fraud"] == 0).sum()) if verdict_count > 0 else 0

    # Combine datasets
    df_combined = pd.concat([df_base, df_verdicts], ignore_index=True)
    total_count = len(df_combined)
    total_fraud_count = int((df_combined["is_fraud"] == 1).sum())
    total_legit_count = int((df_combined["is_fraud"] == 0).sum())

    logger.info(
        "Retraining dataset assembled: %d synthetic rows (%d fraud) + %d verdict rows (%d fraud, %d legit) = %d total",
        synthetic_count,
        synthetic_fraud_count,
        verdict_count,
        verdict_fraud_count,
        verdict_legit_count,
        total_count,
    )

    # 3. Fresh train/validation split (80/20, stratified, seed derived from candidate version)
    seed = int(hashlib.sha256(model_version_candidate.encode("utf-8")).hexdigest()[:8], 16) % 100000
    train_df, val_df = train_test_split(
        df_combined,
        test_size=0.20,
        stratify=df_combined["is_fraud"],
        random_state=seed,
    )

    # 4. Train raw XGBClassifier with identical hyperparameters
    n_neg = int((train_df["is_fraud"] == 0).sum())
    n_pos = int((train_df["is_fraud"] == 1).sum())
    scale_pos_weight = float(n_neg / n_pos) if n_pos > 0 else 1.0

    logger.info(
        "Fitting raw XGBClassifier (seed=%d, scale_pos_weight=%.4f) on %d train rows...",
        seed,
        scale_pos_weight,
        len(train_df),
    )
    raw_model = XGBClassifier(
        n_estimators=300,
        max_depth=6,
        learning_rate=0.1,
        subsample=0.8,
        colsample_bytree=0.8,
        tree_method="hist",
        eval_metric="aucpr",
        n_jobs=-1,
        random_state=seed,
        scale_pos_weight=scale_pos_weight,
    )
    raw_model.fit(train_df[CONTRACT_FEATURES], train_df["is_fraud"])

    # 5. Calibrate on fresh validation split via FrozenEstimator
    logger.info("Calibrating on %d fresh validation rows via FrozenEstimator...", len(val_df))
    frozen_base = FrozenEstimator(raw_model)
    calibrated_model = CalibratedClassifierCV(
        estimator=frozen_base,
        method="isotonic",
    )
    calibrated_model.fit(val_df[CONTRACT_FEATURES], val_df["is_fraud"])

    # 6. Derive thresholds on fresh validation split
    val_probs = calibrated_model.predict_proba(val_df[CONTRACT_FEATURES])[:, 1]
    y_val = val_df["is_fraud"].to_numpy()

    block_threshold, review_threshold, block_val_metrics, review_val_metrics = derive_thresholds(y_val, val_probs)
    val_pr_auc = float(average_precision_score(y_val, val_probs))
    val_roc_auc = float(roc_auc_score(y_val, val_probs))
    val_brier = float(brier_score_loss(y_val, val_probs))

    val_metrics = {
        "pr_auc": round(val_pr_auc, 5),
        "roc_auc": round(val_roc_auc, 5),
        "brier_score": round(val_brier, 5),
        "block_threshold_metrics": block_val_metrics,
        "review_threshold_metrics": review_val_metrics,
    }

    # 7. Evaluate calibrated candidate ONCE on held-out original TEST split
    logger.info("Evaluating calibrated candidate model ONCE on original held-out TEST split...")
    test_metrics = evaluate_test_split(calibrated_model, block_threshold, review_threshold, data_path)

    # 8. Export artifacts to model_store/{model_version_candidate}/
    logger.info("Computing TreeExplainer for candidate model...")
    explainer = shap.TreeExplainer(raw_model)

    # Read schema version from contract
    with open(schema_path, "r", encoding="utf-8") as f:
        schema_json = json.load(f)
    schema_version = schema_json.get("schema_version", "1")

    metadata = {
        "model_version": model_version_candidate,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "feature_order": CONTRACT_FEATURES,
        "feature_schema_version": schema_version,
        "block_threshold": round(block_threshold, 5),
        "review_threshold": round(review_threshold, 5),
        "val_metrics": val_metrics,
        "test_metrics": test_metrics,
        "training_row_counts": {
            "synthetic_rows": synthetic_count,
            "verdict_rows": verdict_count,
            "total_rows": total_count,
            "train_rows": len(train_df),
            "val_rows": len(val_df),
            "test_rows": test_metrics["test_rows"],
        },
        "random_seed": seed,
        "scale_pos_weight_used": round(scale_pos_weight, 4),
        "calibration_method_used": "CalibratedClassifierCV(estimator=FrozenEstimator(raw_model), method='isotonic')",
    }

    joblib.dump(calibrated_model, candidate_dir / "model.joblib")
    joblib.dump(explainer, candidate_dir / "explainer.joblib")
    joblib.dump(raw_model, candidate_dir / "raw_model.joblib")

    with open(candidate_dir / "metadata.json", "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    logger.info("Candidate model exported to %s", candidate_dir)

    # 9. Format response payload
    combined_metrics = {
        "pr_auc": test_metrics["pr_auc"],
        "roc_auc": test_metrics["roc_auc"],
        "test_pr_auc": test_metrics["pr_auc"],
        "test_roc_auc": test_metrics["roc_auc"],
        "val_pr_auc": round(val_pr_auc, 5),
        "val_roc_auc": round(val_roc_auc, 5),
        "val_brier": round(val_brier, 5),
        "block_threshold": round(block_threshold, 5),
        "review_threshold": round(review_threshold, 5),
        "val_metrics": val_metrics,
        "test_metrics": test_metrics,
    }

    training_data_summary = {
        "synthetic_rows": synthetic_count,
        "verdict_rows": verdict_count,
        "total_rows": total_count,
        "fraud_count": total_fraud_count,
        "legitimate_count": total_legit_count,
    }

    return {
        "model_version_candidate": model_version_candidate,
        "metrics": combined_metrics,
        "training_data_summary": training_data_summary,
        "model_store_path": f"model_store/{model_version_candidate}",
        "block_threshold": round(block_threshold, 5),
        "review_threshold": round(review_threshold, 5),
    }
