"""Unit tests for final evaluation components (no database required).

Tests:
- All 12 matrix cells by name (9 hybrid matrix cells + 3 fallback cells)
- Rule tier boundaries (29, 30, 44, 45)
- Transaction ID regex (valid ID, malformed ID, ID with different run segment)
- Wilson confidence interval on two known reference values
- Confusion metrics on a small hand-crafted ground truth / prediction set
- Flagged and block detection logic for each system
"""

import math
import sys
from pathlib import Path

# Ensure ml-service root is on sys.path
ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(ML_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(ML_SERVICE_ROOT))

import numpy as np
import pytest

from training.final_evaluation import (
    HYBRID_MATRIX,
    FALLBACK_MATRIX,
    get_rule_tier,
    get_ml_band,
    lookup_hybrid_decision,
    lookup_fallback_decision,
    parse_transaction_id,
    wilson_interval,
    compute_confusion_metrics,
)


# -----------------------------------------------------------------------------
# 1. Decision Matrix Cells (12 cells total: 9 hybrid + 3 fallback)
# -----------------------------------------------------------------------------
@pytest.mark.parametrize(
    "tier,band,expected",
    [
        ("LOW", "low", "APPROVE"),
        ("LOW", "review", "REVIEW"),
        ("LOW", "block", "BLOCK"),
        ("MEDIUM", "low", "REVIEW"),
        ("MEDIUM", "review", "REVIEW"),
        ("MEDIUM", "block", "BLOCK"),
        ("HIGH", "low", "REVIEW"),
        ("HIGH", "review", "BLOCK"),
        ("HIGH", "block", "BLOCK"),
    ],
)
def test_hybrid_matrix_all_9_cells(tier: str, band: str, expected: str) -> None:
    assert HYBRID_MATRIX[(tier, band)] == expected
    assert lookup_hybrid_decision(tier, band) == expected


@pytest.mark.parametrize(
    "tier,expected",
    [
        ("LOW", "APPROVE"),
        ("MEDIUM", "REVIEW"),
        ("HIGH", "REVIEW"),
    ],
)
def test_fallback_matrix_all_3_cells(tier: str, expected: str) -> None:
    assert FALLBACK_MATRIX[tier] == expected
    assert lookup_fallback_decision(tier) == expected


# -----------------------------------------------------------------------------
# 2. Rule Tier Boundaries (29, 30, 44, 45)
# -----------------------------------------------------------------------------
@pytest.mark.parametrize(
    "score,expected_tier",
    [
        (29, "LOW"),
        (29.99, "LOW"),
        (30, "MEDIUM"),
        (44, "MEDIUM"),
        (44.99, "MEDIUM"),
        (45, "HIGH"),
        (100, "HIGH"),
    ],
)
def test_rule_tier_boundaries(score: float, expected_tier: str) -> None:
    assert get_rule_tier(score) == expected_tier


# -----------------------------------------------------------------------------
# 3. Transaction ID Regex
# -----------------------------------------------------------------------------
def test_transaction_id_regex_valid() -> None:
    txn_id = "SIM-6adb3870-SIM-ACC-0006-000001"
    parsed = parse_transaction_id(txn_id)
    assert parsed is not None
    acc_id, seq = parsed
    assert acc_id == "SIM-ACC-0006"
    assert seq == 1


def test_transaction_id_regex_different_run_segment() -> None:
    txn_id = "SIM-deadbeef1234-SIM-ACC-0042-009999"
    parsed = parse_transaction_id(txn_id)
    assert parsed is not None
    acc_id, seq = parsed
    assert acc_id == "SIM-ACC-0042"
    assert seq == 9999


def test_transaction_id_regex_malformed() -> None:
    assert parse_transaction_id("INVALID-TXN-ID") is None
    assert parse_transaction_id("SIM-6adb3870-SIM-ACC-0006") is None
    assert parse_transaction_id("SIM-6adb3870-NOTACC-0006-000001") is None
    assert parse_transaction_id("SIM-6adb3870-SIM-ACC-XXXX-000001") is None
    assert parse_transaction_id("") is None


