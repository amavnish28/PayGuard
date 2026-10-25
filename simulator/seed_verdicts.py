#!/usr/bin/env python3
"""
seed_verdicts.py - Standalone analyst verdict seeding script for PayGuard.

Reads exported transactions from a simulator run (e.g. simulator/last_run_transactions.json),
queries alerts from the Spring Boot backend, matches alerts to the simulated transaction scenarios
using ground truth, and submits deterministic verdicts (FRAUD for fraud scenarios, LEGITIMATE for LEGIT)
via POST /api/v1/alerts/{id}/verdict.

Usage:
    python seed_verdicts.py [--txns-file last_run_transactions.json] [--api-url http://localhost:8080] [--dry-run]
    python seed_verdicts.py --confirm
"""

import argparse
import json
import logging
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import requests

try:
    from dotenv import load_dotenv
except ImportError:
    load_dotenv = None

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("seed_verdicts")

# Retraining gating policy thresholds (locked in PayGuard specification)
MIN_TOTAL_VERDICTS = 20
MIN_PER_CLASS = 5


def load_env_variables() -> None:
    """Load environment variables from .env file if available."""
    script_dir = Path(__file__).resolve().parent
    env_file = script_dir / ".env"
    if env_file.exists() and load_dotenv:
        load_dotenv(dotenv_path=env_file)
    elif load_dotenv:
        load_dotenv()


def login(session: requests.Session, base_url: str, username: str, password: str) -> str:
    """Authenticates against the PayGuard auth endpoint and returns JWT token."""
    login_url = f"{base_url.rstrip('/')}/api/auth/login"
    logger.info(f"Authenticating with {login_url} as '{username}'...")
    try:
        resp = session.post(
            login_url,
            json={"username": username, "password": password},
            timeout=10,
        )
    except requests.RequestException as e:
        logger.error(f"Network error during authentication: {e}")
        sys.exit(1)

    if resp.status_code != 200:
        logger.error(f"Authentication failed [HTTP {resp.status_code}]: {resp.text}")
        sys.exit(1)

    token = resp.json().get("token")
    if not token:
        logger.error("No token received in login response")
        sys.exit(1)

    logger.info("Authentication successful.")
    return token


def fetch_all_alerts(session: requests.Session, base_url: str, headers: Dict[str, str]) -> List[Dict[str, Any]]:
    """Fetch all alerts from Spring Boot across all pages."""
    alerts: List[Dict[str, Any]] = []
    page = 0
    size = 100
    base_endpoint = f"{base_url.rstrip('/')}/api/v1/alerts"

    while True:
        resp = session.get(f"{base_endpoint}?page={page}&size={size}", headers=headers, timeout=15)
        if resp.status_code != 200:
            logger.error(f"Failed to fetch alerts page {page} [HTTP {resp.status_code}]: {resp.text}")
            break

        data = resp.json()
        content = data.get("content", [])
        alerts.extend(content)

        total_pages = data.get("totalPages", 1)
        is_last = data.get("last", True)

        if is_last or (page + 1) >= total_pages:
            break
        page += 1

    return alerts


def fetch_alert_detail(session: requests.Session, base_url: str, headers: Dict[str, str], alert_id: str) -> Optional[Dict[str, Any]]:
    """Fetch detail for a single alert including transaction fields and existing verdict."""
    url = f"{base_url.rstrip('/')}/api/v1/alerts/{alert_id}"
    resp = session.get(url, headers=headers, timeout=10)
    if resp.status_code == 200:
        return resp.json()
    logger.warning(f"Could not fetch detail for alert {alert_id} [HTTP {resp.status_code}]")
    return None


