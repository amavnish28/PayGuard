#!/usr/bin/env python3
"""PayGuard Transaction Simulator — External Python Client.

Generates realistic financial transactions and sends them to the PayGuard
Spring Boot backend API (/api/transactions) with real-time rate pacing,
deterministic RNG seeding, virtual account tracking, simulated IST clock,
and fraud scenario modeling.
"""

from __future__ import annotations

import argparse
import collections
from datetime import datetime, timedelta, timezone
import json
import logging
import os
from pathlib import Path
import random
import sys
import time
from typing import Any, Deque, Dict, List, Optional, Set, Tuple

import requests
from dotenv import load_dotenv

# Fixed IST timezone: UTC+05:30
IST = timezone(timedelta(hours=5, minutes=30))

# Fixed pool of Indian cities
CITIES: List[str] = [
    "Delhi",
    "Mumbai",
    "Bangalore",
    "Kolkata",
    "Chennai",
    "Hyderabad",
    "Pune",
    "Jaipur",
]

# Merchant categories
MERCHANT_TYPES: List[str] = [
    "GROCERY",
    "RETAIL",
    "DINING",
    "ENTERTAINMENT",
    "ELECTRONICS",
    "UTILITIES",
    "TRAVEL",
    "HEALTHCARE",
]

# Fraud scenario types
FRAUD_SCENARIOS: List[str] = [
    "ACCOUNT_TAKEOVER",
    "CARD_TESTING",
    "ODD_HOUR_TRANSFER",
    "SUBTLE",
    "STEALTH",
]

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("simulator")


class VirtualAccount:
    """Tracks state and history for a single simulated account."""

    def __init__(
        self,
        account_id: str,
        home_location: str,
        home_device: str,
        median_amount: float,
        activity_weight: float = 1.0,
    ) -> None:
        self.account_id: str = account_id
        self.transaction_count: int = 0
        self.home_location: str = home_location
        self.home_device: str = home_device
        self.median_amount: float = median_amount
        self.activity_weight: float = activity_weight
        self.known_devices: Set[str] = {home_device}
        self.known_locations: Set[str] = {home_location}


class TransactionTemplate:
    """Pre-computed template for a single transaction in an episode."""

    def __init__(
        self,
        account: VirtualAccount,
        scenario: str,
        amount: float,
        device_id: str,
        location: str,
        merchant_type: str,
        timing_action: str,
        timing_value: float,
    ) -> None:
        self.account: VirtualAccount = account
        self.scenario: str = scenario
        self.amount: float = amount
        self.device_id: str = device_id
        self.location: str = location
        self.merchant_type: str = merchant_type
        # timing_action: "advance_seconds", "odd_hour", "daytime"
        self.timing_action: str = timing_action
        self.timing_value: float = timing_value


class SimulatedClock:
    """Maintains monotonically advancing simulated time in IST."""

    def __init__(self, initial_time: datetime, rng: random.Random) -> None:
        self.current_time: datetime = initial_time
        self.rng: random.Random = rng

    def advance_seconds(self, seconds: float) -> None:
        self.current_time += timedelta(seconds=max(1.0, seconds))

    def advance_to_odd_hour(self) -> None:
        """Advance time into odd hours (00:00 - 05:59 IST)."""
        h = self.current_time.hour
        if h in [0, 1, 2, 3, 4, 5]:
            # Already in odd hour window; advance by 1-5 minutes
            self.advance_seconds(self.rng.uniform(60, 300))
        elif h == 23:
            # 23:00 is also late night odd hour
            self.advance_seconds(self.rng.uniform(60, 300))
        else:
            # Advance to next day at 01:00 - 04:00 IST
            next_day = self.current_time.date() + timedelta(days=1)
            target_hour = self.rng.randint(1, 4)
            target_min = self.rng.randint(0, 59)
            target_sec = self.rng.randint(0, 59)
            self.current_time = datetime(
                next_day.year,
                next_day.month,
                next_day.day,
                target_hour,
                target_min,
                target_sec,
                tzinfo=IST,
            )

    def advance_to_daytime(self) -> None:
        """Advance time into normal daytime hours (08:00 - 21:59 IST)."""
        h = self.current_time.hour
        if h < 8:
            # Jump forward to 08:00+ today
            self.current_time = self.current_time.replace(
                hour=8,
                minute=self.rng.randint(0, 30),
                second=self.rng.randint(0, 59),
            )
        elif h >= 22:
            # Jump forward to next day morning
            next_day = self.current_time.date() + timedelta(days=1)
            self.current_time = datetime(
                next_day.year,
                next_day.month,
                next_day.day,
                8,
                self.rng.randint(0, 30),
                self.rng.randint(0, 59),
                tzinfo=IST,
            )
        else:
            # Already in daytime; advance by 30s-10m
            self.advance_seconds(self.rng.uniform(30, 600))


