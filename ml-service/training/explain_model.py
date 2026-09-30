"""Compute and export SHAP explanations for the PayGuard production model.

Uses shap.TreeExplainer on the underlying XGBoost model to evaluate feature contributions
on the VAL split, exporting global importance and individual scenario examples.
"""

import json
from pathlib import Path
from typing import Any, Dict, List

import joblib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import shap
from xgboost import XGBClassifier

ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
DATA_PATH = ML_SERVICE_ROOT / "data" / "synthetic" / "synthetic_v1.csv"
MODEL_STORE_DIR = ML_SERVICE_ROOT / "model_store" / "xgboost_v1"
REPORT_DIR = ML_SERVICE_ROOT / "reports"
FIG_DIR = REPORT_DIR / "figures"

CALIBRATED_MODEL_PATH = MODEL_STORE_DIR / "model.joblib"
RAW_MODEL_PATH = MODEL_STORE_DIR / "raw_model.joblib"
EXPLAINER_PATH = MODEL_STORE_DIR / "explainer.joblib"

GLOBAL_IMPORTANCE_JSON = REPORT_DIR / "shap_global_importance.json"
EXAMPLES_JSON = REPORT_DIR / "shap_examples.json"
IMPORTANCE_PNG = FIG_DIR / "shap_global_importance.png"

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


def extract_base_model() -> XGBClassifier:
    """Load the raw XGBoost estimator for TreeExplainer."""
    if RAW_MODEL_PATH.exists():
        return joblib.load(RAW_MODEL_PATH)

    calibrated = joblib.load(CALIBRATED_MODEL_PATH)
    # CalibratedClassifierCV wraps FrozenEstimator or base estimator
    if hasattr(calibrated.estimator, "estimator"):
        return calibrated.estimator.estimator
    return calibrated.estimator


