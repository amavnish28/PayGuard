"""Tests for synthetic dataset generator and contract conformance."""

from datetime import datetime, timedelta
import json
from pathlib import Path
import pytest

from training.features_reference import Txn, compute_features, evaluate_rules
from training.synthetic_generator import (
    CONTRACT_FEATURE_NAMES,
    generate_synthetic_data,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO_ROOT / "contracts" / "feature_schema_v1.json"


@pytest.fixture(scope="module")
def sample_dataset_n50():
    """Generate deterministic n_accounts=50 dataset fixture once for tests."""
    df, summary, split_seed = generate_synthetic_data(n_accounts=50, seed=42)
    return df, summary, split_seed


def test_generator_determinism():
    """Assert generator is deterministic: two runs with n_accounts=50 and same seed give identical output."""
    df1, s1, seed1 = generate_synthetic_data(n_accounts=50, seed=42)
    df2, s2, seed2 = generate_synthetic_data(n_accounts=50, seed=42)

    assert seed1 == seed2
    assert s1 == s2
    # Verify exact equality of all rows, columns, and values
    assert df1.equals(df2)


def test_every_feature_value_within_schema_contract(sample_dataset_n50):
    """Assert every feature value lies within the ranges/allowed values in contracts/feature_schema_v1.json."""
    df, _, _ = sample_dataset_n50

    with open(SCHEMA_PATH, "r", encoding="utf-8") as f:
        schema = json.load(f)

    for feat in schema["features"]:
        name = feat["name"]
        assert name in df.columns, f"Feature '{name}' missing from generated DataFrame"

        col = df[name]

        # Check min boundary
        if "min" in feat:
            min_val = feat["min"]
            assert (col >= min_val).all(), f"Feature '{name}' has values < {min_val}: {col[col < min_val]}"

        # Check max boundary
        if "max" in feat:
            max_val = feat["max"]
            assert (col <= max_val).all(), f"Feature '{name}' has values > {max_val}: {col[col > max_val]}"

        # Check allowed discrete values
        if "allowed" in feat:
            allowed_set = set(feat["allowed"])
            actual_set = set(col.unique())
            assert actual_set.issubset(allowed_set), (
                f"Feature '{name}' has disallowed values: {actual_set - allowed_set}"
            )


def test_csv_feature_columns_match_contract_order(sample_dataset_n50):
    """Assert CSV feature columns match the contract's 11 names in order."""
    df, _, _ = sample_dataset_n50

    with open(SCHEMA_PATH, "r", encoding="utf-8") as f:
        schema = json.load(f)

    expected_feature_names = [f["name"] for f in schema["features"]]
    assert CONTRACT_FEATURE_NAMES == expected_feature_names

    # Check order in df
    cols = list(df.columns)
    ts_idx = cols.index("timestamp")
    feature_slice = cols[ts_idx + 1 : ts_idx + 1 + 11]
    assert feature_slice == expected_feature_names


def test_labels_independent_of_rules(sample_dataset_n50):
    """Assert fraud rows exist with zero rules triggered, and legit rows exist with >= 1 rule triggered."""
    df, _, _ = sample_dataset_n50

    has_rules = df["rules_triggered"].apply(len) > 0

    fraud_no_rules = df[(df["is_fraud"] == 1) & (~has_rules)]
    legit_with_rules = df[(df["is_fraud"] == 0) & has_rules]

    assert len(fraud_no_rules) > 0, "Expected fraud rows with zero rules triggered (e.g. SUBTLE/STEALTH)"
    assert len(legit_with_rules) > 0, "Expected legit rows with >= 1 rule triggered (e.g. noise/travel/burst)"


def test_no_fraud_row_before_tenth_transaction(sample_dataset_n50):
    """Assert no fraud row occurs before its account's 10th transaction."""
    df, _, _ = sample_dataset_n50

    for acc_id, group in df.groupby("account_id", sort=False):
        fraud_positions = [i for i, is_fr in enumerate(group["is_fraud"]) if is_fr == 1]
        for pos in fraud_positions:
            assert pos >= 10, (
                f"Account {acc_id} has fraud at transaction index {pos} (< 10)"
            )


def test_splits_are_disjoint_by_account(sample_dataset_n50):
    """Assert splits are disjoint by account_id."""
    df, _, _ = sample_dataset_n50

    train_accs = set(df[df["split"] == "train"]["account_id"])
    val_accs = set(df[df["split"] == "val"]["account_id"])
    test_accs = set(df[df["split"] == "test"]["account_id"])

    assert train_accs.isdisjoint(val_accs), "Train and Val splits share accounts"
    assert train_accs.isdisjoint(test_accs), "Train and Test splits share accounts"
    assert val_accs.isdisjoint(test_accs), "Val and Test splits share accounts"

    all_accs = set(df["account_id"])
    assert (train_accs | val_accs | test_accs) == all_accs


def test_timestamps_are_valid_and_timezone_aware(sample_dataset_n50):
    """Assert timestamps are valid timezone-aware values with +05:30 offset."""
    df, _, _ = sample_dataset_n50

    for ts_str in df["timestamp"]:
        dt = datetime.fromisoformat(ts_str)
        assert dt.tzinfo is not None, f"Timestamp is naive: {ts_str}"
        assert dt.utcoffset() == timedelta(hours=5, minutes=30), (
            f"Timestamp offset is not +05:30: {ts_str}"
        )


def test_arrival_order_preserved_exactly(sample_dataset_n50):
    """Assert arrival order is preserved exactly in output without timestamp sorting."""
    df, _, _ = sample_dataset_n50

    # Account ACC_000008 (acc_idx = 7) contains a deliberately delayed arrival transaction at index 5
    acc8 = df[df["account_id"] == "ACC_000008"].reset_index(drop=True)
    assert len(acc8) >= 6

    ts_k4 = datetime.fromisoformat(acc8.loc[4, "timestamp"])
    ts_k5 = datetime.fromisoformat(acc8.loc[5, "timestamp"])

    # Transaction 5 has an earlier timestamp than transaction 4
    assert ts_k5 < ts_k4, f"Expected non-monotonic timestamp for ACC_000008: ts4={ts_k4}, ts5={ts_k5}"

    # In output, row 4 is followed by row 5 (arrival order preserved)
    assert acc8.loc[4, "txn_id"] == "TXN_ACC_000008_0005"
    assert acc8.loc[5, "txn_id"] == "TXN_ACC_000008_0006"


def test_no_feature_calculation_after_timestamp_sorting(sample_dataset_n50):
    """Assert by inspection/construction that no feature calculation is performed after timestamp sorting.

    For ACC_000008, transaction 4 arrived with timestamp T4, and transaction 5 arrived with
    timestamp T5 = T4 - 10 minutes.
    In arrival order, T4's history does not contain T5.
    When T5 arrives, T4 has a later timestamp (T4 > T5), so T4 is excluded from T5's valid history.
    Neither transaction counted the other.
    If sorting by timestamp had occurred, T5 would precede T4, and T4 would have included T5.
    """
    df, _, _ = sample_dataset_n50
    acc8 = df[df["account_id"] == "ACC_000008"].reset_index(drop=True)

    # Reconstruct history up to transaction 3, 4, 5
    history_before_4 = [
        Txn(
            account_id=acc8.loc[i, "account_id"],
            txn_id=acc8.loc[i, "txn_id"],
            amount=acc8.loc[i, "amount"],
            device_id=acc8.loc[i, "new_device"], # dummy
            location=acc8.loc[i, "new_location"], # dummy
            timestamp=datetime.fromisoformat(acc8.loc[i, "timestamp"]),
        )
        for i in range(4)
    ]

    # Verify transaction 4's average was computed only on history_before_4 (txns 0..3)
    avg_0_to_3 = round(sum(acc8.loc[i, "amount"] for i in range(4)) / 4.0, 2)
    assert acc8.loc[4, "account_avg_amount"] == pytest.approx(avg_0_to_3, abs=0.01)

    # When transaction 5 is evaluated, transaction 4 is excluded from valid history because ts4 > ts5
    # So transaction 5's average must ALSO be based only on txns 0..3 (excluding transaction 4)
    assert acc8.loc[5, "account_avg_amount"] == pytest.approx(avg_0_to_3, abs=0.01)
