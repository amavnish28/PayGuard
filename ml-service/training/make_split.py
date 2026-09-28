"""Fixed stratified train/val/test splitting for PayGuard ML service."""

import json
from pathlib import Path
from typing import Dict, Tuple
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split

from training import config
from training.data_loader import load_deduplicated, load_raw


def compute_split_indices(
    df: pd.DataFrame,
    random_seed: int = config.RANDOM_SEED,
    train_frac: float = config.TRAIN_FRAC,
    val_frac: float = config.VAL_FRAC,
    test_frac: float = config.TEST_FRAC,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Compute stratified train/val/test index arrays for a given dataframe and seed.

    Stratification is performed on df['Class'].
    First split holds out the test set (test_frac).
    Second split splits the remainder into train and val so the final
    fractions are train_frac / val_frac / test_frac of the input dataset.
    """
    n = len(df)
    indices = np.arange(n)

    # 1. Hold out test split
    remaining_idx, test_idx = train_test_split(
        indices,
        test_size=test_frac,
        random_state=random_seed,
        stratify=df["Class"],
    )

    # 2. Split remainder into train and validation
    val_relative_frac = val_frac / (train_frac + val_frac)
    train_idx, val_idx = train_test_split(
        remaining_idx,
        test_size=val_relative_frac,
        random_state=random_seed,
        stratify=df.loc[remaining_idx, "Class"],
    )

    return (
        np.array(train_idx, dtype=np.int64),
        np.array(val_idx, dtype=np.int64),
        np.array(test_idx, dtype=np.int64),
    )


def create_split(
    random_seed: int = config.RANDOM_SEED,
    save: bool = True,
) -> Dict:
    """Execute deduplication and stratified 70/15/15 splitting.

    Saves:
      - SPLIT_DIR / split_seed{random_seed}.npz containing train_idx, val_idx, test_idx
      - REPORT_DIR / split_manifest.json containing split metadata and statistics

    Returns:
      Manifest dictionary containing split details.
    """
    if save:
        config.ensure_output_dirs()

    # Load raw and deduplicated data
    raw_df = load_raw()
    raw_rows = int(len(raw_df))
    raw_fraud = int((raw_df["Class"] == 1).sum())
    raw_fraud_pct = float(raw_fraud / raw_rows * 100)

    dedup_df = load_deduplicated()
    dedup_rows = int(len(dedup_df))
    dedup_fraud = int((dedup_df["Class"] == 1).sum())
    dedup_fraud_pct = float(dedup_fraud / dedup_rows * 100)

    duplicate_rows = raw_rows - dedup_rows
    duplicate_fraud = raw_fraud - dedup_fraud

    # Compute stratified splits
    train_idx, val_idx, test_idx = compute_split_indices(
        dedup_df,
        random_seed=random_seed,
        train_frac=config.TRAIN_FRAC,
        val_frac=config.VAL_FRAC,
        test_frac=config.TEST_FRAC,
    )

    # Compute statistics for each split
    splits_info = {}
    for name, idx in [("train", train_idx), ("val", val_idx), ("test", test_idx)]:
        sub_df = dedup_df.iloc[idx]
        split_rows = int(len(sub_df))
        split_fraud = int((sub_df["Class"] == 1).sum())
        split_fraud_pct = float(split_fraud / split_rows * 100)
        time_min = float(sub_df["Time"].min())
        time_max = float(sub_df["Time"].max())

        splits_info[name] = {
            "rows": split_rows,
            "fraud_count": split_fraud,
            "fraud_percentage": split_fraud_pct,
            "time_min": time_min,
            "time_max": time_max,
        }

    manifest = {
        "seed": random_seed,
        "fractions": {
            "train": config.TRAIN_FRAC,
            "val": config.VAL_FRAC,
            "test": config.TEST_FRAC,
        },
        "before_dedup": {
            "rows": raw_rows,
            "fraud_count": raw_fraud,
            "fraud_percentage": raw_fraud_pct,
        },
        "after_dedup": {
            "rows": dedup_rows,
            "fraud_count": dedup_fraud,
            "fraud_percentage": dedup_fraud_pct,
            "duplicate_rows_removed": duplicate_rows,
            "duplicate_fraud_removed": duplicate_fraud,
        },
        "splits": splits_info,
        # Flat aliases for easy lookup
        "before_dedup_rows": raw_rows,
        "before_dedup_fraud_count": raw_fraud,
        "after_dedup_rows": dedup_rows,
        "after_dedup_fraud_count": dedup_fraud,
        "train_rows": splits_info["train"]["rows"],
        "train_fraud_count": splits_info["train"]["fraud_count"],
        "train_fraud_percentage": splits_info["train"]["fraud_percentage"],
        "val_rows": splits_info["val"]["rows"],
        "val_fraud_count": splits_info["val"]["fraud_count"],
        "val_fraud_percentage": splits_info["val"]["fraud_percentage"],
        "test_rows": splits_info["test"]["rows"],
        "test_fraud_count": splits_info["test"]["fraud_count"],
        "test_fraud_percentage": splits_info["test"]["fraud_percentage"],
    }

    if save:
        npz_path = config.SPLIT_DIR / f"split_seed{random_seed}.npz"
        np.savez(npz_path, train_idx=train_idx, val_idx=val_idx, test_idx=test_idx)

        manifest_path = config.REPORT_DIR / "split_manifest.json"
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2)

    return manifest


def main() -> None:
    """Entrypoint to ensure output directories and generate split artifacts."""
    config.ensure_output_dirs()
    manifest = create_split()
    print("=== PayGuard Fixed Stratified Split Completed ===")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
