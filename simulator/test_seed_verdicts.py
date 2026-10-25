"""Tests for simulator ground-truth verdict seeding logic (seed_verdicts.py).

No network calls required. Run with the ml-service venv:
    pytest simulator/test_seed_verdicts.py
"""

from __future__ import annotations

import json
from pathlib import Path
import sys
from typing import Any, Dict

import pytest

# Ensure simulator directory is on sys.path
SIMULATOR_DIR = Path(__file__).resolve().parent
if str(SIMULATOR_DIR) not in sys.path:
    sys.path.insert(0, str(SIMULATOR_DIR))

from seed_verdicts import (
    build_txn_log_index,
    format_verdict_comment,
    get_verdict_label,
    match_alert_to_log,
    parse_transaction_id,
    process_alerts,
    validate_legit_count_against_report,
)


@pytest.fixture
def fake_log() -> list[Dict[str, Any]]:
    """Return a fake simulator transaction log."""
    return [
        {
            "seq": 101,
            "accountId": "SIM-ACC-0001",
            "scenario": "CARD_TESTING",
            "amount": 50.00,
            "currency": "INR",
            "deviceId": "DEVICE-SIM-ACC-0001-01",
            "location": "Mumbai",
            "merchantType": "RETAIL",
            "transactionTimestamp": "2026-10-01T10:00:00+05:30",
        },
        {
            "seq": 102,
            "accountId": "SIM-ACC-0002",
            "scenario": "LEGIT",
            "amount": 1250.75,
            "currency": "INR",
            "deviceId": "DEVICE-SIM-ACC-0002-01",
            "location": "Delhi",
            "merchantType": "GROCERY",
            "transactionTimestamp": "2026-10-01T10:05:00+05:30",
        },
        {
            "seq": 103,
            "accountId": "SIM-ACC-0003",
            "scenario": "ACCOUNT_TAKEOVER",
            "amount": 25000.00,
            "currency": "INR",
            "deviceId": "DEVICE-ATO-9999",
            "location": "Kolkata",
            "merchantType": "ELECTRONICS",
            "transactionTimestamp": "2026-10-01T10:10:00+05:30",
        },
    ]


@pytest.fixture
def log_index(fake_log: list[Dict[str, Any]]) -> dict:
    return build_txn_log_index(fake_log)


def test_parse_transaction_id():
    """Verify transactionId parsing into (accountId, seq)."""
    assert parse_transaction_id("SIM-6adb3870-SIM-ACC-0006-000212") == ("SIM-ACC-0006", 212)
    assert parse_transaction_id("SIM-run1-SIM-ACC-0001-000101") == ("SIM-ACC-0001", 101)
    assert parse_transaction_id("SIM-run2-CUSTOM_ACC-000042") == ("CUSTOM_ACC", 42)
    assert parse_transaction_id("invalid-format") is None
    assert parse_transaction_id(None) is None


def test_card_testing_review_alert_gets_fraud(log_index: dict):
    """(a) A REVIEW alert from CARD_TESTING gets FRAUD verdict.

    Must fail against the old decision-based behavior (which labeled REVIEW -> LEGITIMATE).
    """
    review_alert = {
        "id": "alert-card-testing-01",
        "transactionId": "SIM-testrun-SIM-ACC-0001-000101",
        "amount": 50.00,
        "decision": "REVIEW",
        "severity": "LOW",
    }

    matched_log = match_alert_to_log(review_alert, log_index)
    assert matched_log is not None
    assert matched_log["scenario"] == "CARD_TESTING"

    verdict_label = get_verdict_label(matched_log["scenario"])
    assert verdict_label == "FRAUD"

    # End-to-end processing check
    matched_verdicts, unmatched, scen_x_label, scen_x_dec = process_alerts([review_alert], log_index)
    assert len(matched_verdicts) == 1
    assert unmatched == 0
    assert matched_verdicts[0]["verdict"] == "FRAUD"
    assert matched_verdicts[0]["decision"] == "REVIEW"
    assert scen_x_label["CARD_TESTING"]["FRAUD"] == 1
    assert scen_x_label["CARD_TESTING"]["LEGITIMATE"] == 0


