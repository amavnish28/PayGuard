"""Exploratory Data Analysis (EDA) for the Credit Card Fraud Detection dataset."""

import json
from pathlib import Path
from typing import Dict, List, Tuple
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from training import config
from training.data_loader import load_raw


def compute_eda_summary(df: pd.DataFrame) -> Dict:
    """Compute all EDA statistics from the raw dataframe."""
    total_rows = int(len(df))
    class_0_count = int((df["Class"] == 0).sum())
    class_1_count = int((df["Class"] == 1).sum())
    fraud_percentage = float(class_1_count / total_rows * 100)

    # Duplicates analysis (all 31 columns)
    is_dup = df.duplicated()
    exact_duplicate_rows = int(is_dup.sum())
    fraud_duplicate_rows = int(df.loc[is_dup, "Class"].sum())
    df_dedup = df.drop_duplicates()
    total_rows_after_dedup = int(len(df_dedup))
    fraud_count_after_dedup = int((df_dedup["Class"] == 1).sum())

    # Amount statistics by class
    s0 = df.loc[df["Class"] == 0, "Amount"]
    s1 = df.loc[df["Class"] == 1, "Amount"]

    amount_summary = {
        "class_0": {
            "min": float(s0.min()),
            "median": float(s0.median()),
            "mean": float(s0.mean()),
            "p95": float(s0.quantile(0.95)),
            "p99": float(s0.quantile(0.99)),
            "max": float(s0.max()),
        },
        "class_1": {
            "min": float(s1.min()),
            "median": float(s1.median()),
            "mean": float(s1.mean()),
            "p95": float(s1.quantile(0.95)),
            "p99": float(s1.quantile(0.99)),
            "max": float(s1.max()),
        },
    }

    per_class_means_amount = {
        "0": float(s0.mean()),
        "1": float(s1.mean()),
    }

    # Time statistics
    time_min = float(df["Time"].min())
    time_max = float(df["Time"].max())
    span_seconds = float(time_max - time_min)
    span_hours = float(span_seconds / 3600.0)

    time_summary = {
        "min": time_min,
        "max": time_max,
        "span_seconds": span_seconds,
        "span_hours": span_hours,
    }

    # Correlation with Class for V1..V28 and Amount
    feature_candidates = [f"V{i}" for i in range(1, 29)] + ["Amount"]
    corrs = {}
    for col in feature_candidates:
        corrs[col] = float(df[col].corr(df["Class"]))

    # Sort descending by absolute Pearson correlation
    sorted_corrs = sorted(corrs.items(), key=lambda item: abs(item[1]), reverse=True)
    top_10_features = [
        {
            "feature": feat,
            "correlation": val,
            "abs_correlation": abs(val),
        }
        for feat, val in sorted_corrs[:10]
    ]

    summary = {
        "class_counts": {
            "0": class_0_count,
            "1": class_1_count,
            "total": total_rows,
        },
        "fraud_percentage": fraud_percentage,
        "duplicates": {
            "exact_duplicate_rows": exact_duplicate_rows,
            "fraud_duplicate_rows": fraud_duplicate_rows,
            "fraud_count_after_dedup": fraud_count_after_dedup,
            "total_rows_after_dedup": total_rows_after_dedup,
        },
        "amount_summary": amount_summary,
        "per_class_means_amount": per_class_means_amount,
        "time_summary": time_summary,
        "top_10_correlated_features": top_10_features,
    }

    return summary


def plot_class_balance(df: pd.DataFrame, output_path: Path) -> None:
    """Plot class balance bar chart with log-scale y axis."""
    counts = df["Class"].value_counts().sort_index()
    labels = ["Legitimate (Class 0)", "Fraudulent (Class 1)"]
    values = [int(counts[0]), int(counts[1])]
    percentages = [v / len(df) * 100 for v in values]

    fig, ax = plt.subplots(figsize=(7, 5))
    bars = ax.bar(labels, values, color=["#1f77b4", "#d62728"], width=0.5, edgecolor="black", linewidth=0.8)
    ax.set_yscale("log")
    ax.set_title("Class Balance Distribution (Log Scale)", fontsize=13, pad=12)
    ax.set_ylabel("Count (log scale)", fontsize=11)
    ax.grid(axis="y", linestyle="--", alpha=0.6)

    for bar, val, pct in zip(bars, values, percentages):
        y_pos = bar.get_height()
        ax.annotate(
            f"{val:,}\n({pct:.3f}%)",
            xy=(bar.get_x() + bar.get_width() / 2, y_pos),
            xytext=(0, 6),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=10,
            fontweight="bold",
        )

    # Add extra headspace for annotation above the tallest bar
    ax.set_ylim(top=values[0] * 5)
    plt.tight_layout()
    fig.savefig(output_path, dpi=300)
    plt.close(fig)


