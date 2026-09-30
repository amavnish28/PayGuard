"""Train, calibrate, and evaluate the PayGuard production XGBoost fraud detection model.

Uses TRAIN split for fitting, VAL split for isotonic calibration via FrozenEstimator,
and StratifiedKFold on TRAIN for robustness reference. Does NOT touch the TEST split.
"""

from datetime import datetime, timezone
import json
from pathlib import Path
import time
from typing import Any, Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.frozen import FrozenEstimator
from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score
from sklearn.model_selection import StratifiedKFold
from xgboost import XGBClassifier

ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
DATA_PATH = ML_SERVICE_ROOT / "data" / "synthetic" / "synthetic_v1.csv"
MODEL_STORE_DIR = ML_SERVICE_ROOT / "model_store" / "xgboost_v1"
REPORT_DIR = ML_SERVICE_ROOT / "reports"
TRAIN_METRICS_PATH = REPORT_DIR / "train_metrics.json"

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


def load_train_and_val_data(csv_path: Path) -> Tuple[pd.DataFrame, pd.Series, pd.DataFrame, pd.Series]:
    """Load only TRAIN and VAL splits from dataset. The TEST split is never loaded here."""
    df = pd.read_csv(csv_path)

    train_mask = df["split"] == "train"
    val_mask = df["split"] == "val"

    X_train = df.loc[train_mask, CONTRACT_FEATURES].copy()
    y_train = df.loc[train_mask, "is_fraud"].copy()

    X_val = df.loc[val_mask, CONTRACT_FEATURES].copy()
    y_val = df.loc[val_mask, "is_fraud"].copy()

    return X_train, y_train, X_val, y_val


def compute_reliability_table(y_true: np.ndarray, y_prob: np.ndarray, n_bins: int = 10) -> List[Dict[str, Any]]:
    """Compute a 10-bin calibration table comparing predicted probability vs actual fraud rate."""
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    table = []

    for i in range(n_bins):
        low, high = bins[i], bins[i + 1]
        if i == n_bins - 1:
            mask = (y_prob >= low) & (y_prob <= high)
            bin_label = f"[{low:.1f}, {high:.1f}]"
        else:
            mask = (y_prob >= low) & (y_prob < high)
            bin_label = f"[{low:.1f}, {high:.1f})"

        count = int(np.sum(mask))
        if count > 0:
            mean_pred = float(np.mean(y_prob[mask]))
            actual_rate = float(np.mean(y_true[mask]))
        else:
            mean_pred = 0.0
            actual_rate = 0.0

        table.append({
            "bin": bin_label,
            "count": count,
            "mean_predicted_probability": round(mean_pred, 5),
            "actual_fraud_rate": round(actual_rate, 5),
        })

    return table


