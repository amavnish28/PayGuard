#!/usr/bin/env python3
"""
seed_verdicts.py - Standalone analyst verdict seeding script for PayGuard.

Reads exported transactions from a simulator run (e.g. simulator/last_run_transactions.json),
queries alerts from the Spring Boot backend, matches alerts to the simulated transaction scenarios,
and submits deterministic verdicts (FRAUD for fraud scenarios, LEGITIMATE for LEGIT)
via POST /api/v1/alerts/{id}/verdict.

Usage:
    python seed_verdicts.py [--txns-file last_run_transactions.json] [--api-url http://localhost:8080]
"""

import argparse
import json
import logging
import os
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


def normalize_iso_timestamp(ts: Optional[str]) -> str:
    """Normalize timestamp string to second precision for robust index matching."""
    if not ts:
        return ""
    try:
        dt = datetime.fromisoformat(ts)
        return dt.strftime("%Y-%m-%dT%H:%M:%S")
    except Exception:
        return ts[:19]


def build_txn_indexes(transactions: List[Dict[str, Any]]) -> Tuple[Dict[str, Dict[str, Any]], Dict[Tuple[str, float], Dict[str, Any]]]:
    """Index simulated transactions by transactionId and (timestamp, amount)."""
    by_id: Dict[str, Dict[str, Any]] = {}
    by_ts_amt: Dict[Tuple[str, float], Dict[str, Any]] = {}

    for tx in transactions:
        tx_id = tx.get("transactionId")
        if tx_id:
            by_id[tx_id] = tx

        ts = normalize_iso_timestamp(tx.get("transactionTimestamp"))
        amt = round(float(tx.get("amount", 0.0)), 2)
        if ts:
            by_ts_amt[(ts, amt)] = tx

    return by_id, by_ts_amt


def match_scenario(alert_detail: Dict[str, Any], by_id: Dict[str, Dict[str, Any]], by_ts_amt: Dict[Tuple[str, float], Dict[str, Any]]) -> str:
    """Match alert detail to simulated transaction scenario."""
    tx_id = alert_detail.get("transactionId")
    if tx_id and tx_id in by_id:
        return by_id[tx_id].get("scenario", "UNKNOWN")

    ts = normalize_iso_timestamp(alert_detail.get("transactionTimestamp"))
    amt = round(float(alert_detail.get("amount", 0.0)), 2)
    key = (ts, amt)
    if key in by_ts_amt:
        return by_ts_amt[key].get("scenario", "UNKNOWN")

    # Fallback heuristic: If decision is BLOCK, assume fraud scenario; if REVIEW without match, default to LEGIT
    decision = alert_detail.get("decision", "REVIEW")
    return "FRAUD_HEURISTIC" if decision == "BLOCK" else "LEGIT"


def main() -> None:
    parser = argparse.ArgumentParser(description="PayGuard Verdict Seeding Script")
    parser.add_argument(
        "--txns-file",
        type=str,
        default="last_run_transactions.json",
        help="Path to exported transactions JSON file (default: last_run_transactions.json)",
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
        help="Analyst/Admin username (default: from .env or analyst)",
    )
    parser.add_argument(
        "--password",
        type=str,
        default=None,
        help="Password (default: from .env or analyst123)",
    )

    args = parser.parse_args()
    load_env_variables()

    script_dir = Path(__file__).resolve().parent
    txns_path = Path(args.txns_file)
    if not txns_path.is_absolute():
        txns_path = script_dir / txns_path

    if not txns_path.exists():
        logger.error(f"Transactions file not found: {txns_path}")
        logger.error("Run simulator first with --export-txns, e.g.:")
        logger.error("  python simulator.py --count 300 --accounts 15 --fraud-rate 0.03 --seed 42 --export-txns")
        sys.exit(1)

    logger.info(f"Loading exported transactions from: {txns_path}")
    with open(txns_path, "r", encoding="utf-8") as f:
        transactions: List[Dict[str, Any]] = json.load(f)
    logger.info(f"Loaded {len(transactions)} exported transactions.")

    by_id, by_ts_amt = build_txn_indexes(transactions)

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

    total_alerts = len(alerts)
    already_verdicted = 0
    existing_fraud = 0
    existing_legit = 0

    new_submitted = 0
    new_fraud = 0
    new_legit = 0

    for idx, alert in enumerate(alerts, start=1):
        alert_id = alert.get("id")
        if not alert_id:
            continue

        detail = fetch_alert_detail(session, api_base_url, headers, alert_id)
        if not detail:
            continue

        verdict_obj = detail.get("verdict")
        if verdict_obj is not None:
            already_verdicted += 1
            v_type = verdict_obj.get("verdict")
            if v_type == "FRAUD":
                existing_fraud += 1
            elif v_type == "LEGITIMATE":
                existing_legit += 1
            continue

        # Alert is un-verdicted -> determine verdict from matched scenario
        scenario = match_scenario(detail, by_id, by_ts_amt)
        is_fraud = scenario != "LEGIT"
        verdict = "FRAUD" if is_fraud else "LEGITIMATE"
        txn_ts = detail.get("transactionTimestamp") or datetime.now(timezone.utc).isoformat()
        comment = f"Seeded from simulator run {txn_ts} scenario {scenario}"

        success = submit_verdict(session, api_base_url, headers, alert_id, verdict, comment)
        if success:
            new_submitted += 1
            if is_fraud:
                new_fraud += 1
            else:
                new_legit += 1

            if new_submitted % 5 == 0 or idx == total_alerts:
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
    logger.info(f"Total Alerts Inspected:       {total_alerts}")
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
        if cum_total < MIN_TOTAL_VERDICTS:
            logger.info(f"  * Need at least {MIN_TOTAL_VERDICTS - cum_total} more total verdicts.")
        if cum_fraud < MIN_PER_CLASS:
            logger.info(f"  * Need at least {MIN_PER_CLASS - cum_fraud} more FRAUD verdicts.")
        if cum_legit < MIN_PER_CLASS:
            logger.info(f"  * Need at least {MIN_PER_CLASS - cum_legit} more LEGITIMATE verdicts.")
    logger.info("=" * 65)


if __name__ == "__main__":
    main()
