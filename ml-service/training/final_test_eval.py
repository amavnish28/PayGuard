"""Final test evaluation of the winning Track 1 benchmark model."""

import argparse
import json
from pathlib import Path
import sys
import time
from typing import Dict, List, Optional

import numpy as np
from sklearn.metrics import (
    average_precision_score,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)

from training import config
from training.data_loader import load_split
from training.models import build_pipeline


def evaluate_on_test(
    reports_dir: Optional[Path] = None,
    force: bool = False,
) -> Dict:
    """Evaluate winning benchmark model on TEST split.

    Refuses to run if test_result.json exists unless force=True.
    Refits winning model on TRAIN split only, applies VAL-tuned threshold (no test tuning).
    """
    target_reports_dir = Path(reports_dir) if reports_dir is not None else config.REPORT_DIR
    winner_path = target_reports_dir / "benchmark_winner.json"
    test_result_path = target_reports_dir / "test_result.json"

    # Enforce non-zero exit / error if test_result.json exists without --force
    if test_result_path.exists() and not force:
        raise RuntimeError(
            f"Evaluation refused: '{test_result_path}' already exists. "
            "Use the --force flag to allow re-running test evaluation."
        )

    if not winner_path.exists():
        raise FileNotFoundError(
            f"Benchmark winner file not found at: {winner_path}. "
            "Run training.benchmark first to determine the winning configuration."
        )

    with open(winner_path, "r", encoding="utf-8") as f:
        winner_data = json.load(f)

    if "winner" in winner_data:
        model_name = winner_data["winner"]["model"]
        imbalance = winner_data["winner"]["imbalance"]
    else:
        model_name = winner_data["model"]
        imbalance = winner_data["imbalance"]

    applied_threshold = float(winner_data["val_tuned_threshold"])

    print("==================================================")
    print("FINAL TEST EVALUATION (Track 1 Winning Configuration)")
    print("==================================================")
    print(f"Winning Model:       {model_name}")
    print(f"Imbalance Strategy:  {imbalance}")
    print(f"Applied Threshold:   {applied_threshold:.4f} (tuned on validation)")
    print("-" * 50)

    # Load splits
    X_train, y_train, X_val, y_val, X_test, y_test = load_split()
    del X_val, y_val  # Validation split not used during test evaluation

    # Use 29 Track 1 features
    X_train = X_train[config.TRACK_1_FEATURES]
    X_test = X_test[config.TRACK_1_FEATURES]

    # Refit winning configuration on TRAIN split only
    print("Refitting winning pipeline on full train split...")
    pipe = build_pipeline(model_name, imbalance, y_train)
    t_start = time.perf_counter()
    pipe.fit(X_train, y_train)
    fit_time = time.perf_counter() - t_start
    print(f"Fit completed in {fit_time:.2f}s")

    # Evaluate on TEST split
    print("Predicting probabilities on test split...")
    test_probs = pipe.predict_proba(X_test)[:, 1]

    test_pr_auc = float(average_precision_score(y_test, test_probs))
    test_roc_auc = float(roc_auc_score(y_test, test_probs))

    # Apply the threshold chosen on VAL (do NOT re-tune on test)
    y_pred = (test_probs >= applied_threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_test, y_pred).ravel()
    precision = float(precision_score(y_test, y_pred, zero_division=0))
    recall = float(recall_score(y_test, y_pred, zero_division=0))
    f1 = float(f1_score(y_test, y_pred, zero_division=0))
    fpr = float(fp / (fp + tn)) if (fp + tn) > 0 else 0.0

    test_result = {
        "winning_model": model_name,
        "winning_imbalance": imbalance,
        "applied_threshold": applied_threshold,
        "test_pr_auc": test_pr_auc,
        "test_roc_auc": test_roc_auc,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "fpr": fpr,
        "confusion_matrix": {
            "tn": int(tn),
            "fp": int(fp),
            "fn": int(fn),
            "tp": int(tp),
        },
        "refit_time_seconds": fit_time,
    }

    with open(test_result_path, "w", encoding="utf-8") as f:
        json.dump(test_result, f, indent=2)

    print("-" * 50)
    print("TEST EVALUATION RESULTS:")
    print(f"  PR-AUC:           {test_pr_auc:.4f}")
    print(f"  ROC-AUC:          {test_roc_auc:.4f}")
    print(f"  Precision:        {precision:.4f}")
    print(f"  Recall:           {recall:.4f}")
    print(f"  F1 Score:         {f1:.4f}")
    print(f"  FPR:              {fpr:.6f}")
    print(f"  Confusion Matrix: TN={tn:,}, FP={fp:,}, FN={fn:,}, TP={tp:,}")
    print(f"Saved result: {test_result_path}")
    print("==================================================")

    return test_result


def main(argv: Optional[List[str]] = None) -> None:
    """CLI entrypoint for final test evaluation."""
    parser = argparse.ArgumentParser(description="Evaluate winning benchmark model on test split.")
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite reports/test_result.json if it already exists.",
    )
    args = parser.parse_args(argv)

    try:
        evaluate_on_test(force=args.force)
    except RuntimeError as err:
        print(f"Error: {err}", file=sys.stderr)
        sys.exit(1)
    except Exception as err:
        print(f"Unexpected error during test evaluation: {err}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