def submit_verdict(session: requests.Session, base_url: str, headers: Dict[str, str], alert_id: str, verdict: str, comment: str) -> bool:
    """Submit a verdict for an alert."""
    url = f"{base_url.rstrip('/')}/api/v1/alerts/{alert_id}/verdict"
    payload = {
        "verdict": verdict,
        "comment": comment,
    }
    resp = session.post(url, json=payload, headers=headers, timeout=10)
    if resp.status_code in (200, 201):
        return True
    elif resp.status_code == 409:
        logger.info(f"Alert {alert_id} was already verdicted (409 Conflict).")
        return False
    else:
        logger.warning(f"Failed to submit verdict for alert {alert_id} [HTTP {resp.status_code}]: {resp.text}")
        return False


def parse_transaction_id(tx_id: Optional[str]) -> Optional[Tuple[str, int]]:
    """Parse accountId and seq from SIM-<run>-<account>-<seq>.

    Example:
        'SIM-6adb3870-SIM-ACC-0006-000212' -> ('SIM-ACC-0006', 212)
    """
    if not tx_id or not isinstance(tx_id, str):
        return None

    # Matches SIM-<run>-(SIM-ACC-\d+)-(\d+)
    m = re.match(r"^SIM-[^-]+-(SIM-ACC-\d+)-(\d+)$", tx_id)
    if m:
        return m.group(1), int(m.group(2))

    # General fallback for arbitrary account formats: SIM-<run>-<account>-<seq>
    parts = tx_id.split("-")
    if len(parts) >= 4 and parts[0] == "SIM" and parts[-1].isdigit():
        seq = int(parts[-1])
        account_id = "-".join(parts[2:-1])
        return account_id, seq

    return None


def build_txn_log_index(transactions: List[Dict[str, Any]]) -> Dict[Tuple[str, int], Dict[str, Any]]:
    """Index transactions log by (accountId, seq)."""
    index: Dict[Tuple[str, int], Dict[str, Any]] = {}
    for tx in transactions:
        acc_id = tx.get("accountId")
        seq = tx.get("seq")
        if acc_id is not None and seq is not None:
            try:
                index[(str(acc_id), int(seq))] = tx
            except (ValueError, TypeError):
                continue
    return index


def match_alert_to_log(
    alert_detail: Dict[str, Any],
    log_index: Dict[Tuple[str, int], Dict[str, Any]],
) -> Optional[Dict[str, Any]]:
    """Match alert detail to its log entry exactly using parsed accountId, seq, and amount.

    Returns the matching log entry or None if no match.
    Never falls back to alert decision, severity, or score.
    """
    tx_id = alert_detail.get("transactionId")
    parsed = parse_transaction_id(tx_id)
    if not parsed:
        return None

    account_id, seq = parsed
    log_entry = log_index.get((account_id, seq))
    if not log_entry:
        return None

    # Confirm amount equals (within 0.01 tolerance for floating point representation)
    alert_amount = alert_detail.get("amount")
    log_amount = log_entry.get("amount")
    if alert_amount is None or log_amount is None:
        return None

    try:
        if abs(float(alert_amount) - float(log_amount)) > 0.01:
            return None
    except (ValueError, TypeError):
        return None

    return log_entry


def get_verdict_label(scenario: str) -> str:
    """Label ONLY from the log's scenario field: LEGIT -> LEGITIMATE, anything else -> FRAUD."""
    return "LEGITIMATE" if scenario == "LEGIT" else "FRAUD"


def format_verdict_comment(scenario: str) -> str:
    """Format verdict comment exactly as specified."""
    return f"Auto-verdict from simulator ground truth (scenario: {scenario}) - verification tool only, not a real analyst judgment."


def validate_legit_count_against_report(report_path: Path, legit_alerts_count: int) -> bool:
    """Validate that the number of LEGIT-scenario alerts matches the report's flagged_review_or_block."""
    if not report_path.exists():
        logger.error(f"Simulator report file not found at '{report_path}'. Refusing to proceed with --confirm.")
        return False
    try:
        with open(report_path, "r", encoding="utf-8") as f:
            report_data = json.load(f)
        expected_legit = (
            report_data.get("scenario_breakdown", {})
            .get("LEGIT", {})
            .get("flagged_review_or_block")
        )
        if expected_legit is None:
            logger.error(f"Could not find scenario_breakdown.LEGIT.flagged_review_or_block in '{report_path}'.")
            return False
        if legit_alerts_count != expected_legit:
            logger.error(
                f"Validation failed: Number of LEGIT-scenario alerts ({legit_alerts_count}) "
                f"differs from simulator report ({expected_legit}). Refusing to proceed."
            )
            return False
        logger.info(
            f"Validation passed: LEGIT-scenario alert count ({legit_alerts_count}) "
            f"matches simulator report ({expected_legit})."
        )
        return True
    except Exception as e:
        logger.error(f"Error reading simulator report '{report_path}': {e}. Refusing to proceed.")
        return False