def plot_amount_by_class(df: pd.DataFrame, output_path: Path) -> None:
    """Plot density-normalized histogram of log1p(Amount) by class."""
    amt_0 = np.log1p(df.loc[df["Class"] == 0, "Amount"])
    amt_1 = np.log1p(df.loc[df["Class"] == 1, "Amount"])

    fig, ax = plt.subplots(figsize=(8, 5))
    ax.hist(
        amt_0,
        bins=50,
        density=True,
        alpha=0.55,
        color="#1f77b4",
        edgecolor="#1f77b4",
        linewidth=0.5,
        label=f"Class 0 (Legitimate, n={len(amt_0):,})",
    )
    ax.hist(
        amt_1,
        bins=50,
        density=True,
        alpha=0.6,
        color="#d62728",
        edgecolor="#d62728",
        linewidth=0.5,
        label=f"Class 1 (Fraudulent, n={len(amt_1):,})",
    )

    ax.set_title("Transaction Amount Distribution by Class [log1p(Amount)]", fontsize=13, pad=12)
    ax.set_xlabel("log1p(Amount) = ln(Amount + 1)", fontsize=11)
    ax.set_ylabel("Normalized Density", fontsize=11)
    ax.legend(frameon=True, facecolor="white", framealpha=0.9)
    ax.grid(True, linestyle="--", alpha=0.5)

    plt.tight_layout()
    fig.savefig(output_path, dpi=300)
    plt.close(fig)


def plot_time_by_class(df: pd.DataFrame, output_path: Path) -> None:
    """Plot density-normalized histogram of Time in hours by class."""
    time_0 = df.loc[df["Class"] == 0, "Time"] / 3600.0
    time_1 = df.loc[df["Class"] == 1, "Time"] / 3600.0

    fig, ax = plt.subplots(figsize=(8, 5))
    ax.hist(
        time_0,
        bins=48,
        density=True,
        alpha=0.55,
        color="#1f77b4",
        edgecolor="#1f77b4",
        linewidth=0.5,
        label="Class 0 (Legitimate)",
    )
    ax.hist(
        time_1,
        bins=48,
        density=True,
        alpha=0.6,
        color="#d62728",
        edgecolor="#d62728",
        linewidth=0.5,
        label="Class 1 (Fraudulent)",
    )

    ax.set_title("Transaction Time Distribution by Class (Hours)", fontsize=13, pad=12)
    ax.set_xlabel("Time (Hours elapsed from start of recording)", fontsize=11)
    ax.set_ylabel("Normalized Density", fontsize=11)
    ax.legend(frameon=True, facecolor="white", framealpha=0.9)
    ax.grid(True, linestyle="--", alpha=0.5)

    plt.tight_layout()
    fig.savefig(output_path, dpi=300)
    plt.close(fig)


def plot_top_corr_features(top_features: List[Dict], output_path: Path) -> None:
    """Plot horizontal bar chart of the top 10 features correlated with Class."""
    # Reverse order so the feature with largest absolute correlation appears at the top
    items = list(reversed(top_features))
    features = [item["feature"] for item in items]
    corrs = [item["correlation"] for item in items]
    colors = ["#d62728" if c < 0 else "#1f77b4" for c in corrs]

    fig, ax = plt.subplots(figsize=(9, 6))
    bars = ax.barh(features, corrs, color=colors, height=0.6, edgecolor="black", linewidth=0.6)
    ax.axvline(0, color="black", linestyle="-", linewidth=0.8)

    ax.set_title("Top 10 Features by Absolute Pearson Correlation with Class", fontsize=13, pad=12)
    ax.set_xlabel("Pearson Correlation Coefficient (r)", fontsize=11)
    ax.set_ylabel("Feature Name", fontsize=11)
    ax.grid(axis="x", linestyle="--", alpha=0.6)

    # Annotate bar values
    x_min, x_max = min(corrs), max(corrs)
    padding = (x_max - x_min) * 0.05
    for bar, val in zip(bars, corrs):
        offset = -padding if val < 0 else padding
        ha = "right" if val < 0 else "left"
        ax.annotate(
            f"{val:+.4f}",
            xy=(val, bar.get_y() + bar.get_height() / 2),
            xytext=(3 if val >= 0 else -3, 0),
            textcoords="offset points",
            ha=ha,
            va="center",
            fontsize=9.5,
            fontweight="bold",
        )

    ax.set_xlim(min(corrs) - padding * 2, max(corrs) + padding * 2)
    plt.tight_layout()
    fig.savefig(output_path, dpi=300)
    plt.close(fig)