class TransactionGenerator:
    """Generates deterministic transaction payloads and manages episode queues."""

    def __init__(
        self,
        accounts: List[VirtualAccount],
        clock: SimulatedClock,
        rng: random.Random,
        fraud_rate: float,
        run_id: str,
    ) -> None:
        self.accounts: List[VirtualAccount] = accounts
        self.clock: SimulatedClock = clock
        self.rng: random.Random = rng
        self.fraud_rate: float = fraud_rate
        self.run_id: str = run_id
        self.episode_queue: Deque[TransactionTemplate] = collections.deque()
        self.global_tx_counter: int = 0
        self.account_weights: List[float] = [a.activity_weight for a in accounts]

    def _generate_legit_template(self, account: VirtualAccount) -> TransactionTemplate:
        # Pacing: 30s - 10min gap
        gap = self.rng.uniform(30, 600)

        # Legit noise: 3% new device, 3% new location, 2% high amount
        if self.rng.random() < 0.03:
            device = f"DEVICE-{account.account_id}-{self.rng.randint(2, 5):02d}"
        else:
            device = account.home_device

        if self.rng.random() < 0.03:
            other_cities = [c for c in CITIES if c != account.home_location]
            location = self.rng.choice(other_cities)
        else:
            location = account.home_location

        if self.rng.random() < 0.02:
            amount = round(account.median_amount * self.rng.uniform(1.8, 2.8), 2)
        else:
            amount = round(account.median_amount * self.rng.uniform(0.7, 1.3), 2)
        amount = max(10.0, amount)

        merchant = self.rng.choice(MERCHANT_TYPES)
        return TransactionTemplate(
            account=account,
            scenario="LEGIT",
            amount=amount,
            device_id=device,
            location=location,
            merchant_type=merchant,
            timing_action="advance_seconds",
            timing_value=gap,
        )

    def _generate_fraud_episode(self, account: VirtualAccount, scenario: str) -> List[TransactionTemplate]:
        templates: List[TransactionTemplate] = []

        if scenario == "ACCOUNT_TAKEOVER":
            # 1-3 transactions within ~30 simulated minutes
            n_txns = self.rng.randint(1, 3)
            is_odd_hour = self.rng.random() < 0.50
            new_dev = f"DEVICE-ATO-{self.rng.randint(1000, 9999)}"
            other_cities = [c for c in CITIES if c not in account.known_locations]
            if not other_cities:
                other_cities = [c for c in CITIES if c != account.home_location]
            new_loc = self.rng.choice(other_cities) if self.rng.random() < 0.85 else account.home_location

            for idx in range(n_txns):
                amt = round(account.median_amount * self.rng.uniform(3.0, 10.0), 2)
                amt = max(100.0, amt)
                merchant = self.rng.choice(["ELECTRONICS", "RETAIL", "TRAVEL"])

                if idx == 0:
                    timing_action = "odd_hour" if is_odd_hour else "advance_seconds"
                    timing_val = 0.0 if is_odd_hour else self.rng.uniform(60, 300)
                else:
                    timing_action = "advance_seconds"
                    timing_val = self.rng.uniform(120, 600)  # 2 to 10 min

                templates.append(
                    TransactionTemplate(
                        account=account,
                        scenario="ACCOUNT_TAKEOVER",
                        amount=amt,
                        device_id=new_dev,
                        location=new_loc,
                        merchant_type=merchant,
                        timing_action=timing_action,
                        timing_value=timing_val,
                    )
                )

        elif scenario == "CARD_TESTING":
            # 5-8 transactions within ~90 simulated seconds
            n_txns = self.rng.randint(5, 8)
            dev = f"DEVICE-CT-{self.rng.randint(100, 999)}" if self.rng.random() < 0.50 else account.home_device
            has_final_large = self.rng.random() < 0.60
            loc = account.home_location

            for idx in range(n_txns):
                if idx == n_txns - 1 and has_final_large:
                    amt = round(account.median_amount * self.rng.uniform(2.0, 6.0), 2)
                else:
                    amt = round(max(1.0, account.median_amount * self.rng.uniform(0.02, 0.30)), 2)

                merchant = self.rng.choice(["GROCERY", "RETAIL", "UTILITIES"])
                timing_action = "advance_seconds"
                timing_val = self.rng.uniform(10, 25) if idx == 0 else self.rng.uniform(8, 14)

                templates.append(
                    TransactionTemplate(
                        account=account,
                        scenario="CARD_TESTING",
                        amount=amt,
                        device_id=dev,
                        location=loc,
                        merchant_type=merchant,
                        timing_action=timing_action,
                        timing_value=timing_val,
                    )
                )

        elif scenario == "ODD_HOUR_TRANSFER":
            # 1-2 transactions placed at simulated hour 0-5 IST
            n_txns = self.rng.randint(1, 2)
            dev = account.home_device
            loc = account.home_location

            for idx in range(n_txns):
                amt = round(account.median_amount * self.rng.uniform(2.5, 6.0), 2)
                merchant = self.rng.choice(["RETAIL", "UTILITIES", "DINING"])

                if idx == 0:
                    timing_action = "odd_hour"
                    timing_val = 0.0
                else:
                    timing_action = "advance_seconds"
                    timing_val = self.rng.uniform(120, 300)

                templates.append(
                    TransactionTemplate(
                        account=account,
                        scenario="ODD_HOUR_TRANSFER",
                        amount=amt,
                        device_id=dev,
                        location=loc,
                        merchant_type=merchant,
                        timing_action=timing_action,
                        timing_value=timing_val,
                    )
                )

        elif scenario == "SUBTLE":
            # 1-2 transactions staying BELOW rule-engine thresholds
            n_txns = self.rng.randint(1, 2)
            dev = account.home_device
            is_daytime_newloc = self.rng.random() < 0.50

            if is_daytime_newloc:
                other_cities = [c for c in CITIES if c not in account.known_locations]
                if not other_cities:
                    other_cities = [c for c in CITIES if c != account.home_location]
                loc = self.rng.choice(other_cities)
                multiplier = self.rng.uniform(1.5, 2.9)  # < 3.0 threshold
                first_action = "daytime"
            else:
                loc = account.home_location
                multiplier = self.rng.uniform(1.4, 1.99)  # < 2.0 threshold
                first_action = "odd_hour"

            for idx in range(n_txns):
                amt = round(account.median_amount * multiplier, 2)
                merchant = self.rng.choice(MERCHANT_TYPES)
                if idx == 0:
                    timing_action = first_action
                    timing_val = 0.0
                else:
                    timing_action = "advance_seconds"
                    timing_val = self.rng.uniform(150, 300)

                templates.append(
                    TransactionTemplate(
                        account=account,
                        scenario="SUBTLE",
                        amount=amt,
                        device_id=dev,
                        location=loc,
                        merchant_type=merchant,
                        timing_action=timing_action,
                        timing_value=timing_val,
                    )
                )

        elif scenario == "STEALTH":
            # 1 transaction statistically identical to normal legit transaction
            dev = account.home_device
            loc = account.home_location
            amt = round(account.median_amount * self.rng.uniform(0.9, 1.1), 2)
            merchant = self.rng.choice(MERCHANT_TYPES)

            templates.append(
                TransactionTemplate(
                    account=account,
                    scenario="STEALTH",
                    amount=amt,
                    device_id=dev,
                    location=loc,
                    merchant_type=merchant,
                    timing_action="daytime",
                    timing_value=self.rng.uniform(30, 600),
                )
            )

        return templates

    def next_transaction(self) -> Tuple[Dict[str, Any], str, VirtualAccount]:
        """Returns (payload, scenario, account)."""
        if not self.episode_queue:
            # Pick an account using activity weights
            account = self.rng.choices(self.accounts, weights=self.account_weights, k=1)[0]

            # Check cold-start threshold: transaction_count >= 10
            is_fraud_eligible = account.transaction_count >= 10
            if is_fraud_eligible and (self.rng.random() < self.fraud_rate):
                scenario = self.rng.choice(FRAUD_SCENARIOS)
                episode = self._generate_fraud_episode(account, scenario)
                self.episode_queue.extend(episode)
            else:
                self.episode_queue.append(self._generate_legit_template(account))

        template = self.episode_queue.popleft()

        # Advance clock based on template timing specification
        if template.timing_action == "advance_seconds":
            self.clock.advance_seconds(template.timing_value)
            # If legit and landed in deep night, 90% chance advance to daytime
            if template.scenario == "LEGIT" and (self.clock.current_time.hour >= 23 or self.clock.current_time.hour < 6):
                if self.rng.random() < 0.90:
                    self.clock.advance_to_daytime()
        elif template.timing_action == "odd_hour":
            self.clock.advance_to_odd_hour()
        elif template.timing_action == "daytime":
            self.clock.advance_to_daytime()

        self.global_tx_counter += 1
        tx_id = f"SIM-{self.run_id}-{template.account.account_id}-{self.global_tx_counter:06d}"

        payload: Dict[str, Any] = {
            "transactionId": tx_id,
            "accountId": template.account.account_id,
            "amount": float(template.amount),
            "currency": "INR",
            "deviceId": template.device_id,
            "location": template.location,
            "merchantType": template.merchant_type,
            "transactionTimestamp": self.clock.current_time.isoformat(),
        }

        return payload, template.scenario, template.account


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
        logger.error(f"Authentication request failed: {e}")
        sys.exit(1)

    if resp.status_code != 200:
        logger.error(f"Authentication failed [HTTP {resp.status_code}]: {resp.text}")
        sys.exit(1)

    data = resp.json()
    token = data.get("token")
    if not token:
        logger.error("Authentication response did not contain a JWT token")
        sys.exit(1)

    logger.info(f"Authentication successful (user: {data.get('username')}, role: {data.get('role')})")
    return token