def print_scenario_tables(
    scenario_x_label: Dict[str, Dict[str, int]],
    scenario_x_decision: Dict[str, Dict[str, int]],
    unmatched_count: int,
) -> None:
    """Print scenario x label table, scenario x decision table, and unmatched count."""
    print()
    print("=" * 65)
    print("SCENARIO x LABEL")
    print("=" * 65)
    print(f"{'Scenario':<25} {'FRAUD':>10} {'LEGITIMATE':>12} {'Total':>8}")
    print("-" * 65)
    tot_fraud = 0
    tot_legit = 0
    tot_all = 0
    for scenario in sorted(scenario_x_label.keys()):
        f_count = scenario_x_label[scenario].get("FRAUD", 0)
        l_count = scenario_x_label[scenario].get("LEGITIMATE", 0)
        s_total = f_count + l_count
        tot_fraud += f_count
        tot_legit += l_count
        tot_all += s_total
        print(f"{scenario:<25} {f_count:>10} {l_count:>12} {s_total:>8}")
    print("-" * 65)
    print(f"{'TOTAL':<25} {tot_fraud:>10} {tot_legit:>12} {tot_all:>8}")
    print()

    print("=" * 65)
    print("SCENARIO x ALERT DECISION")
    print("=" * 65)
    print(f"{'Scenario':<25} {'BLOCK':>10} {'REVIEW':>10} {'Total':>8}")
    print("-" * 65)
    tot_block = 0
    tot_review = 0
    tot_dec_all = 0
    for scenario in sorted(scenario_x_decision.keys()):
        b_count = scenario_x_decision[scenario].get("BLOCK", 0)
        r_count = scenario_x_decision[scenario].get("REVIEW", 0)
        s_total = b_count + r_count
        tot_block += b_count
        tot_review += r_count
        tot_dec_all += s_total
        print(f"{scenario:<25} {b_count:>10} {r_count:>10} {s_total:>8}")
    print("-" * 65)
    print(f"{'TOTAL':<25} {tot_block:>10} {tot_review:>10} {tot_dec_all:>8}")
    print()
    print(f"UNMATCHED ALERTS: {unmatched_count}")
    print("=" * 65)


def process_alerts(
    alert_details: List[Dict[str, Any]],
    log_index: Dict[Tuple[str, int], Dict[str, Any]],
) -> Tuple[List[Dict[str, Any]], int, Dict[str, Dict[str, int]], Dict[str, Dict[str, int]]]:
    """Match a collection of alert details to log entries and compute tables.

    Returns:
        matched_verdicts: list of dicts with alert_id, verdict, comment, scenario, decision, detail
        unmatched_count: count of alerts that could not be matched
        scenario_x_label: nested counts of scenario -> label -> count
        scenario_x_decision: nested counts of scenario -> decision -> count
    """
    matched_verdicts: List[Dict[str, Any]] = []
    unmatched_count = 0
    scenario_x_label: Dict[str, Dict[str, int]] = {}
    scenario_x_decision: Dict[str, Dict[str, int]] = {}

    for detail in alert_details:
        alert_id = detail.get("id")
        log_entry = match_alert_to_log(detail, log_index)
        if not log_entry:
            unmatched_count += 1
            tx_id = detail.get("transactionId")
            logger.warning(f"Unmatched alert {alert_id} (transactionId={tx_id}): skipped.")
            continue

        scenario = log_entry.get("scenario", "UNKNOWN")
        verdict = get_verdict_label(scenario)
        comment = format_verdict_comment(scenario)
        decision = detail.get("decision", "UNKNOWN")

        if scenario not in scenario_x_label:
            scenario_x_label[scenario] = {"FRAUD": 0, "LEGITIMATE": 0}
        scenario_x_label[scenario][verdict] = scenario_x_label[scenario].get(verdict, 0) + 1

        if scenario not in scenario_x_decision:
            scenario_x_decision[scenario] = {}
        scenario_x_decision[scenario][decision] = scenario_x_decision[scenario].get(decision, 0) + 1

        matched_verdicts.append(
            {
                "alert_id": alert_id,
                "verdict": verdict,
                "comment": comment,
                "scenario": scenario,
                "decision": decision,
                "detail": detail,
            }
        )

    return matched_verdicts, unmatched_count, scenario_x_label, scenario_x_decision