def generate_explanations() -> None:
    """Generate SHAP explanations on VAL split and save artifacts."""
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    FIG_DIR.mkdir(parents=True, exist_ok=True)
    MODEL_STORE_DIR.mkdir(parents=True, exist_ok=True)

    print("Loading VAL data and models...")
    df = pd.read_csv(DATA_PATH)
    val_df = df[df["split"] == "val"].reset_index(drop=True)
    X_val = val_df[CONTRACT_FEATURES]
    y_val = val_df["is_fraud"].to_numpy()

    base_model = extract_base_model()
    calibrated_model = joblib.load(CALIBRATED_MODEL_PATH)
    val_probs = calibrated_model.predict_proba(X_val)[:, 1]

    # Add predicted probabilities to val_df for example selection
    val_df["predicted_probability"] = val_probs

    print("Building shap.TreeExplainer on underlying XGBoost model...")
    explainer = shap.TreeExplainer(base_model)

    print(f"Computing SHAP values for all {len(X_val)} validation rows...")
    shap_explanation = explainer(X_val)
    shap_values = shap_explanation.values

    # Handle shape format (n_samples, n_features) or binary (n_samples, n_features, 2)
    if shap_values.ndim == 3 and shap_values.shape[2] == 2:
        shap_values_pos = shap_values[:, :, 1]
    else:
        shap_values_pos = shap_values

    # 1. Global feature importance (mean absolute SHAP value)
    mean_abs_shap = np.mean(np.abs(shap_values_pos), axis=0)
    global_importance = {
        feat: round(float(mean_abs_shap[i]), 5)
        for i, feat in enumerate(CONTRACT_FEATURES)
    }
    sorted_importance = dict(sorted(global_importance.items(), key=lambda item: item[1], reverse=True))

    print(f"Writing global importance to {GLOBAL_IMPORTANCE_JSON}...")
    with open(GLOBAL_IMPORTANCE_JSON, "w", encoding="utf-8") as f:
        json.dump(sorted_importance, f, indent=2)

    # 2. Bar chart figure
    print(f"Saving importance bar chart to {IMPORTANCE_PNG}...")
    plt.figure(figsize=(10, 6))
    feats_sorted = list(sorted_importance.keys())
    scores_sorted = list(sorted_importance.values())

    plt.barh(feats_sorted[::-1], scores_sorted[::-1], color="#2b5c8f")
    plt.xlabel("Mean |SHAP Value| (Impact on Model Output)")
    plt.title("PayGuard Production Model: SHAP Global Feature Importance")
    plt.grid(True, linestyle="--", alpha=0.5)
    plt.tight_layout()
    plt.savefig(IMPORTANCE_PNG, dpi=200)
    plt.close()

    # 3. Select 3 fraud examples and 1 false positive example
    print("Selecting explanation examples...")
    fraud_df = val_df[val_df["is_fraud"] == 1]
    legit_df = val_df[val_df["is_fraud"] == 0]

    # Example 1: ACCOUNT_TAKEOVER with highest predicted probability
    ato_candidates = fraud_df[fraud_df["scenario"] == "ACCOUNT_TAKEOVER"]
    ato_idx = int(ato_candidates["predicted_probability"].idxmax()) if not ato_candidates.empty else int(fraud_df["predicted_probability"].idxmax())

    # Example 2: SUBTLE scenario with highest predicted probability
    subtle_candidates = fraud_df[fraud_df["scenario"] == "SUBTLE"]
    subtle_idx = int(subtle_candidates["predicted_probability"].idxmax()) if not subtle_candidates.empty else int(fraud_df["predicted_probability"].idxmax())

    # Example 3: STEALTH if prob > 0.5, else substitute ODD_HOUR_TRANSFER or CARD_TESTING
    stealth_candidates = fraud_df[(fraud_df["scenario"] == "STEALTH") & (fraud_df["predicted_probability"] > 0.5)]
    if not stealth_candidates.empty:
        ex3_idx = int(stealth_candidates["predicted_probability"].idxmax())
        ex3_label = "stealth_fraud"
    else:
        # Substitute another fraud scenario (e.g. highest prob CARD_TESTING or ODD_HOUR_TRANSFER)
        alt_candidates = fraud_df[fraud_df["scenario"].isin(["CARD_TESTING", "ODD_HOUR_TRANSFER"])]
        ex3_idx = int(alt_candidates["predicted_probability"].idxmax()) if not alt_candidates.empty else int(fraud_df["predicted_probability"].idxmax())
        ex3_label = f"substitute_{val_df.loc[ex3_idx, 'scenario'].lower()}_fraud"

    # Example 4: False Positive (legit with highest predicted probability)
    fp_idx = int(legit_df["predicted_probability"].idxmax())

    example_indices = [
        ("account_takeover_fraud", ato_idx),
        ("subtle_fraud", subtle_idx),
        (ex3_label, ex3_idx),
        ("false_positive_legit", fp_idx),
    ]

    examples_output: Dict[str, Any] = {}
    for name, idx in example_indices:
        row = val_df.loc[idx]
        row_shap = shap_values_pos[idx]

        # Top 5 features by absolute SHAP value
        top_feat_indices = np.argsort(np.abs(row_shap))[::-1][:5]
        top_5 = []
        for fi in top_feat_indices:
            top_5.append({
                "feature": CONTRACT_FEATURES[fi],
                "feature_value": float(row[CONTRACT_FEATURES[fi]]),
                "shap_value": round(float(row_shap[fi]), 5),
            })

        examples_output[name] = {
            "account_id": str(row["account_id"]),
            "txn_id": str(row["txn_id"]),
            "scenario": str(row["scenario"]),
            "is_fraud": int(row["is_fraud"]),
            "predicted_probability": round(float(row["predicted_probability"]), 5),
            "top_5_features": top_5,
        }

    print(f"Writing examples explanation to {EXAMPLES_JSON}...")
    with open(EXAMPLES_JSON, "w", encoding="utf-8") as f:
        json.dump(examples_output, f, indent=2)

    # 4. Save explainer artifact
    print(f"Saving SHAP explainer to {EXPLAINER_PATH} with joblib...")
    joblib.dump(explainer, EXPLAINER_PATH)

    print("SHAP explanation pipeline completed successfully.")


if __name__ == "__main__":
    generate_explanations()