def run_eda() -> Dict:
    """Run full EDA pipeline, generate figures, write eda_summary.json, and print summary."""
    config.ensure_output_dirs()

    # Load and validate dataset
    df = load_raw()

    # Compute EDA summary dictionary
    summary = compute_eda_summary(df)

    # Save summary JSON
    summary_path = config.REPORT_DIR / "eda_summary.json"
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    # Generate all four requested figures
    plot_class_balance(df, config.FIG_DIR / "class_balance.png")
    plot_amount_by_class(df, config.FIG_DIR / "amount_by_class.png")
    plot_time_by_class(df, config.FIG_DIR / "time_by_class.png")
    plot_top_corr_features(summary["top_10_correlated_features"], config.FIG_DIR / "top_corr_features.png")

    # Readable console output
    print("=" * 60)
    print("PAYGUARD DATASET EXPLORATORY DATA ANALYSIS (EDA) SUMMARY")
    print("=" * 60)
    print(f"Total Rows:              {summary['class_counts']['total']:,}")
    print(f"Legitimate Rows (0):     {summary['class_counts']['0']:,}")
    print(f"Fraudulent Rows (1):     {summary['class_counts']['1']:,}")
    print(f"Fraud Percentage:        {summary['fraud_percentage']:.4f}%")
    print("-" * 60)
    print("DUPLICATE ROWS ANALYSIS:")
    print(f"Exact Duplicate Rows:    {summary['duplicates']['exact_duplicate_rows']:,}")
    print(f"Fraud Duplicates:        {summary['duplicates']['fraud_duplicate_rows']:,}")
    print(f"Fraud Rows After Dedup:  {summary['duplicates']['fraud_count_after_dedup']:,}")
    print(f"Total Rows After Dedup:  {summary['duplicates']['total_rows_after_dedup']:,}")
    print("-" * 60)
    print("TRANSACTION AMOUNT STATISTICS ($):")
    c0 = summary["amount_summary"]["class_0"]
    c1 = summary["amount_summary"]["class_1"]
    print(f"  Class 0 (Legit) -> Min: {c0['min']:.2f}, Median: {c0['median']:.2f}, Mean: {c0['mean']:.2f}, P95: {c0['p95']:.2f}, P99: {c0['p99']:.2f}, Max: {c0['max']:.2f}")
    print(f"  Class 1 (Fraud) -> Min: {c1['min']:.2f}, Median: {c1['median']:.2f}, Mean: {c1['mean']:.2f}, P95: {c1['p95']:.2f}, P99: {c1['p99']:.2f}, Max: {c1['max']:.2f}")
    print("-" * 60)
    print("TIME STATISTICS:")
    ts = summary["time_summary"]
    print(f"  Min Time:   {ts['min']:.1f} s")
    print(f"  Max Time:   {ts['max']:.1f} s")
    print(f"  Total Span: {ts['span_hours']:.2f} hours (~{ts['span_hours']/24:.1f} days)")
    print("-" * 60)
    print("TOP 10 CORRELATED FEATURES WITH CLASS:")
    for rank, item in enumerate(summary["top_10_correlated_features"], start=1):
        print(f"  {rank:2d}. {item['feature']:<8} r = {item['correlation']:+.5f} (|r| = {item['abs_correlation']:.5f})")
    print("=" * 60)
    print(f"Summary JSON saved: {summary_path}")
    print(f"Figures saved in:   {config.FIG_DIR}")
    print("=" * 60)

    return summary


def main() -> None:
    """Entrypoint to ensure output directories and run EDA."""
    config.ensure_output_dirs()
    run_eda()


if __name__ == "__main__":
    main()