def main() -> None:
    parser = argparse.ArgumentParser(description="PayGuard Verdict Seeding Script")
    parser.add_argument(
        "--txns-file",
        type=str,
        default="last_run_transactions.json",
        help="Path to exported transactions JSON file (default: last_run_transactions.json)",
    )
    parser.add_argument(
        "--report-file",
        type=str,
        default="last_run_report.json",
        help="Path to simulator report JSON file (default: last_run_report.json)",
    )
    parser.add_argument(
        "--confirm",
        action="store_true",
        default=False,
        help="Execute real verdict submission against backend (default: False; requires --confirm)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="Run in dry-run mode without submitting any verdicts (default ON unless --confirm is passed)",
    )
    parser.add_argument(
        "--api-url",
        type=str,
        default=None,
        help="PayGuard API base URL (default: from .env or http://localhost:8080)",
    )
    parser.add_argument(
        "--username",
        type=str,
        default=None,
        help="Analyst/Admin username (default: from .env or admin)",
    )
    parser.add_argument(
        "--password",
        type=str,
        default=None,
        help="Password (default: from .env or adminPassword123)",
    )

    args = parser.parse_args()
    load_env_variables()

    is_dry_run = not args.confirm or args.dry_run

    script_dir = Path(__file__).resolve().parent
    txns_path = Path(args.txns_file)
    if not txns_path.is_absolute():
        txns_path = script_dir / txns_path

    if not txns_path.exists():
        logger.error(f"Transactions file not found: {txns_path}")
        logger.error("Run simulator first with --export-txns, e.g.:")
        logger.error("  python simulator.py --count 300 --accounts 15 --fraud-rate 0.03 --seed 42 --export-txns")
        sys.exit(1)

    report_path = Path(args.report_file)
    if not report_path.is_absolute():
        report_path = script_dir / report_path

    logger.info(f"Loading exported transactions from: {txns_path}")
    with open(txns_path, "r", encoding="utf-8") as f:
        transactions: List[Dict[str, Any]] = json.load(f)
    logger.info(f"Loaded {len(transactions)} exported transactions.")

    log_index = build_txn_log_index(transactions)
    logger.info(f"Indexed {len(log_index)} unique (accountId, seq) transaction log entries.")

    api_base_url = args.api_url or os.getenv("API_BASE_URL", "http://localhost:8080").rstrip("/")
    username = args.username or os.getenv("SIMULATOR_USERNAME", "admin")
    password = args.password or os.getenv("SIMULATOR_PASSWORD", "adminPassword123")

    session = requests.Session()
    token = login(session, api_base_url, username, password)
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }

    logger.info(f"Fetching all alerts from {api_base_url}/api/v1/alerts...")
    alerts = fetch_all_alerts(session, api_base_url, headers)
    logger.info(f"Retrieved {len(alerts)} total alerts from backend.")

    # Fetch detail for each alert
    alert_details: List[Dict[str, Any]] = []
    for alert in alerts:
        alert_id = alert.get("id")
        if not alert_id:
            continue
        detail = fetch_alert_detail(session, api_base_url, headers, alert_id)
        if detail:
            alert_details.append(detail)

    matched_verdicts, unmatched_count, scenario_x_label, scenario_x_decision = process_alerts(
        alert_details, log_index
    )

    legit_alerts_count = sum(
        counts.get("LEGITIMATE", 0) for counts in scenario_x_label.values()
    )

    if is_dry_run:
        logger.info("[DRY-RUN MODE] Computing ground-truth matches without submitting verdicts.")
        print_scenario_tables(scenario_x_label, scenario_x_decision, unmatched_count)
        logger.info("[DRY-RUN COMPLETE] POSTed 0 verdicts. Pass --confirm to submit real verdicts.")
        return

    # Real submission mode (--confirm)
    logger.info("Confirm flag present: validating simulator report ground truth before submission...")
    if not validate_legit_count_against_report(report_path, legit_alerts_count):
        logger.error("Simulator report validation failed. Aborting submission.")
        sys.exit(1)

    print_scenario_tables(scenario_x_label, scenario_x_decision, unmatched_count)

    already_verdicted = 0
    existing_fraud = 0
    existing_legit = 0
    new_submitted = 0
    new_fraud = 0
    new_legit = 0

    total_matched = len(matched_verdicts)
    for idx, item in enumerate(matched_verdicts, start=1):
        alert_id = item["alert_id"]
        detail = item["detail"]
        verdict = item["verdict"]
        comment = item["comment"]

        verdict_obj = detail.get("verdict")
        if verdict_obj is not None:
            already_verdicted += 1
            v_type = verdict_obj.get("verdict")
            if v_type == "FRAUD":
                existing_fraud += 1
            elif v_type == "LEGITIMATE":
                existing_legit += 1
            continue

        success = submit_verdict(session, api_base_url, headers, alert_id, verdict, comment)
        if success:
            new_submitted += 1
            if verdict == "FRAUD":
                new_fraud += 1
            else:
                new_legit += 1

            if new_submitted % 5 == 0 or idx == total_matched:
                logger.info(f"[Progress] Submitted {new_submitted} verdicts (Fraud: {new_fraud}, Legit: {new_legit})...")

    cum_total = already_verdicted + new_submitted
    cum_fraud = existing_fraud + new_fraud
    cum_legit = existing_legit + new_legit

    is_eligible = (
        cum_total >= MIN_TOTAL_VERDICTS
        and cum_fraud >= MIN_PER_CLASS
        and cum_legit >= MIN_PER_CLASS
    )

    logger.info("=" * 65)
    logger.info("VERDICT SEEDING SUMMARY")
    logger.info("=" * 65)
    logger.info(f"Total Alerts Inspected:       {len(alert_details)}")
    logger.info(f"Unmatched Alerts:             {unmatched_count}")
    logger.info(f"Already Verdicted Alerts:     {already_verdicted} (Fraud: {existing_fraud}, Legit: {existing_legit})")
    logger.info(f"New Verdicts Submitted:       {new_submitted}")
    logger.info(f"  - FRAUD:                    {new_fraud}")
    logger.info(f"  - LEGITIMATE:               {new_legit}")
    logger.info("-" * 65)
    logger.info("CUMULATIVE DATABASE TOTALS:")
    logger.info(f"  - Total Verdicts:           {cum_total} (Gate Requirement: >= {MIN_TOTAL_VERDICTS})")
    logger.info(f"  - FRAUD Verdicts:           {cum_fraud} (Gate Requirement: >= {MIN_PER_CLASS})")
    logger.info(f"  - LEGITIMATE Verdicts:      {cum_legit} (Gate Requirement: >= {MIN_PER_CLASS})")
    logger.info("-" * 65)
    if is_eligible:
        logger.info("ELIGIBILITY STATUS:           PASS (Eligible for retraining trigger)")
    else:
        logger.info("ELIGIBILITY STATUS:           FAIL (Insufficient verdicts for retraining)")
    logger.info("=" * 65)


if __name__ == "__main__":
    main()
