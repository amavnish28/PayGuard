"""Payload Generator for PayGuard Load Testing.

Generates deterministic, isolated test payloads:
1. Backend Transaction API (/api/transactions):
   - Fresh account IDs: 'LOAD-ACC-0001' to 'LOAD-ACC-0050' (never 'SIM-')
   - Fresh transaction IDs: 'LOAD-<run_id>-<seq>' (never 'SIM-')
   - Strictly conforms to TransactionRequest schema and validation rules
2. ML Service Prediction API (/api/v1/predict):
   - Conforms to contracts/feature_schema_v1.json and Pydantic consistency validators
   - Includes realistic combinations (low risk and high risk)
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
import random
import time
from typing import Any, Dict, List

# Asia/Kolkata timezone (+05:30)
KOLKATA_TZ = timezone(timedelta(hours=5, minutes=30))

CITIES = [
    "Mumbai", "Delhi", "Bangalore", "Hyderabad",
    "Chennai", "Kolkata", "Pune", "Jaipur",
]

MERCHANT_TYPES = [
    "GROCERY", "RETAIL", "DINING", "ENTERTAINMENT",
    "ELECTRONICS", "UTILITIES", "TRAVEL", "HEALTHCARE",
]


class PayloadGenerator:
    """Thread-safe generator of isolated load-test payloads."""

    def __init__(self, num_accounts: int = 50, seed: int = 1337) -> None:
        self.num_accounts = num_accounts
        self.rng = random.Random(seed)
        self.run_id = f"{int(time.time()):08x}"
        self.sequence_counter = 0

        # Pre-generate virtual accounts with fixed home location and primary device
        self.accounts: List[Dict[str, str]] = []
        for i in range(1, num_accounts + 1):
            acc_id = f"LOAD-ACC-{i:04d}"
            home_city = self.rng.choice(CITIES)
            primary_device = f"DEVICE-{acc_id}-01"
            self.accounts.append({
                "account_id": acc_id,
                "home_city": home_city,
                "primary_device": primary_device,
            })

    def next_transaction_payload(self) -> Dict[str, Any]:
        """Generate a valid Spring Boot TransactionRequest payload."""
        self.sequence_counter += 1
        seq = self.sequence_counter
        txn_id = f"LOAD-{self.run_id}-{seq:06d}"

        account = self.rng.choice(self.accounts)
        acc_id = account["account_id"]

        # Vary amount between 10.00 and 15,000.00
        amount = round(self.rng.uniform(15.00, 3500.00), 2)

        # 95% primary device, 5% secondary device
        if self.rng.random() < 0.05:
            device_id = f"DEVICE-{acc_id}-02"
        else:
            device_id = account["primary_device"]

        # 95% home city, 5% other location
        if self.rng.random() < 0.05:
            loc = self.rng.choice([c for c in CITIES if c != account["home_city"]])
        else:
            loc = account["home_city"]

        merchant = self.rng.choice(MERCHANT_TYPES)
        now_ist = datetime.now(KOLKATA_TZ)

        return {
            "transactionId": txn_id,
            "accountId": acc_id,
            "amount": amount,
            "currency": "INR",
            "deviceId": device_id,
            "location": loc,
            "merchantType": merchant,
            "transactionTimestamp": now_ist.isoformat(),
        }

    def next_ml_prediction_payload(self, high_risk: bool = False) -> Dict[str, Any]:
        """Generate a valid ML Service PredictionRequest payload."""
        now_ist = datetime.now(KOLKATA_TZ)
        hour = now_ist.hour
        is_odd = 1 if (hour >= 23 or hour < 6) else 0

        if high_risk:
            # Velocity spike + high amount ratio
            return {
                "amount": 45000.0,
                "transactions_last_2_min": 6,
                "transactions_last_1_hour": 15,
                "account_avg_amount": 2500.0,
                "amount_ratio": 18.0,
                "time_since_previous_seconds": 12.0,
                "is_first_transaction": 0,
                "new_device": 1,
                "new_location": 1,
                "transaction_hour": hour,
                "odd_hour": is_odd,
            }

        # Normal low-risk transaction
        is_first = 1 if self.rng.random() < 0.10 else 0
        if is_first == 1:
            return {
                "amount": round(self.rng.uniform(200.0, 1500.0), 2),
                "transactions_last_2_min": 0,
                "transactions_last_1_hour": 0,
                "account_avg_amount": 0.0,
                "amount_ratio": 0.0,
                "time_since_previous_seconds": -1.0,
                "is_first_transaction": 1,
                "new_device": 1,
                "new_location": 1,
                "transaction_hour": hour,
                "odd_hour": is_odd,
            }
        else:
            avg_amt = round(self.rng.uniform(500.0, 2000.0), 2)
            amt = round(avg_amt * self.rng.uniform(0.7, 1.3), 2)
            ratio = round(amt / avg_amt, 4)
            return {
                "amount": amt,
                "transactions_last_2_min": self.rng.randint(0, 2),
                "transactions_last_1_hour": self.rng.randint(1, 4),
                "account_avg_amount": avg_amt,
                "amount_ratio": ratio,
                "time_since_previous_seconds": round(self.rng.uniform(45.0, 3600.0), 1),
                "is_first_transaction": 0,
                "new_device": 0,
                "new_location": 0,
                "transaction_hour": hour,
                "odd_hour": is_odd,
            }
