"""Tests for dataset loading, validation, and stratified splitting."""

from pathlib import Path
import numpy as np
import pandas as pd
import pytest

from training import config
from training.data_loader import (
    EXPECTED_FEATURE_COLUMNS,
    load_deduplicated,
    load_raw,
    load_split,
)
from training.make_split import compute_split_indices, create_split


@pytest.fixture(scope="session", autouse=True)
def ensure_dataset_and_split():
    """Verify raw dataset exists (or skip tests) and ensure split npz exists."""
    if not config.DATA_PATH.exists():
        pytest.skip(f"Dataset CSV not found at {config.DATA_PATH}; skipping split tests.")

    # If the .npz split file is missing, generate it by calling the split function (do not skip)
    split_file = config.SPLIT_DIR / f"split_seed{config.RANDOM_SEED}.npz"
    if not split_file.exists():
        create_split(random_seed=config.RANDOM_SEED, save=True)


def test_config_paths_are_absolute():
    """Assert all path constants in config.py are absolute paths."""
    path_constants = [
        ("ML_SERVICE_ROOT", config.ML_SERVICE_ROOT),
        ("REPO_ROOT", config.REPO_ROOT),
        ("DATA_PATH", config.DATA_PATH),
        ("SPLIT_DIR", config.SPLIT_DIR),
        ("REPORT_DIR", config.REPORT_DIR),
        ("FIG_DIR", config.FIG_DIR),
        ("SPLIT_FILE", config.SPLIT_FILE),
        ("SPLIT_MANIFEST_PATH", config.SPLIT_MANIFEST_PATH),
        ("EDA_SUMMARY_PATH", config.EDA_SUMMARY_PATH),
    ]

    for name, path_val in path_constants:
        assert isinstance(path_val, Path), f"config.{name} is not a Path instance: {path_val}"
        assert path_val.is_absolute(), f"config.{name} is not an absolute path: {path_val}"


def test_raw_dataset_validation():
    """Assert load_raw successfully validates dataset shape, columns, classes, fraud count, and nulls."""
    df = load_raw()
    assert df.shape == (284807, 31)
    expected_cols = ["Time"] + [f"V{i}" for i in range(1, 29)] + ["Amount", "Class"]
    assert list(df.columns) == expected_cols
    assert set(df["Class"].unique()) == {0, 1}
    assert int((df["Class"] == 1).sum()) == 492
    assert df.isna().sum().sum() == 0


def test_validation_failure_conditions(tmp_path: Path):
    """Assert load_raw raises ValueError if validation constraints are violated."""
    # 1. Invalid columns
    bad_cols_csv = tmp_path / "bad_cols.csv"
    pd.DataFrame({"A": [1], "B": [2]}).to_csv(bad_cols_csv, index=False)
    with pytest.raises(ValueError, match="Dataset shape validation failed"):
        load_raw(bad_cols_csv)


def test_split_indices_disjoint_and_complete():
    """Assert train/val/test index sets are pairwise disjoint and their union equals all rows of deduplicated data."""
    split_file = config.SPLIT_DIR / f"split_seed{config.RANDOM_SEED}.npz"
    data = np.load(split_file)
    train_idx = set(data["train_idx"].tolist())
    val_idx = set(data["val_idx"].tolist())
    test_idx = set(data["test_idx"].tolist())

    df_dedup = load_deduplicated()
    total_rows = len(df_dedup)

    # Disjoint checks
    assert train_idx.isdisjoint(val_idx), "train_idx and val_idx must be disjoint"
    assert train_idx.isdisjoint(test_idx), "train_idx and test_idx must be disjoint"
    assert val_idx.isdisjoint(test_idx), "val_idx and test_idx must be disjoint"

    # Union check
    union_idx = train_idx | val_idx | test_idx
    assert len(union_idx) == total_rows, f"Union count {len(union_idx)} does not match deduplicated rows {total_rows}"
    assert union_idx == set(range(total_rows)), "Union of indices does not cover the complete range [0, N)"


