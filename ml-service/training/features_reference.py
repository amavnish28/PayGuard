"""Reference implementation of PayGuard behavioral feature calculation and fraud rules engine.

Mirrors Phase 4 Java BehavioralFeatureService and FraudRulesEngine exactly.
Feature calculation uses arrival order without timestamp sorting.
"""

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Dict, List, Tuple
from zoneinfo import ZoneInfo

KOLKATA_TZ = ZoneInfo("Asia/Kolkata")
MAX_AMOUNT_RATIO = Decimal("999999.9999")
ZERO_AVG = Decimal("0.00")
ZERO_RATIO = Decimal("0.0000")


@dataclass
class Txn:
    """Represents a transaction for behavioral feature calculation."""

    account_id: str
    txn_id: str
    amount: Decimal
    device_id: str
    location: str
    timestamp: datetime

    def __post_init__(self) -> None:
        if not isinstance(self.amount, Decimal):
            self.amount = Decimal(str(self.amount))
        if self.timestamp.tzinfo is None:
            raise ValueError(f"Transaction timestamp must be timezone-aware: {self.timestamp}")


def compute_features(history: List[Txn], current: Txn) -> Dict[str, Any]:
    """Calculate the 11 contract features for `current` given its account `history`.

    History passed must reflect the exact arrival order in which transactions
    were generated/stored. No timestamp sorting is performed.

    Returns:
        Dict with 11 contract features in canonical contract order.
    """
    # Exclude current transaction itself and exclude stored rows with timestamp > current.timestamp
    # Arrival order of valid transactions is preserved without sorting.
    valid_history = [
        t for t in history
        if t.account_id == current.account_id
        and t.txn_id != current.txn_id
        and t.timestamp <= current.timestamp
    ]

    has_history = len(valid_history) > 0

    # 1. amount
    amount_val = float(current.amount)

    # 2. transactions_last_2_min (inclusive: [current - 2min, current])
    two_min_ago = current.timestamp - timedelta(minutes=2)
    count_2min = sum(1 for t in valid_history if t.timestamp >= two_min_ago)

    # 3. transactions_last_1_hour (inclusive: [current - 1hr, current])
    one_hour_ago = current.timestamp - timedelta(hours=1)
    count_1hour = sum(1 for t in valid_history if t.timestamp >= one_hour_ago)

    # 4. account_avg_amount (mean of all history amounts, rounded 2 dp HALF_UP)
    if not has_history:
        avg_dec = ZERO_AVG
    else:
        total_hist_amount = sum((t.amount for t in valid_history), Decimal("0"))
        avg_dec = (total_hist_amount / Decimal(len(valid_history))).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP
        )
    account_avg_amount_val = float(avg_dec)

    # 5. amount_ratio (amount / rounded avg, 4 dp HALF_UP, capped at 999999.9999)
    if avg_dec == ZERO_AVG:
        ratio_dec = ZERO_RATIO
    else:
        ratio_dec = (current.amount / avg_dec).quantize(
            Decimal("0.0001"), rounding=ROUND_HALF_UP
        )
        if ratio_dec > MAX_AMOUNT_RATIO:
            ratio_dec = MAX_AMOUNT_RATIO
    amount_ratio_val = float(ratio_dec)

    # 6. time_since_previous_seconds (max timestamp, ties broken by latest arrival)
    if not has_history:
        time_since_previous = -1.0
    else:
        # Find previous transaction with maximum timestamp, tie-breaking on latest arrival index
        best_idx, prev_txn = max(
            enumerate(valid_history),
            key=lambda item: (item[1].timestamp, item[0])
        )
        elapsed_seconds = int((current.timestamp - prev_txn.timestamp).total_seconds())
        time_since_previous = float(max(0, elapsed_seconds))

    # 7. is_first_transaction
    is_first = 1 if not has_history else 0

    # 8. new_device (TRUE if not in history, including cold start)
    device_seen = any(t.device_id == current.device_id for t in valid_history)
    new_device = 0 if device_seen else 1

    # 9. new_location (TRUE if not in history, including cold start)
    loc_seen = any(t.location == current.location for t in valid_history)
    new_location = 0 if loc_seen else 1

    # 10. transaction_hour (instant converted to Asia/Kolkata business zone)
    kolkata_dt = current.timestamp.astimezone(KOLKATA_TZ)
    txn_hour = kolkata_dt.hour

    # 11. odd_hour (hour >= 23 or hour < 6)
    odd_hour = 1 if (txn_hour >= 23 or txn_hour < 6) else 0

    return {
        "amount": amount_val,
        "transactions_last_2_min": int(count_2min),
        "transactions_last_1_hour": int(count_1hour),
        "account_avg_amount": account_avg_amount_val,
        "amount_ratio": amount_ratio_val,
        "time_since_previous_seconds": time_since_previous,
        "is_first_transaction": int(is_first),
        "new_device": int(new_device),
        "new_location": int(new_location),
        "transaction_hour": int(txn_hour),
        "odd_hour": int(odd_hour),
    }


def evaluate_rules(features: Dict[str, Any]) -> Tuple[int, List[str]]:
    """Evaluate deterministic fraud detection rules against calculated features.

    Rules and scores:
    - VELOCITY (last_2_min + 1 >= 5): 30
    - HIGH_AMOUNT (avg > 0 and ratio >= 3.0): 25
    - NEW_DEVICE (new_device and has history): 15
    - NEW_LOCATION (new_location and has history): 15
    - ODD_HOUR_HIGH_VALUE (odd_hour and avg > 0 and ratio >= 2.0): 15
    Total score is capped at 100.

    Returns:
        (rule_score, triggered_rule_names)
    """
    triggered: List[str] = []
    total_score = 0

    # 1. VELOCITY (30): 5 or more transactions within 2 minutes (history + current)
    if (features["transactions_last_2_min"] + 1) >= 5:
        total_score += 30
        triggered.append("VELOCITY")

    # 2. HIGH_AMOUNT (25): transaction amount is at least 3x the account historical average
    if features["account_avg_amount"] > 0 and features["amount_ratio"] >= 3.0:
        total_score += 25
        triggered.append("HIGH_AMOUNT")

    # 3. NEW_DEVICE (15): device not seen before, account has history
    if features["new_device"] == 1 and features["is_first_transaction"] == 0:
        total_score += 15
        triggered.append("NEW_DEVICE")

    # 4. NEW_LOCATION (15): location not seen before, account has history
    if features["new_location"] == 1 and features["is_first_transaction"] == 0:
        total_score += 15
        triggered.append("NEW_LOCATION")

    # 5. ODD_HOUR_HIGH_VALUE (15): odd hour and amount >= 2x account historical average
    if (
        features["odd_hour"] == 1
        and features["account_avg_amount"] > 0
        and features["amount_ratio"] >= 2.0
    ):
        total_score += 15
        triggered.append("ODD_HOUR_HIGH_VALUE")

    capped_score = min(100, total_score)
    return capped_score, triggered