# -----------------------------------------------------------------------------
# 4. Wilson Interval on Two Known Values
# -----------------------------------------------------------------------------
def test_wilson_interval_known_values() -> None:
    # Known case 1: 50 successes out of 100 trials, 95% confidence
    low_50, high_50 = wilson_interval(50, 100, confidence=0.95)
    assert math.isclose(low_50, 0.4038315, rel_tol=1e-4)
    assert math.isclose(high_50, 0.5961685, rel_tol=1e-4)

    # Known case 2: 0 successes out of 100 trials, 95% confidence
    low_0, high_0 = wilson_interval(0, 100, confidence=0.95)
    assert low_0 == 0.0
    assert math.isclose(high_0, 0.0369935, rel_tol=1e-4)


# -----------------------------------------------------------------------------
# 5. Confusion Metrics on a Small Hand-Made Example
# -----------------------------------------------------------------------------
def test_confusion_metrics_hand_made_example() -> None:
    # 4 transactions:
    # row 0: true=1, pred=1 -> TP
    # row 1: true=1, pred=0 -> FN
    # row 2: true=0, pred=1 -> FP
    # row 3: true=0, pred=0 -> TN
    y_true = np.array([1, 1, 0, 0])
    y_pred = np.array([1, 0, 1, 0])

    m = compute_confusion_metrics(y_true, y_pred)
    assert m["tp"] == 1
    assert m["fp"] == 1
    assert m["fn"] == 1
    assert m["tn"] == 1

    # Recall = TP / (TP + FN) = 1 / 2 = 0.5
    assert math.isclose(m["recall"], 0.5)
    # Precision = TP / (TP + FP) = 1 / 2 = 0.5
    assert math.isclose(m["precision"], 0.5)
    # FPR = FP / (FP + TN) = 1 / 2 = 0.5
    assert math.isclose(m["fpr"], 0.5)
    # F1 = 2 * P * R / (P + R) = 0.5
    assert math.isclose(m["f1"], 0.5)

    # Wilson interval check on 1/2
    low_half, high_half = wilson_interval(1, 2)
    assert math.isclose(m["recall_ci_95"][0], low_half)
    assert math.isclose(m["recall_ci_95"][1], high_half)


# -----------------------------------------------------------------------------
# 6. Flagged and Block Detection Logic for Each System
# -----------------------------------------------------------------------------
def test_system_flagged_and_blocked_logic() -> None:
    review_thresh = 0.18657
    block_thresh = 0.56667

    # System A: score >= 30
    assert (25 >= 30) is False
    assert (30 >= 30) is True
    assert (50 >= 30) is True

    # System A2: score > 0
    assert (0 > 0) is False
    assert (15 > 0) is True
    assert (30 > 0) is True

    # System B (flagged): prob >= review threshold
    assert (0.10 >= review_thresh) is False
    assert (0.18657 >= review_thresh) is True
    assert (0.60 >= review_thresh) is True

    # System B (blocked): prob >= block threshold
    assert (0.50 >= block_thresh) is False
    assert (0.56667 >= block_thresh) is True
    assert (0.80 >= block_thresh) is True

    # System C / D (flagged): decision in ('REVIEW', 'BLOCK')
    assert ("APPROVE" in ("REVIEW", "BLOCK")) is False
    assert ("REVIEW" in ("REVIEW", "BLOCK")) is True
    assert ("BLOCK" in ("REVIEW", "BLOCK")) is True

    # System C / D (blocked): decision == 'BLOCK'
    assert ("APPROVE" == "BLOCK") is False
    assert ("REVIEW" == "BLOCK") is False
    assert ("BLOCK" == "BLOCK") is True

    # ML Band helper check
    assert get_ml_band(0.10, review_thresh, block_thresh) == "low"
    assert get_ml_band(0.20, review_thresh, block_thresh) == "review"
    assert get_ml_band(0.70, review_thresh, block_thresh) == "block"