def parse_sim_start(sim_start_str: str) -> datetime:
    """Parses --sim-start string to datetime with IST offset."""
    if sim_start_str.strip().lower() == "now":
        return datetime.now(IST)
    try:
        dt = datetime.fromisoformat(sim_start_str)
        if dt.tzinfo is None:
            return dt.replace(tzinfo=IST)
        return dt.astimezone(IST)
    except Exception as e:
        logger.error(f"Invalid --sim-start datetime '{sim_start_str}': {e}")
        sys.exit(1)


def initialize_accounts(num_accounts: int, rng: random.Random) -> List[VirtualAccount]:
    """Generates the initial deterministic virtual account pool."""
    accounts: List[VirtualAccount] = []
    for i in range(1, num_accounts + 1):
        acc_id = f"SIM-ACC-{i:04d}"
        home_loc = rng.choice(CITIES)
        home_dev = f"DEVICE-{acc_id}-01"
        med_amt = round(max(500.0, min(10000.0, rng.lognormvariate(7.8, 0.4))), 2)
        activity_weight = rng.lognormvariate(0.0, 1.2)
        acc = VirtualAccount(
            account_id=acc_id,
            home_location=home_loc,
            home_device=home_dev,
            median_amount=med_amt,
            activity_weight=activity_weight,
        )
        accounts.append(acc)
    return accounts