def run_training_pipeline() -> Dict[str, Any]:
    """Execute model training, prefit calibration, validation evaluation, and cross-validation."""
    t0 = time.time()
    MODEL_STORE_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)

    print("Loading TRAIN and VAL splits from synthetic dataset...")
    X_train, y_train, X_val, y_val = load_train_and_val_data(DATA_PATH)

    n_neg = int((y_train == 0).sum())
    n_pos = int((y_train == 1).sum())
    scale_pos_weight = float(n_neg / n_pos)

    print(f"TRAIN size: {len(X_train)} (negatives: {n_neg}, positives: {n_pos})")
    print(f"Computed scale_pos_weight: {scale_pos_weight:.4f}")
    print(f"VAL size:   {len(X_val)} (positives: {int(y_val.sum())})")

    # 1. Fit raw XGBoost on TRAIN split
    print("\nFitting raw XGBClassifier on TRAIN split...")
    raw_model = XGBClassifier(
        n_estimators=300,
        max_depth=6,
        learning_rate=0.1,
        subsample=0.8,
        colsample_bytree=0.8,
        tree_method="hist",
        eval_metric="aucpr",
        n_jobs=-1,
        random_state=42,
        scale_pos_weight=scale_pos_weight,
    )
    raw_model.fit(X_train, y_train)

    # 2. Fit isotonic calibration on VAL split using FrozenEstimator
    # Scikit-learn 1.9.1 removed cv="prefit"; the supported API is wrapping the fitted model with FrozenEstimator.
    print("Fitting isotonic calibrator on VAL split using FrozenEstimator wrapper...")
    frozen_base = FrozenEstimator(raw_model)
    calibrated_model = CalibratedClassifierCV(
        estimator=frozen_base,
        method="isotonic",
    )
    calibrated_model.fit(X_val, y_val)

    # 3. Evaluate calibrated probabilities on VAL
    val_probs = calibrated_model.predict_proba(X_val)[:, 1]
    val_pr_auc = float(average_precision_score(y_val, val_probs))
    val_roc_auc = float(roc_auc_score(y_val, val_probs))
    val_brier = float(brier_score_loss(y_val, val_probs))

    calibration_table = compute_reliability_table(y_val.to_numpy(), val_probs, n_bins=10)

    print("\n" + "=" * 60)
    print("VAL METRICS (Calibrated Probabilities)")
    print("=" * 60)
    print(f"PR-AUC:      {val_pr_auc:.5f}")
    print(f"ROC-AUC:     {val_roc_auc:.5f}")
    print(f"Brier Score: {val_brier:.5f}")
    print("\nReliability Calibration Table (10 bins on VAL):")
    print(f"{'Bin':<14} {'Count':<8} {'Mean Pred Prob':<16} {'Actual Fraud Rate':<18}")
    print("-" * 58)
    for row in calibration_table:
        print(f"{row['bin']:<14} {row['count']:<8} {row['mean_predicted_probability']:<16.4f} {row['actual_fraud_rate']:<18.4f}")

    # 4. 5-fold StratifiedKFold CV on TRAIN only (uncalibrated architecture)
    print("\n" + "=" * 60)
    print("5-FOLD CROSS VALIDATION (TRAIN split only, uncalibrated PR-AUC)")
    print("=" * 60)
    skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
    cv_scores: List[float] = []

    for fold, (t_idx, v_idx) in enumerate(skf.split(X_train, y_train), 1):
        fold_X_tr, fold_y_tr = X_train.iloc[t_idx], y_train.iloc[t_idx]
        fold_X_va, fold_y_va = X_train.iloc[v_idx], y_train.iloc[v_idx]

        fold_neg = int((fold_y_tr == 0).sum())
        fold_pos = int((fold_y_tr == 1).sum())
        fold_spw = float(fold_neg / fold_pos)

        fold_clf = XGBClassifier(
            n_estimators=300,
            max_depth=6,
            learning_rate=0.1,
            subsample=0.8,
            colsample_bytree=0.8,
            tree_method="hist",
            eval_metric="aucpr",
            n_jobs=-1,
            random_state=42,
            scale_pos_weight=fold_spw,
        )
        fold_clf.fit(fold_X_tr, fold_y_tr)
        fold_probs = fold_clf.predict_proba(fold_X_va)[:, 1]
        fold_score = float(average_precision_score(fold_y_va, fold_probs))
        cv_scores.append(fold_score)
        print(f"Fold {fold}: PR-AUC = {fold_score:.5f}")

    cv_mean = float(np.mean(cv_scores))
    cv_std = float(np.std(cv_scores))
    print(f"\nCV PR-AUC Mean: {cv_mean:.5f} ± {cv_std:.5f}")

    # 5. Save model artifacts and metrics
    print(f"\nSaving calibrated model to {MODEL_STORE_DIR / 'model.joblib'}...")
    joblib.dump(calibrated_model, MODEL_STORE_DIR / "model.joblib")

    print(f"Saving raw model to {MODEL_STORE_DIR / 'raw_model.joblib'}...")
    joblib.dump(raw_model, MODEL_STORE_DIR / "raw_model.joblib")

    train_metrics = {
        "val_pr_auc": round(val_pr_auc, 5),
        "val_roc_auc": round(val_roc_auc, 5),
        "val_brier": round(val_brier, 5),
        "calibration_table": calibration_table,
        "cv_pr_auc_mean": round(cv_mean, 5),
        "cv_pr_auc_std": round(cv_std, 5),
        "cv_scores": [round(s, 5) for s in cv_scores],
        "scale_pos_weight": round(scale_pos_weight, 4),
        "train_rows": len(X_train),
        "train_fraud": n_pos,
        "val_rows": len(X_val),
        "val_fraud": int(y_val.sum()),
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "calibration_method_used": "CalibratedClassifierCV(estimator=FrozenEstimator(raw_model), method='isotonic')",
        "runtime_seconds": round(time.time() - t0, 2),
    }

    with open(TRAIN_METRICS_PATH, "w", encoding="utf-8") as f:
        json.dump(train_metrics, f, indent=2)

    print(f"Training metrics saved to {TRAIN_METRICS_PATH}.")
    return train_metrics


if __name__ == "__main__":
    run_training_pipeline()