def test_split_sizes_proportions():
    """Assert split sizes are within 1 row of 70/15/15 of the deduplicated data."""
    split_file = config.SPLIT_DIR / f"split_seed{config.RANDOM_SEED}.npz"
    data = np.load(split_file)
    df_dedup = load_deduplicated()
    total_rows = len(df_dedup)

    expected_train = total_rows * config.TRAIN_FRAC
    expected_val = total_rows * config.VAL_FRAC
    expected_test = total_rows * config.TEST_FRAC

    actual_train = len(data["train_idx"])
    actual_val = len(data["val_idx"])
    actual_test = len(data["test_idx"])

    assert abs(actual_train - expected_train) <= 1.0, (
        f"Train size {actual_train} differs from expected {expected_train:.1f} by > 1 row"
    )
    assert abs(actual_val - expected_val) <= 1.0, (
        f"Val size {actual_val} differs from expected {expected_val:.1f} by > 1 row"
    )
    assert abs(actual_test - expected_test) <= 1.0, (
        f"Test size {actual_test} differs from expected {expected_test:.1f} by > 1 row"
    )


def test_split_fraud_percentage_stratification():
    """Assert fraud percentage in each split is within 0.05 percentage points of overall fraud percentage."""
    split_file = config.SPLIT_DIR / f"split_seed{config.RANDOM_SEED}.npz"
    data = np.load(split_file)
    df_dedup = load_deduplicated()

    overall_fraud_pct = float((df_dedup["Class"] == 1).mean() * 100)

    for name in ["train", "val", "test"]:
        idx = data[f"{name}_idx"]
        split_fraud_pct = float((df_dedup.loc[idx, "Class"] == 1).mean() * 100)
        diff = abs(split_fraud_pct - overall_fraud_pct)
        assert diff <= 0.05, (
            f"{name} fraud pct ({split_fraud_pct:.5f}%) differs from overall "
            f"({overall_fraud_pct:.5f}%) by {diff:.5f} pp (threshold: 0.05 pp)"
        )


def test_split_minimum_fraud_rows():
    """Assert each split contains at least 30 fraud rows."""
    split_file = config.SPLIT_DIR / f"split_seed{config.RANDOM_SEED}.npz"
    data = np.load(split_file)
    df_dedup = load_deduplicated()

    for name in ["train", "val", "test"]:
        idx = data[f"{name}_idx"]
        fraud_count = int((df_dedup.loc[idx, "Class"] == 1).sum())
        assert fraud_count >= 30, f"{name} split contains only {fraud_count} fraud rows (< 30)"


def test_split_reproducibility():
    """Assert running the split logic twice with the same seed gives identical indices."""
    df_dedup = load_deduplicated()

    train_1, val_1, test_1 = compute_split_indices(df_dedup, random_seed=config.RANDOM_SEED)
    train_2, val_2, test_2 = compute_split_indices(df_dedup, random_seed=config.RANDOM_SEED)

    assert np.array_equal(train_1, train_2), "Train indices differ across runs with same seed"
    assert np.array_equal(val_1, val_2), "Val indices differ across runs with same seed"
    assert np.array_equal(test_1, test_2), "Test indices differ across runs with same seed"


def test_load_split_feature_columns_and_contract():
    """Assert load_split returns X frames with exactly 30 feature columns (no Class column) and Series for y."""
    assert "The test split must never be used for fitting, scaling, resampling, feature selection, or threshold/hyperparameter tuning." in load_split.__doc__

    X_train, y_train, X_val, y_val, X_test, y_test = load_split()

    for split_name, (X, y) in [("train", (X_train, y_train)), ("val", (X_val, y_val)), ("test", (X_test, y_test))]:
        assert isinstance(X, pd.DataFrame), f"X_{split_name} must be a DataFrame"
        assert isinstance(y, pd.Series), f"y_{split_name} must be a Series"
        assert list(X.columns) == EXPECTED_FEATURE_COLUMNS, f"X_{split_name} columns mismatch"
        assert len(X.columns) == 30, f"X_{split_name} must have exactly 30 columns"
        assert "Class" not in X.columns, f"X_{split_name} must not contain target column 'Class'"
        assert y.name == "Class", f"y_{split_name} series must be named 'Class'"
        assert len(X) == len(y), f"X and y lengths mismatch for {split_name}"


def test_no_duplicated_rows_across_splits():
    """Assert no duplicated rows appear across different splits (when DEDUPLICATE is True)."""
    if not config.DEDUPLICATE:
        pytest.skip("Deduplication is disabled in config.")

    X_train, y_train, X_val, y_val, X_test, y_test = load_split()

    df_train = X_train.assign(Class=y_train)
    df_val = X_val.assign(Class=y_val)
    df_test = X_test.assign(Class=y_test)

    combined = pd.concat([df_train, df_val, df_test], ignore_index=True)
    duplicate_count = int(combined.duplicated().sum())

    assert duplicate_count == 0, f"Found {duplicate_count} duplicated rows across splits"