def main() -> None:
    parser = argparse.ArgumentParser(description="PayGuard Transaction Simulator Client")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--count", type=int, help="Total transactions to send")
    group.add_argument("--duration", type=float, help="Run for this many real wall-clock seconds")

    parser.add_argument("--tps", type=float, default=2.0, help="Target real transactions-per-second send rate (default: 2.0)")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for full determinism (default: 42)")
    parser.add_argument("--accounts", type=int, default=50, help="Size of the virtual account pool (default: 50)")
    parser.add_argument(
        "--sim-start",
        type=str,
        default="2026-10-01T00:00:00+05:30",
        help='Initial simulated clock time (ISO-8601 or "now", default: 2026-10-01T00:00:00+05:30)',
    )
    parser.add_argument("--fraud-rate", type=float, default=0.015, help="Probability of fraud episode per eligible transaction (default: 0.015)")
    parser.add_argument("--export-txns", action="store_true", help="Save all sent transaction payloads to last_run_transactions.json")

    args = parser.parse_args()

    if args.count is not None and args.count <= 0:
        logger.error("--count must be greater than 0")
        sys.exit(1)
    if args.duration is not None and args.duration <= 0:
        logger.error("--duration must be greater than 0")
        sys.exit(1)
    if args.tps <= 0:
        logger.error("--tps must be greater than 0")
        sys.exit(1)
    if args.accounts <= 0:
        logger.error("--accounts must be greater than 0")
        sys.exit(1)
    if args.fraud_rate < 0 or args.fraud_rate > 1:
        logger.error("--fraud-rate must be between 0.0 and 1.0")
        sys.exit(1)

    # Load environment variables
    env_path = Path(__file__).resolve().parent / ".env"
    if env_path.exists():
        load_dotenv(dotenv_path=env_path)
    else:
        load_dotenv()

    api_base_url = os.getenv("API_BASE_URL", "http://localhost:8080").rstrip("/")
    username = os.getenv("SIMULATOR_USERNAME", "admin")
    password = os.getenv("SIMULATOR_PASSWORD", "adminPassword123")

    session = requests.Session()
    token = login(session, api_base_url, username, password)

    # Initialize RNG, Clock, and Virtual Account Pool
    rng = random.Random(args.seed)
    initial_sim_time = parse_sim_start(args.sim_start)
    clock = SimulatedClock(initial_sim_time, rng)
    accounts = initialize_accounts(args.accounts, rng)

    # Unique run timestamp to avoid duplicate transaction IDs across separate runs
    run_id = f"{int(time.time()):08x}"
    generator = TransactionGenerator(
        accounts=accounts,
        clock=clock,
        rng=rng,
        fraud_rate=args.fraud_rate,
        run_id=run_id,
    )

    # Tracking metrics
    total_sent: int = 0
    total_failed: int = 0
    ml_available_count: int = 0
    decision_counts: Dict[str, int] = {"APPROVE": 0, "REVIEW": 0, "BLOCK": 0}
    scenario_breakdown: Dict[str, Dict[str, int]] = {
        "LEGIT": {"sent": 0, "flagged_review_or_block": 0},
        "ACCOUNT_TAKEOVER": {"sent": 0, "flagged_review_or_block": 0},
        "CARD_TESTING": {"sent": 0, "flagged_review_or_block": 0},
        "ODD_HOUR_TRANSFER": {"sent": 0, "flagged_review_or_block": 0},
        "SUBTLE": {"sent": 0, "flagged_review_or_block": 0},
        "STEALTH": {"sent": 0, "flagged_review_or_block": 0},
    }

    first_sim_time: Optional[str] = None
    last_sim_time: Optional[str] = None
    recorded_transactions: List[Dict[str, Any]] = []

    logger.info("=" * 60)
    logger.info("Starting PayGuard Transaction Simulator")
    logger.info(f"Target URL: {api_base_url}/api/transactions")
    logger.info(f"Config: count={args.count}, duration={args.duration}, tps={args.tps}, seed={args.seed}, accounts={args.accounts}, fraud_rate={args.fraud_rate}")
    logger.info(f"Initial simulated time: {initial_sim_time.isoformat()}")
    logger.info("=" * 60)

    start_wall_time = time.monotonic()
    tx_index = 0

    progress_step = 10
    if args.count:
        progress_step = max(1, min(10, args.count // 10))

    try:
        while True:
            # Termination check
            if args.count is not None and tx_index >= args.count:
                break
            if args.duration is not None and (time.monotonic() - start_wall_time) >= args.duration:
                break

            # Rate limiting pacing
            expected_dispatch = start_wall_time + (tx_index / args.tps)
            sleep_duration = expected_dispatch - time.monotonic()
            if sleep_duration > 0:
                time.sleep(sleep_duration)

            payload, scenario, account = generator.next_transaction()
            if first_sim_time is None:
                first_sim_time = payload["transactionTimestamp"]
            last_sim_time = payload["transactionTimestamp"]

            # Save normalized transaction record for determinism proof
            recorded_transactions.append(
                {
                    "seq": tx_index + 1,
                    "accountId": payload["accountId"],
                    "scenario": scenario,
                    "amount": payload["amount"],
                    "currency": payload["currency"],
                    "deviceId": payload["deviceId"],
                    "location": payload["location"],
                    "merchantType": payload["merchantType"],
                    "transactionTimestamp": payload["transactionTimestamp"],
                }
            )

            # Send HTTP request
            headers = {
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            }
            tx_url = f"{api_base_url}/api/transactions"

            resp = None
            try:
                resp = session.post(tx_url, json=payload, headers=headers, timeout=10)
            except requests.RequestException as net_err:
                logger.warning(f"Network error on transaction {tx_index + 1} ({net_err}). Retrying once...")
                time.sleep(0.5)
                try:
                    resp = session.post(tx_url, json=payload, headers=headers, timeout=10)
                except requests.RequestException as retry_err:
                    logger.error(f"Retry failed for transaction {tx_index + 1}: {retry_err}")
                    total_sent += 1
                    total_failed += 1
                    tx_index += 1
                    continue

            # Handle 401 Unauthorized (attempt one re-login)
            if resp is not None and resp.status_code == 401:
                logger.warning(f"Received 401 Unauthorized for transaction {tx_index + 1}. Attempting re-login...")
                try:
                    token = login(session, api_base_url, username, password)
                    headers["Authorization"] = f"Bearer {token}"
                    resp = session.post(tx_url, json=payload, headers=headers, timeout=10)
                except Exception as auth_err:
                    logger.error(f"Re-login or retry failed: {auth_err}. Aborting.")
                    sys.exit(1)

                if resp.status_code in (401, 403):
                    logger.error(f"Request failed with {resp.status_code} even after re-login. Aborting.")
                    sys.exit(1)

            total_sent += 1
            tx_index += 1

            if resp is not None and resp.status_code in (200, 201):
                resp_data = resp.json()
                decision = resp_data.get("decision", "UNKNOWN")
                rule_score = resp_data.get("ruleScore", 0)
                ml_avail = resp_data.get("mlAvailable", False)

                if decision in decision_counts:
                    decision_counts[decision] += 1
                if ml_avail:
                    ml_available_count += 1

                scenario_breakdown[scenario]["sent"] += 1
                if decision in ("REVIEW", "BLOCK"):
                    scenario_breakdown[scenario]["flagged_review_or_block"] += 1

                # Update virtual account history on successful processing
                account.known_devices.add(payload["deviceId"])
                account.known_locations.add(payload["location"])
                account.transaction_count += 1

                # Periodic progress logging
                if tx_index % progress_step == 0 or (args.count and tx_index == args.count):
                    elapsed = time.monotonic() - start_wall_time
                    if args.count:
                        pct = (tx_index / args.count) * 100
                        logger.info(
                            f"[Progress] Sent {tx_index}/{args.count} ({pct:.1f}%) | "
                            f"{account.account_id} [{scenario}] -> {decision} (score={rule_score}, ML={ml_avail}) | "
                            f"Elapsed: {elapsed:.1f}s"
                        )
                    else:
                        logger.info(
                            f"[Progress] Sent {tx_index} txns | "
                            f"{account.account_id} [{scenario}] -> {decision} (score={rule_score}, ML={ml_avail}) | "
                            f"Elapsed: {elapsed:.1f}s"
                        )
            else:
                status_code = resp.status_code if resp is not None else "NO_RESPONSE"
                err_body = resp.text if resp is not None else ""
                logger.error(f"Transaction {tx_index} rejected [HTTP {status_code}]: {err_body}")
                total_failed += 1

    except KeyboardInterrupt:
        logger.info("\nSimulation stopped by user (Ctrl+C). Generating report for sent transactions...")

    total_wall_clock = round(time.monotonic() - start_wall_time, 2)
    successful_tx = total_sent - total_failed
    ml_rate = round(ml_available_count / successful_tx, 4) if successful_tx > 0 else 0.0

    report: Dict[str, Any] = {
        "run_config": {
            "count": args.count,
            "duration": args.duration,
            "tps": args.tps,
            "seed": args.seed,
            "accounts": args.accounts,
            "fraud_rate": args.fraud_rate,
            "sim_start": args.sim_start,
        },
        "total_sent": total_sent,
        "total_failed": total_failed,
        "decision_counts": decision_counts,
        "scenario_breakdown": scenario_breakdown,
        "ml_availability_rate": ml_rate,
        "wall_clock_duration_seconds": total_wall_clock,
        "simulated_time_range": {
            "start": first_sim_time or initial_sim_time.isoformat(),
            "end": last_sim_time or initial_sim_time.isoformat(),
        },
    }

    # Save last_run_report.json
    simulator_dir = Path(__file__).resolve().parent
    report_file = simulator_dir / "last_run_report.json"
    with open(report_file, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    # Save recorded transactions for determinism verification
    tx_file = simulator_dir / "last_run_transactions.json"
    with open(tx_file, "w", encoding="utf-8") as f:
        json.dump(recorded_transactions, f, indent=2)

    # Print summary report to console
    logger.info("=" * 60)
    logger.info("END-OF-RUN REPORT")
    logger.info("=" * 60)
    print(json.dumps(report, indent=2))
    logger.info("=" * 60)
    logger.info(f"Report saved to: {report_file}")
    logger.info(f"Transactions log saved to: {tx_file}")


if __name__ == "__main__":
    main()