def test_legit_block_alert_gets_legitimate(log_index: dict):
    """(b) A BLOCK alert from LEGIT gets LEGITIMATE verdict.

    Must fail against the old decision-based behavior (which labeled BLOCK -> FRAUD).
    """
    block_alert = {
        "id": "alert-legit-block-02",
        "transactionId": "SIM-testrun-SIM-ACC-0002-000102",
        "amount": 1250.75,
        "decision": "BLOCK",
        "severity": "HIGH",
    }

    matched_log = match_alert_to_log(block_alert, log_index)
    assert matched_log is not None
    assert matched_log["scenario"] == "LEGIT"

    verdict_label = get_verdict_label(matched_log["scenario"])
    assert verdict_label == "LEGITIMATE"

    # End-to-end processing check
    matched_verdicts, unmatched, scen_x_label, scen_x_dec = process_alerts([block_alert], log_index)
    assert len(matched_verdicts) == 1
    assert unmatched == 0
    assert matched_verdicts[0]["verdict"] == "LEGITIMATE"
    assert matched_verdicts[0]["decision"] == "BLOCK"
    assert scen_x_label["LEGIT"]["LEGITIMATE"] == 1
    assert scen_x_label["LEGIT"]["FRAUD"] == 0


def test_unmatched_alert_is_skipped(log_index: dict):
    """(c) An unmatched alert is skipped and counted, never falling back to decision.

    Must fail against the old decision-based behavior (which never skipped and fell back).
    """
    unmatched_alerts = [
        # Non-existent seq / account
        {
            "id": "alert-unmatched-01",
            "transactionId": "SIM-testrun-SIM-ACC-0009-000999",
            "amount": 100.00,
            "decision": "BLOCK",
            "severity": "HIGH",
        },
        # Exists in log but amount does NOT match
        {
            "id": "alert-unmatched-02",
            "transactionId": "SIM-testrun-SIM-ACC-0001-000101",
            "amount": 99999.00,  # real amount is 50.00
            "decision": "REVIEW",
            "severity": "LOW",
        },
    ]

    matched_verdicts, unmatched_count, _, _ = process_alerts(unmatched_alerts, log_index)
    assert len(matched_verdicts) == 0
    assert unmatched_count == 2


def test_comment_contains_scenario_card_testing(log_index: dict):
    """(d) The comment contains 'scenario: CARD_TESTING'.

    Exact format:
    'Auto-verdict from simulator ground truth (scenario: <SCENARIO>) - verification tool only, not a real analyst judgment.'
    Must fail against the old behavior (which produced comments with 'scenario FRAUD_HEURISTIC' or 'scenario LEGIT').
    """
    card_testing_alert = {
        "id": "alert-card-testing-03",
        "transactionId": "SIM-testrun-SIM-ACC-0001-000101",
        "amount": 50.00,
        "decision": "REVIEW",
        "severity": "MEDIUM",
    }

    matched_verdicts, _, _, _ = process_alerts([card_testing_alert], log_index)
    assert len(matched_verdicts) == 1
    comment = matched_verdicts[0]["comment"]

    expected_exact_comment = (
        "Auto-verdict from simulator ground truth (scenario: CARD_TESTING) - "
        "verification tool only, not a real analyst judgment."
    )
    assert comment == expected_exact_comment
    assert "scenario: CARD_TESTING" in comment


def test_confirm_gate_refuses_when_legit_count_differs(tmp_path: Path):
    """With --confirm, refuse to proceed if LEGIT alert count differs from simulator report."""
    fake_report = {
        "scenario_breakdown": {
            "LEGIT": {
                "sent": 8857,
                "flagged_review_or_block": 3,
            }
        }
    }
    report_file = tmp_path / "last_run_report.json"
    report_file.write_text(json.dumps(fake_report), encoding="utf-8")

    # When legit_alerts_count matches (3 == 3) -> True
    assert validate_legit_count_against_report(report_file, 3) is True

    # When legit_alerts_count differs (e.g. 2 or 4 != 3) -> False
    assert validate_legit_count_against_report(report_file, 2) is False
    assert validate_legit_count_against_report(report_file, 4) is False
