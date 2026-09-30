"""Tests verifying behavioral feature calculation and fraud rules engine parity against golden cases."""

import json
from datetime import datetime
from decimal import Decimal
from pathlib import Path
import pytest

from training.features_reference import Txn, compute_features, evaluate_rules

CONTRACTS_DIR = Path(__file__).resolve().parents[2] / "contracts"
GOLDEN_CASES_PATH = CONTRACTS_DIR / "golden_feature_cases_v1.json"


def load_golden_cases():
    with open(GOLDEN_CASES_PATH, "r", encoding="utf-8") as f:
        cases = json.load(f)
    return cases


ALL_CASES = load_golden_cases()


def test_golden_cases_count():
    assert len(ALL_CASES) >= 16, f"Expected at least 16 golden cases, found {len(ALL_CASES)}"


@pytest.mark.parametrize("case", ALL_CASES, ids=[c["name"] for c in ALL_CASES])
def test_golden_case(case):
    case_name = case["name"]
    raw_txns = case["transactions"]
    target_idx = case["target_index"]
    expected_features = case["expected_features"]
    expected_rule_score = case["expected_rule_score"]
    expected_rules = case["expected_rules"]

    txns = [
        Txn(
            account_id=t["account_id"],
            txn_id=t["txn_id"],
            amount=Decimal(t["amount"]),
            device_id=t["device_id"],
            location=t["location"],
            timestamp=datetime.fromisoformat(t["timestamp"]),
        )
        for t in raw_txns
    ]

    current = txns[target_idx]
    history = txns[:target_idx]

    actual_features = compute_features(history, current)
    actual_score, actual_rules = evaluate_rules(actual_features)

    # 1. Assert each of the 11 contract features individually
    feature_names = [
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

    assert list(actual_features.keys()) == feature_names, (
        f"Feature keys or order mismatch in case '{case_name}'. "
        f"Expected {feature_names}, got {list(actual_features.keys())}"
    )

    for fname in feature_names:
        assert fname in expected_features, f"Missing '{fname}' in expected_features for case '{case_name}'"
        exp_val = expected_features[fname]
        act_val = actual_features[fname]

        if isinstance(exp_val, float):
            assert act_val == pytest.approx(exp_val, rel=1e-4, abs=1e-4), (
                f"Feature '{fname}' mismatch in case '{case_name}': expected {exp_val}, got {act_val}"
            )
        else:
            assert act_val == exp_val, (
                f"Feature '{fname}' mismatch in case '{case_name}': expected {exp_val}, got {act_val}"
            )

    # 2. Assert rule_score
    assert actual_score == expected_rule_score, (
        f"rule_score mismatch in case '{case_name}': expected {expected_rule_score}, got {actual_score}"
    )

    # 3. Assert triggered_rules
    assert actual_rules == expected_rules, (
        f"triggered_rules mismatch in case '{case_name}': expected {expected_rules}, got {actual_rules}"
    )
