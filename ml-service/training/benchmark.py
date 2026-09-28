"""Track 1 Benchmark: 3 model architectures x 3 class imbalance strategies."""

import json
from pathlib import Path
import time
from typing import Dict, List, Tuple, Union

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.metrics import (
    average_precision_score,
    confusion_matrix,
    f1_score,
    precision_recall_curve,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedKFold, cross_val_score

from training import config
from training.data_loader import load_split
from training.models import VALID_IMBALANCES, VALID_MODELS, build_pipeline


def find_best_f1_threshold(
    y_true: Union[pd.Series, np.ndarray],
    y_probs: Union[pd.Series, np.ndarray],
) -> Tuple[float, float, float, float]:
    """Find the decision threshold that maximizes F1 score on validation predictions.

    Uses precision_recall_curve and handles the final threshold-less point correctly.

    Returns:
        (best_threshold, best_f1, best_precision, best_recall)
    """
    precisions, recalls, thresholds = precision_recall_curve(y_true, y_probs)

    # precision and recall arrays have length len(thresholds) + 1.
    # The last point corresponds to (recall=0, precision=1) without a threshold.
    p_sub = precisions[:-1]
    r_sub = recalls[:-1]
    denom = p_sub + r_sub
    f1_scores = np.where(denom > 0, 2.0 * p_sub * r_sub / denom, 0.0)

    best_idx = int(np.argmax(f1_scores))
    best_threshold = float(thresholds[best_idx])
    best_f1 = float(f1_scores[best_idx])
    best_precision = float(p_sub[best_idx])
    best_recall = float(r_sub[best_idx])

    return best_threshold, best_f1, best_precision, best_recall


def run_configuration(
    model_name: str,
    imbalance: str,
    X_train: pd.DataFrame,
    y_train: pd.Series,
    X_val: pd.DataFrame,
    y_val: pd.Series,
) -> Dict:
    """Execute 5-fold CV on TRAIN, fit on full TRAIN, and evaluate on VAL."""
    config_filename = f"{model_name}__{imbalance}.json"
    config_path = config.BENCHMARK_DIR / config_filename

    # Resume support: skip if already computed
    if config_path.exists():
        print(f"  [CACHE] Configuration {model_name} + {imbalance} already completed. Loading from {config_filename}")
        with open(config_path, "r", encoding="utf-8") as f:
            return json.load(f)

    print(f"  >>> Starting: {model_name} + {imbalance} ...")
    start_total_time = time.perf_counter()

    # a) 5-fold StratifiedKFold CV on TRAIN split only (scoring: average_precision)
    cv = StratifiedKFold(n_splits=5, shuffle=True, random_state=config.RANDOM_SEED)
    cv_pipe = build_pipeline(model_name, imbalance, y_train)
    cv_scores = cross_val_score(
        cv_pipe,
        X_train,
        y_train,
        cv=cv,
        scoring="average_precision",
        n_jobs=1,
    )
    cv_pr_auc_mean = float(np.mean(cv_scores))
    cv_pr_auc_std = float(np.std(cv_scores))

    # b) Fit on the full train split
    full_pipe = build_pipeline(model_name, imbalance, y_train)
    t_fit_start = time.perf_counter()
    full_pipe.fit(X_train, y_train)
    fit_time_seconds = float(time.perf_counter() - t_fit_start)

    # Predict probabilities on VAL split
    val_probs = full_pipe.predict_proba(X_val)[:, 1]

    # c) Compute VAL PR-AUC and ROC-AUC
    val_pr_auc = float(average_precision_score(y_val, val_probs))
    val_roc_auc = float(roc_auc_score(y_val, val_probs))

    # d) Choose decision threshold that maximizes F1 on VAL
    best_threshold, _, _, _ = find_best_f1_threshold(y_val, val_probs)

    # Predictions at tuned threshold
    y_pred_tuned = (val_probs >= best_threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_val, y_pred_tuned).ravel()
    precision_tuned = float(precision_score(y_val, y_pred_tuned, zero_division=0))
    recall_tuned = float(recall_score(y_val, y_pred_tuned, zero_division=0))
    f1_tuned = float(f1_score(y_val, y_pred_tuned, zero_division=0))
    fpr_tuned = float(fp / (fp + tn)) if (fp + tn) > 0 else 0.0

    # Predictions at default threshold 0.5
    y_pred_05 = (val_probs >= 0.5).astype(int)
    precision_05 = float(precision_score(y_val, y_pred_05, zero_division=0))
    recall_05 = float(recall_score(y_val, y_pred_05, zero_division=0))
    f1_05 = float(f1_score(y_val, y_pred_05, zero_division=0))

    elapsed = time.perf_counter() - start_total_time
    print(
        f"  [DONE] {model_name} + {imbalance}: "
        f"CV PR-AUC={cv_pr_auc_mean:.4f}±{cv_pr_auc_std:.4f}, "
        f"VAL PR-AUC={val_pr_auc:.4f}, VAL ROC-AUC={val_roc_auc:.4f}, "
        f"F1={f1_tuned:.4f} (at thr={best_threshold:.4f}), "
        f"Fit Time={fit_time_seconds:.2f}s, Total Elapsed={elapsed:.2f}s"
    )

    result = {
        "model": model_name,
        "imbalance": imbalance,
        "cv_pr_auc_mean": cv_pr_auc_mean,
        "cv_pr_auc_std": cv_pr_auc_std,
        "val_pr_auc": val_pr_auc,
        "val_roc_auc": val_roc_auc,
        "tuned_threshold": best_threshold,
        "precision": precision_tuned,
        "recall": recall_tuned,
        "f1": f1_tuned,
        "fpr": fpr_tuned,
        "f1_at_05": f1_05,
        "precision_at_05": precision_05,
        "recall_at_05": recall_05,
        "confusion_matrix": {
            "tn": int(tn),
            "fp": int(fp),
            "fn": int(fn),
            "tp": int(tp),
        },
        "fit_time_seconds": fit_time_seconds,
        "total_elapsed_seconds": elapsed,
        "val_probs": [float(p) for p in val_probs],
    }

    # Write per-configuration result immediately
    with open(config_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)

    return result


def aggregate_results(results: List[Dict], y_val: pd.Series) -> Dict:
    """Aggregate individual configuration results into summary reports, table, and figures."""
    # Build dataframe for tabular export
    rows = []
    for r in results:
        rows.append(
            {
                "model": r["model"],
                "imbalance": r["imbalance"],
                "cv_pr_auc_mean": r["cv_pr_auc_mean"],
                "cv_pr_auc_std": r["cv_pr_auc_std"],
                "val_pr_auc": r["val_pr_auc"],
                "val_roc_auc": r["val_roc_auc"],
                "tuned_threshold": r["tuned_threshold"],
                "precision": r["precision"],
                "recall": r["recall"],
                "f1": r["f1"],
                "fpr": r["fpr"],
                "f1_at_05": r["f1_at_05"],
                "fit_time_seconds": r["fit_time_seconds"],
                "tn": r["confusion_matrix"]["tn"],
                "fp": r["confusion_matrix"]["fp"],
                "fn": r["confusion_matrix"]["fn"],
                "tp": r["confusion_matrix"]["tp"],
            }
        )
    df_res = pd.DataFrame(rows)

    # 1. Save CSV
    df_res.to_csv(config.BENCHMARK_RESULTS_CSV, index=False)

    # 2. Save Markdown table
    md_lines = [
        "# Track 1 Benchmark Results",
        "",
        "| Model | Imbalance Strategy | CV PR-AUC (mean±std) | VAL PR-AUC | VAL ROC-AUC | Precision | Recall | F1 | FPR | F1 @ 0.5 | Fit Time (s) |",
        "| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |",
    ]
    for _, r in df_res.iterrows():
        md_lines.append(
            f"| `{r['model']}` | `{r['imbalance']}` | {r['cv_pr_auc_mean']:.4f} ± {r['cv_pr_auc_std']:.4f} | "
            f"**{r['val_pr_auc']:.4f}** | {r['val_roc_auc']:.4f} | {r['precision']:.4f} | {r['recall']:.4f} | "
            f"{r['f1']:.4f} | {r['fpr']:.6f} | {r['f1_at_05']:.4f} | {r['fit_time_seconds']:.2f} |"
        )
    md_content = "\n".join(md_lines) + "\n"
    with open(config.BENCHMARK_TABLE_MD, "w", encoding="utf-8") as f:
        f.write(md_content)

    # 3. Identify Winner (highest VAL PR-AUC)
    winner_row = df_res.sort_values(by="val_pr_auc", ascending=False).iloc[0]
    winner_dict = {
        "winner": {
            "model": winner_row["model"],
            "imbalance": winner_row["imbalance"],
        },
        "model": winner_row["model"],
        "imbalance": winner_row["imbalance"],
        "val_pr_auc": float(winner_row["val_pr_auc"]),
        "val_roc_auc": float(winner_row["val_roc_auc"]),
        "val_tuned_threshold": float(winner_row["tuned_threshold"]),
        "cv_pr_auc_mean": float(winner_row["cv_pr_auc_mean"]),
        "cv_pr_auc_std": float(winner_row["cv_pr_auc_std"]),
        "precision": float(winner_row["precision"]),
        "recall": float(winner_row["recall"]),
        "f1": float(winner_row["f1"]),
        "fpr": float(winner_row["fpr"]),
        "f1_at_05": float(winner_row["f1_at_05"]),
        "fit_time_seconds": float(winner_row["fit_time_seconds"]),
    }
    with open(config.BENCHMARK_WINNER_JSON, "w", encoding="utf-8") as f:
        json.dump(winner_dict, f, indent=2)

    # 4. Figure: Validation PR curves for all 9 configurations
    fig, ax = plt.subplots(figsize=(10, 7))
    model_colors = {"logreg": "#1f77b4", "random_forest": "#2ca02c", "xgboost": "#ff7f0e"}
    style_lines = {"none": "-", "class_weight": "--", "smote": ":"}

    for r in results:
        probs = np.array(r["val_probs"])
        p, rec, _ = precision_recall_curve(y_val, probs)
        label = f"{r['model']} + {r['imbalance']} (PR-AUC={r['val_pr_auc']:.4f})"
        ax.plot(
            rec,
            p,
            label=label,
            color=model_colors.get(r["model"], "black"),
            linestyle=style_lines.get(r["imbalance"], "-"),
            linewidth=1.8,
            alpha=0.85,
        )

    # Baseline fraud rate
    baseline_rate = float((y_val == 1).mean())
    ax.axhline(baseline_rate, color="gray", linestyle="--", linewidth=1, label=f"Baseline ({baseline_rate:.4f})")

    ax.set_title("Validation Precision-Recall Curves (Track 1 Benchmark)", fontsize=13, pad=12)
    ax.set_xlabel("Recall", fontsize=11)
    ax.set_ylabel("Precision", fontsize=11)
    ax.set_xlim([0.0, 1.0])
    ax.set_ylim([0.0, 1.05])
    ax.grid(True, linestyle="--", alpha=0.5)
    ax.legend(loc="lower left", fontsize=9, framealpha=0.9)
    plt.tight_layout()
    fig.savefig(config.PR_CURVES_VAL_FIG, dpi=300)
    plt.close(fig)

    # 5. Figure: Grouped bar chart of VAL PR-AUC by model and imbalance strategy
    fig, ax = plt.subplots(figsize=(9, 6))
    models = ["logreg", "random_forest", "xgboost"]
    imbalances = ["none", "class_weight", "smote"]
    x = np.arange(len(models))
    width = 0.25

    colors = {"none": "#7f7f7f", "class_weight": "#1f77b4", "smote": "#ff7f0e"}

    for i, imb in enumerate(imbalances):
        vals = []
        for m in models:
            sub = df_res[(df_res["model"] == m) & (df_res["imbalance"] == imb)]
            vals.append(sub["val_pr_auc"].values[0] if len(sub) > 0 else 0.0)

        offset = (i - 1) * width
        bars = ax.bar(x + offset, vals, width, label=imb, color=colors[imb], edgecolor="black", linewidth=0.5)
        for bar in bars:
            h = bar.get_height()
            ax.annotate(
                f"{h:.3f}",
                xy=(bar.get_x() + bar.get_width() / 2, h),
                xytext=(0, 4),
                textcoords="offset points",
                ha="center",
                va="bottom",
                fontsize=8.5,
                fontweight="bold",
            )

    ax.set_title("Validation PR-AUC across Architectures & Imbalance Strategies", fontsize=13, pad=12)
    ax.set_xlabel("Model Architecture", fontsize=11)
    ax.set_ylabel("Validation PR-AUC (Average Precision)", fontsize=11)
    ax.set_xticks(x)
    ax.set_xticklabels(models, fontsize=10)
    ax.set_ylim([0.0, 1.0])
    ax.grid(axis="y", linestyle="--", alpha=0.6)
    ax.legend(title="Imbalance Strategy", loc="upper left", framealpha=0.9)
    plt.tight_layout()
    fig.savefig(config.BENCHMARK_PR_AUC_FIG, dpi=300)
    plt.close(fig)

    return winner_dict


def run_benchmark() -> Dict:
    """Execute complete Track 1 benchmark across all 9 configurations."""
    config.ensure_output_dirs()

    print("==================================================")
    print("STARTING TRACK 1 BENCHMARK (3 Models x 3 Imbalances)")
    print("==================================================")

    # Load splits
    X_train, y_train, X_val, y_val, X_test, y_test = load_split()

    # Rule: IMMEDIATELY discard X_test and y_test; never reference them in benchmark.py
    del X_test, y_test

    # Filter to 29 Track 1 features (dropping raw Time)
    X_train = X_train[config.TRACK_1_FEATURES]
    X_val = X_val[config.TRACK_1_FEATURES]

    print(f"Train split shape: {X_train.shape} (positives: {(y_train == 1).sum()})")
    print(f"Val split shape:   {X_val.shape} (positives: {(y_val == 1).sum()})")
    print(f"Features (29):     {list(X_train.columns)}")
    print("-" * 50)

    configs_to_run = [
        (m, imb)
        for m in ["logreg", "random_forest", "xgboost"]
        for imb in ["none", "class_weight", "smote"]
    ]

    all_results = []
    overall_start = time.perf_counter()

    for idx, (m, imb) in enumerate(configs_to_run, start=1):
        print(f"[{idx}/9] Running Configuration: model={m}, imbalance={imb}")
        res = run_configuration(m, imb, X_train, y_train, X_val, y_val)
        all_results.append(res)

    total_time = time.perf_counter() - overall_start
    print("-" * 50)
    print(f"All 9 configurations completed in {total_time:.2f}s ({total_time / 60.0:.2f} minutes).")

    winner = aggregate_results(all_results, y_val)
    print("==================================================")
    print(f"BENCHMARK WINNER: {winner['winner']['model']} + {winner['winner']['imbalance']}")
    print(f"VAL PR-AUC:       {winner['val_pr_auc']:.4f}")
    print(f"VAL Tuned Thresh: {winner['val_tuned_threshold']:.4f}")
    print(f"CV PR-AUC:        {winner['cv_pr_auc_mean']:.4f} ± {winner['cv_pr_auc_std']:.4f}")
    print("==================================================")
    return winner


def main() -> None:
    run_benchmark()


if __name__ == "__main__":
    main()
