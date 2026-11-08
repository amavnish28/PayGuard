"""Database Isolation Guard for PayGuard Load Testing.

Ensures:
1. Load test connections ONLY target `payguard_loadtest`.
2. The original evaluation database `payguard` is NEVER touched or modified.
3. Pre-test and post-test verification of baseline row counts (10,000 / 10,000 / 722 / 5).
4. No load-test transaction identifiers ('LOAD-') ever leak into the evaluation database.
"""

from __future__ import annotations

import logging
import subprocess
import sys
from typing import Dict, Any

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] [IsolationGuard]: %(message)s")
logger = logging.getLogger("IsolationGuard")

TARGET_DB = "payguard_loadtest"
EVAL_DB = "payguard"
CONTAINER_NAME = "payguard-postgres"

EXPECTED_EVAL_BASELINE = {
    "transactions": 10000,
    "transaction_features": 10000,
    "alerts": 722,
    "audit_logs": 5,
}


class IsolationViolationError(RuntimeError):
    """Raised when an attempt is made to target the evaluation database or baseline counts differ."""


def run_psql_query(db_name: str, query: str) -> str:
    """Execute a read-only query inside the postgres container using psql -t -A."""
    cmd = [
        "docker", "exec", CONTAINER_NAME,
        "psql", "-U", "payguard", "-d", db_name,
        "-t", "-A", "-c", query,
    ]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, check=True)
        return res.stdout.strip()
    except subprocess.CalledProcessError as e:
        logger.error(f"psql query failed on database '{db_name}': {e.stderr.strip()}")
        raise RuntimeError(f"Database query error on '{db_name}': {e.stderr.strip()}") from e


def get_table_counts(db_name: str) -> Dict[str, int]:
    """Retrieve row counts for core PayGuard tables."""
    query = (
        "SELECT 'transactions', count(*) FROM transactions "
        "UNION ALL SELECT 'transaction_features', count(*) FROM transaction_features "
        "UNION ALL SELECT 'alerts', count(*) FROM alerts "
        "UNION ALL SELECT 'audit_logs', count(*) FROM audit_logs;"
    )
    raw = run_psql_query(db_name, query)
    counts: Dict[str, int] = {}
    for line in raw.splitlines():
        if "|" in line:
            tbl, cnt = line.split("|", 1)
            counts[tbl.strip()] = int(cnt.strip())
    return counts


def verify_pre_test(target_db: str = TARGET_DB) -> Dict[str, Any]:
    """Verify isolation prerequisites BEFORE any load test execution."""
    logger.info("Executing Pre-Test Database Isolation Verification...")

    # Rule 1: Target database must strictly be payguard_loadtest
    if target_db != TARGET_DB:
        msg = f"CRITICAL ISOLATION BREACH: Target database is '{target_db}'! Only '{TARGET_DB}' is permitted."
        logger.critical(msg)
        raise IsolationViolationError(msg)

    # Rule 2: Verify target database identity from PostgreSQL engine
    current_db = run_psql_query(target_db, "SELECT current_database();")
    if current_db != TARGET_DB:
        msg = f"CRITICAL: Connected database '{current_db}' does not match expected target '{TARGET_DB}'."
        logger.critical(msg)
        raise IsolationViolationError(msg)
    logger.info(f"Verified connection to target load-test database: '{current_db}'")

    # Rule 3: Verify evaluation database baseline is untouched
    eval_counts = get_table_counts(EVAL_DB)
    logger.info(f"Evaluation database ('{EVAL_DB}') row counts: {eval_counts}")

    for table, expected in EXPECTED_EVAL_BASELINE.items():
        actual = eval_counts.get(table, 0)
        if actual != expected:
            msg = (
                f"BASELINE INTEGRITY VIOLATION on '{EVAL_DB}.{table}': "
                f"expected {expected}, found {actual}!"
            )
            logger.critical(msg)
            raise IsolationViolationError(msg)

    logger.info(f"Evaluation database ('{EVAL_DB}') baseline verified: 10,000 / 10,000 / 722 / 5")
    return {"status": "PASS", "target_db": target_db, "eval_counts": eval_counts}


def verify_post_test(target_db: str = TARGET_DB) -> Dict[str, Any]:
    """Verify isolation post-test to confirm zero writes reached payguard."""
    logger.info("Executing Post-Test Database Isolation Verification...")

    # Rule 1: Check evaluation database baseline row counts
    eval_counts = get_table_counts(EVAL_DB)
    for table, expected in EXPECTED_EVAL_BASELINE.items():
        actual = eval_counts.get(table, 0)
        if actual != expected:
            msg = f"POST-TEST CORRUPTION on '{EVAL_DB}.{table}': expected {expected}, found {actual}!"
            logger.critical(msg)
            raise IsolationViolationError(msg)

    # Rule 2: Check that NO 'LOAD-' transaction IDs exist in the evaluation database
    leak_check = run_psql_query(
        EVAL_DB,
        "SELECT count(*) FROM transactions WHERE transaction_id LIKE 'LOAD-%';",
    )
    if int(leak_check) != 0:
        msg = f"DATA LEAK DETECTED: Found {leak_check} 'LOAD-' records inside evaluation database '{EVAL_DB}'!"
        logger.critical(msg)
        raise IsolationViolationError(msg)

    # Rule 3: Retrieve and log load-test database record counts
    loadtest_counts = get_table_counts(target_db)
    logger.info(f"Load-test database ('{target_db}') final counts: {loadtest_counts}")
    logger.info(f"Evaluation database ('{EVAL_DB}') verified untouched (0 leaks, baseline intact).")

    return {
        "status": "PASS",
        "eval_counts": eval_counts,
        "loadtest_counts": loadtest_counts,
        "leaked_records": int(leak_check),
    }


if __name__ == "__main__":
    try:
        verify_pre_test()
        print("\nIsolation Guard Check: PASS. Safe to proceed with load testing against 'payguard_loadtest'.")
    except Exception as exc:
        print(f"\nIsolation Guard Check: FAILED ({exc})", file=sys.stderr)
        sys.exit(1)
