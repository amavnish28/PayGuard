"""Data loading, integrity validation, deduplication, and split reconstruction."""

from pathlib import Path
from typing import Tuple, Union
import numpy as np
import pandas as pd

from training import config

EXPECTED_FEATURE_COLUMNS = ["Time"] + [f"V{i}" for i in range(1, 29)] + ["Amount"]
EXPECTED_COLUMNS = EXPECTED_FEATURE_COLUMNS + ["Class"]
EXPECTED_SHAPE = (284807, 31)
EXPECTED_CLASSES = {0, 1}
EXPECTED_FRAUD_COUNT = 492


def load_raw(csv_path: Union[Path, str, None] = None) -> pd.DataFrame:
    """Read the raw Credit Card Fraud dataset CSV and perform strict integrity validation.

    Validations enforced:
      - shape == (284807, 31)
      - columns are Time, V1..V28, Amount, Class in that order
      - Class values are only {0, 1}
      - Class == 1 count == 492
      - no missing values

    Raises:
      FileNotFoundError: If the CSV file does not exist.
      ValueError: If any integrity assertion fails, reporting actual values.
    """
    path = Path(csv_path) if csv_path is not None else config.DATA_PATH

    if not path.exists():
        raise FileNotFoundError(f"Credit card dataset CSV not found at: {path}")

    df = pd.read_csv(path)

    # 1. Shape validation
    if df.shape != EXPECTED_SHAPE:
        raise ValueError(
            f"Dataset shape validation failed: expected {EXPECTED_SHAPE}, got {df.shape}"
        )

    # 2. Column names and ordering validation
    actual_columns = list(df.columns)
    if actual_columns != EXPECTED_COLUMNS:
        raise ValueError(
            f"Dataset columns validation failed.\nExpected: {EXPECTED_COLUMNS}\nGot: {actual_columns}"
        )

    # 3. Class value domain validation
    actual_classes = set(df["Class"].unique())
    if actual_classes != EXPECTED_CLASSES:
        raise ValueError(
            f"Dataset Class values validation failed: expected {EXPECTED_CLASSES}, got {actual_classes}"
        )

    # 4. Class == 1 count validation
    actual_fraud_count = int((df["Class"] == 1).sum())
    if actual_fraud_count != EXPECTED_FRAUD_COUNT:
        raise ValueError(
            f"Dataset Class==1 count validation failed: expected {EXPECTED_FRAUD_COUNT}, got {actual_fraud_count}"
        )

    # 5. Missing values validation
    missing_count = int(df.isna().sum().sum())
    if missing_count != 0:
        missing_by_col = df.isna().sum()[lambda x: x > 0].to_dict()
        raise ValueError(
            f"Dataset missing values validation failed: expected 0 missing values, got {missing_count}. "
            f"Missing by column: {missing_by_col}"
        )

    return df


def load_deduplicated(csv_path: Union[Path, str, None] = None) -> pd.DataFrame:
    """Return the dataset after optional deduplication (per config.DEDUPLICATE) with a reset index.

    This is the single definition of 'the dataset' used by the split and by load_split().
    """
    df = load_raw(csv_path)
    if config.DEDUPLICATE:
        df = df.drop_duplicates().reset_index(drop=True)
    else:
        df = df.reset_index(drop=True)
    return df


def load_split(
    split_path: Union[Path, str, None] = None,
    csv_path: Union[Path, str, None] = None,
) -> Tuple[pd.DataFrame, pd.Series, pd.DataFrame, pd.Series, pd.DataFrame, pd.Series]:
    """The test split must never be used for fitting, scaling, resampling, feature selection, or threshold/hyperparameter tuning.

    Loads the fixed train/val/test splits using stored index arrays and
    reconstructs feature dataframes and target series via load_deduplicated().

    Returns:
        (X_train, y_train, X_val, y_val, X_test, y_test)
        - X dataframes contain exactly 30 feature columns: Time, V1..V28, Amount
        - y series contain the binary target: Class
    """
    target_split_path = Path(split_path) if split_path is not None else config.SPLIT_FILE

    if not target_split_path.exists():
        raise FileNotFoundError(
            f"Split file not found at: {target_split_path}. "
            "Run training.make_split first to generate the split indices."
        )

    data = np.load(target_split_path)
    train_idx = data["train_idx"]
    val_idx = data["val_idx"]
    test_idx = data["test_idx"]

    df = load_deduplicated(csv_path)

    X_train = df.loc[train_idx, EXPECTED_FEATURE_COLUMNS].reset_index(drop=True)
    y_train = df.loc[train_idx, "Class"].reset_index(drop=True)

    X_val = df.loc[val_idx, EXPECTED_FEATURE_COLUMNS].reset_index(drop=True)
    y_val = df.loc[val_idx, "Class"].reset_index(drop=True)

    X_test = df.loc[test_idx, EXPECTED_FEATURE_COLUMNS].reset_index(drop=True)
    y_test = df.loc[test_idx, "Class"].reset_index(drop=True)

    return X_train, y_train, X_val, y_val, X_test, y_test
