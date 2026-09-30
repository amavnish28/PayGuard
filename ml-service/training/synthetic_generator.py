"""Synthetic dataset generator for PayGuard Track 2.

Generates realistic behavioral transactions and evaluates the 11 contract features
using the reference implementation in strict arrival order (no timestamp sorting).
"""

from dataclasses import asdict
from datetime import datetime, timedelta
from decimal import Decimal
import json
from pathlib import Path
import time
from typing import Any, Dict, List, Optional, Tuple
from zoneinfo import ZoneInfo

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from training.features_reference import Txn, compute_features, evaluate_rules

# Directories
ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = ML_SERVICE_ROOT / "data" / "synthetic"
REPORT_DIR = ML_SERVICE_ROOT / "reports"
FIG_DIR = REPORT_DIR / "figures"

OUTPUT_CSV_PATH = DATA_DIR / "synthetic_v1.csv"
SUMMARY_JSON_PATH = REPORT_DIR / "synthetic_summary.json"
FEATURE_PLOT_PATH = FIG_DIR / "synthetic_features_by_class.png"

# Fixed Parameters
SEED = 42
N_ACCOUNTS = 6000
P_FRAUD = 0.28  # Calibrated per-account fraud probability to hit [0.8%, 1.5%] fraud rate
PERIOD_DAYS = 60
KOLKATA_TZ = ZoneInfo("Asia/Kolkata")
START_TIME = datetime(2026, 6, 1, 0, 0, 0, tzinfo=KOLKATA_TZ)

CITIES = ["Mumbai", "Delhi", "Bengaluru", "Hyderabad", "Chennai", "Kolkata", "Pune", "Ahmedabad"]
CITY_WEIGHTS = [0.24, 0.22, 0.16, 0.12, 0.10, 0.06, 0.05, 0.05]

CONTRACT_FEATURE_NAMES = [
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


def generate_account_raw_transactions(
    account_id: str,
    acc_idx: int,
    rng: np.random.Generator,
    p_fraud: float,
) -> List[Dict[str, Any]]:
    """Generate raw incoming transactions for a single account in arrival order."""
    home_city = rng.choice(CITIES, p=CITY_WEIGHTS)
    n_dev = rng.choice([1, 2, 3], p=[0.60, 0.30, 0.10])
    dev_ids = [f"DEV_{account_id}_{d}" for d in range(1, n_dev + 1)]
    account_median = float(rng.lognormal(mean=np.log(1500), sigma=0.8))
    account_median = max(50.0, account_median)
    is_night_owl = bool(rng.random() < 0.10)
    activity_rate = float(rng.gamma(shape=3.0, scale=0.4))
    n_legit = max(12, int(round(activity_rate * PERIOD_DAYS)))

    account_txns: List[Dict[str, Any]] = []
    cur_ts = START_TIME + timedelta(seconds=float(rng.uniform(0, 3600 * 12)))
    active_city = home_city
    travel_until = cur_ts

    for k in range(n_legit):
        gap_seconds = float(rng.exponential(scale=(PERIOD_DAYS * 86400 / n_legit)))
        gap_seconds = max(120.0, gap_seconds)
        cur_ts = cur_ts + timedelta(seconds=gap_seconds)
        if cur_ts > START_TIME + timedelta(days=PERIOD_DAYS - 1, hours=23, minutes=59):
            cur_ts = START_TIME + timedelta(days=PERIOD_DAYS - 1, hours=23, minutes=int(rng.integers(0, 59)))

        is_travel = (rng.random() < 0.02)
        if is_travel:
            other_cities = [c for c in CITIES if c != home_city]
            active_city = rng.choice(other_cities)
            travel_until = cur_ts + timedelta(days=float(rng.integers(1, 4)))
        elif cur_ts > travel_until:
            active_city = home_city

        is_new_dev = (rng.random() < 0.015)
        device = f"DEV_{account_id}_guest_{rng.integers(10, 99)}" if is_new_dev else dev_ids[0]

        is_big_purchase = (rng.random() < 0.02)
        is_burst = (rng.random() < 0.004)

        if is_night_owl:
            if rng.random() < 0.40:
                h = int(rng.choice([23, 0, 1, 2, 3, 4, 5]))
            else:
                h = int(rng.integers(8, 23))
        else:
            if rng.random() < 0.96:
                h = int(rng.integers(8, 23))
            else:
                h = int(rng.choice([6, 7, 23]))

        cur_ts = cur_ts.replace(hour=h, minute=int(rng.integers(0, 60)), second=int(rng.integers(0, 60)))

        # Deliberate out-of-order arrival for determinism and arrival-order verification tests
        if acc_idx == 7 and k == 5 and len(account_txns) > 0:
            cur_ts = account_txns[-1]["ts"] - timedelta(minutes=10)

        base_amt = account_median * float(rng.lognormal(0, 0.5))
        if is_big_purchase:
            base_amt *= float(rng.uniform(3.0, 8.0))
            cur_ts = cur_ts.replace(hour=int(rng.integers(10, 19)))

        amt = max(Decimal("1.00"), Decimal(f"{base_amt:.2f}"))

        if is_burst:
            burst_len = int(rng.integers(5, 8))
            burst_ts = cur_ts
            for b_idx in range(burst_len):
                b_amt = max(Decimal("1.00"), Decimal(f"{(base_amt * float(rng.uniform(0.2, 1.0))):.2f}"))
                account_txns.append({
                    "account_id": account_id,
                    "txn_id": f"TXN_{account_id}_{len(account_txns)+1:04d}",
                    "amount": b_amt,
                    "device_id": device,
                    "location": active_city,
                    "ts": burst_ts,
                    "is_fraud": 0,
                    "scenario": "LEGIT",
                })
                burst_ts = burst_ts + timedelta(seconds=float(rng.uniform(5, 15)))
            cur_ts = burst_ts
        else:
            account_txns.append({
                "account_id": account_id,
                "txn_id": f"TXN_{account_id}_{len(account_txns)+1:04d}",
                "amount": amt,
                "device_id": device,
                "location": active_city,
                "ts": cur_ts,
                "is_fraud": 0,
                "scenario": "LEGIT",
            })

    # Fraud episodes: only AFTER the 10th legit transaction
    has_fraud = (rng.random() < p_fraud) and (len(account_txns) >= 12)
    if has_fraud:
        ep_type = rng.choice(
            ["ACCOUNT_TAKEOVER", "CARD_TESTING", "ODD_HOUR_TRANSFER", "SUBTLE", "STEALTH"],
            p=[0.30, 0.20, 0.20, 0.20, 0.10]
        )
        insert_pos = int(rng.integers(10, len(account_txns)))
        ref_txn = account_txns[insert_pos - 1]
        ref_ts = ref_txn["ts"]

        fraud_batch: List[Dict[str, Any]] = []
        if ep_type == "ACCOUNT_TAKEOVER":
            n_ep = int(rng.integers(1, 4))
            f_dev = f"DEV_ATO_{account_id}_{rng.integers(100, 999)}"
            f_loc = rng.choice([c for c in CITIES if c != home_city]) if rng.random() < 0.90 else home_city
            f_ts = ref_ts + timedelta(minutes=float(rng.integers(15, 60)))
            if rng.random() < 0.60:
                f_ts = f_ts.replace(hour=int(rng.choice([23, 0, 1, 2, 3, 4, 5])))
            for ep_idx in range(n_ep):
                f_amt = max(Decimal("1.00"), Decimal(f"{(account_median * float(rng.uniform(3.0, 10.0))):.2f}"))
                fraud_batch.append({
                    "account_id": account_id,
                    "txn_id": f"TXN_{account_id}_FR_{ep_idx+1}",
                    "amount": f_amt,
                    "device_id": f_dev,
                    "location": f_loc,
                    "ts": f_ts,
                    "is_fraud": 1,
                    "scenario": ep_type,
                })
                f_ts = f_ts + timedelta(minutes=float(rng.uniform(3, 10)))

        elif ep_type == "CARD_TESTING":
            n_ep = int(rng.integers(5, 9))
            f_dev = f"DEV_CT_{account_id}_{rng.integers(100, 999)}" if rng.random() < 0.50 else dev_ids[0]
            f_ts = ref_ts + timedelta(minutes=float(rng.integers(10, 40)))
            for ep_idx in range(n_ep):
                f_amt = max(Decimal("0.50"), Decimal(f"{(account_median * float(rng.uniform(0.02, 0.30))):.2f}"))
                fraud_batch.append({
                    "account_id": account_id,
                    "txn_id": f"TXN_{account_id}_FR_{ep_idx+1}",
                    "amount": f_amt,
                    "device_id": f_dev,
                    "location": home_city,
                    "ts": f_ts,
                    "is_fraud": 1,
                    "scenario": ep_type,
                })
                f_ts = f_ts + timedelta(seconds=float(rng.uniform(5, 12)))
            if rng.random() < 0.50:
                large_amt = max(Decimal("1.00"), Decimal(f"{(account_median * float(rng.uniform(2.0, 6.0))):.2f}"))
                fraud_batch.append({
                    "account_id": account_id,
                    "txn_id": f"TXN_{account_id}_FR_FINAL",
                    "amount": large_amt,
                    "device_id": f_dev,
                    "location": home_city,
                    "ts": f_ts + timedelta(seconds=5),
                    "is_fraud": 1,
                    "scenario": ep_type,
                })

        elif ep_type == "ODD_HOUR_TRANSFER":
            n_ep = int(rng.integers(1, 3))
            f_ts = (ref_ts + timedelta(days=1)).replace(hour=int(rng.integers(0, 6)), minute=int(rng.integers(0, 60)))
            for ep_idx in range(n_ep):
                f_amt = max(Decimal("1.00"), Decimal(f"{(account_median * float(rng.uniform(2.5, 6.0))):.2f}"))
                fraud_batch.append({
                    "account_id": account_id,
                    "txn_id": f"TXN_{account_id}_FR_{ep_idx+1}",
                    "amount": f_amt,
                    "device_id": dev_ids[0],
                    "location": home_city,
                    "ts": f_ts,
                    "is_fraud": 1,
                    "scenario": ep_type,
                })
                f_ts = f_ts + timedelta(minutes=float(rng.uniform(5, 15)))

        elif ep_type == "SUBTLE":
            n_ep = int(rng.integers(1, 3))
            f_ts = ref_ts + timedelta(minutes=float(rng.uniform(2, 9)))
            path_loc = (rng.random() < 0.50)
            if path_loc:
                f_loc = rng.choice([c for c in CITIES if c != home_city])
                amt_mult = float(rng.uniform(1.5, 2.9))
                f_ts = f_ts.replace(hour=int(rng.integers(10, 18)))
            else:
                f_loc = home_city
                amt_mult = float(rng.uniform(1.4, 1.99))
                f_ts = f_ts.replace(hour=int(rng.choice([23, 0, 1, 2, 3, 4, 5])))
            for ep_idx in range(n_ep):
                f_amt = max(Decimal("1.00"), Decimal(f"{(account_median * amt_mult):.2f}"))
                fraud_batch.append({
                    "account_id": account_id,
                    "txn_id": f"TXN_{account_id}_FR_{ep_idx+1}",
                    "amount": f_amt,
                    "device_id": dev_ids[0],
                    "location": f_loc,
                    "ts": f_ts,
                    "is_fraud": 1,
                    "scenario": ep_type,
                })
                f_ts = f_ts + timedelta(minutes=float(rng.uniform(1, 4)))

        elif ep_type == "STEALTH":
            f_ts = ref_ts + timedelta(hours=float(rng.uniform(1, 6)))
            f_ts = f_ts.replace(hour=int(rng.integers(10, 18)))
            f_amt = max(Decimal("1.00"), Decimal(f"{(account_median * float(rng.lognormal(0, 0.5))):.2f}"))
            fraud_batch.append({
                "account_id": account_id,
                "txn_id": f"TXN_{account_id}_FR_1",
                "amount": f_amt,
                "device_id": dev_ids[0],
                "location": home_city,
                "ts": f_ts,
                "is_fraud": 1,
                "scenario": ep_type,
            })

        account_txns = account_txns[:insert_pos] + fraud_batch + account_txns[insert_pos:]

    return account_txns


def generate_synthetic_data(
    n_accounts: int = N_ACCOUNTS,
    p_fraud: float = P_FRAUD,
    seed: int = SEED,
) -> Tuple[pd.DataFrame, Dict[str, Any], int]:
    """Generate the full synthetic dataset with feature calculation in arrival order.

    Returns:
        (df_transactions, summary_dict, split_seed_used)
    """
    rng = np.random.default_rng(seed)
    account_ids = [f"ACC_{i+1:06d}" for i in range(n_accounts)]

    accounts_rows: List[List[Dict[str, Any]]] = []
    fraud_counts_per_account: Dict[str, int] = {}

    total_txns = 0
    total_fraud = 0

    rules_any_on_fraud = 0
    rules_any_on_legit = 0
    rules_30_on_fraud = 0
    rules_30_on_legit = 0

    scenario_counts: Dict[str, int] = {
        "LEGIT": 0,
        "ACCOUNT_TAKEOVER": 0,
        "CARD_TESTING": 0,
        "ODD_HOUR_TRANSFER": 0,
        "SUBTLE": 0,
        "STEALTH": 0,
    }
    scenario_rules_any: Dict[str, int] = {k: 0 for k in scenario_counts}

    sum_features = {
        "legit": {"amount": 0.0, "amount_ratio": 0.0, "transactions_last_2_min": 0.0, "transactions_last_1_hour": 0.0},
        "fraud": {"amount": 0.0, "amount_ratio": 0.0, "transactions_last_2_min": 0.0, "transactions_last_1_hour": 0.0},
    }

    # Generate each account's transactions and evaluate features in ARRIVAL order
    for acc_idx, account_id in enumerate(account_ids):
        raw_txns = generate_account_raw_transactions(account_id, acc_idx, rng, p_fraud)
        history: List[Txn] = []
        acc_processed: List[Dict[str, Any]] = []
        acc_fraud = 0

        for raw in raw_txns:
            txn = Txn(
                account_id=raw["account_id"],
                txn_id=raw["txn_id"],
                amount=raw["amount"],
                device_id=raw["device_id"],
                location=raw["location"],
                timestamp=raw["ts"],
            )
            # Feature calculation uses arrival order, never timestamp sorting
            features = compute_features(history, txn)
            score, rules = evaluate_rules(features)
            history.append(txn)

            is_fr = raw["is_fraud"]
            scen = raw["scenario"]
            total_txns += 1
            if is_fr == 1:
                total_fraud += 1
                acc_fraud += 1

            has_any = (len(rules) > 0)
            has_30 = (score >= 30)

            if is_fr == 1:
                if has_any: rules_any_on_fraud += 1
                if has_30: rules_30_on_fraud += 1
                sum_features["fraud"]["amount"] += features["amount"]
                sum_features["fraud"]["amount_ratio"] += features["amount_ratio"]
                sum_features["fraud"]["transactions_last_2_min"] += features["transactions_last_2_min"]
                sum_features["fraud"]["transactions_last_1_hour"] += features["transactions_last_1_hour"]
            else:
                if has_any: rules_any_on_legit += 1
                if has_30: rules_30_on_legit += 1
                sum_features["legit"]["amount"] += features["amount"]
                sum_features["legit"]["amount_ratio"] += features["amount_ratio"]
                sum_features["legit"]["transactions_last_2_min"] += features["transactions_last_2_min"]
                sum_features["legit"]["transactions_last_1_hour"] += features["transactions_last_1_hour"]

            scenario_counts[scen] += 1
            if has_any:
                scenario_rules_any[scen] += 1

            row_dict = {
                "account_id": raw["account_id"],
                "txn_id": raw["txn_id"],
                "timestamp": raw["ts"].isoformat(),
                **features,
                "is_fraud": is_fr,
                "scenario": scen,
                "rule_score": score,
                "rules_triggered": rules,
            }
            acc_processed.append(row_dict)

        fraud_counts_per_account[account_id] = acc_fraud
        accounts_rows.append(acc_processed)

    overall_fraud_rate = total_fraud / total_txns if total_txns else 0.0

    # Split BY ACCOUNT: 70/15/15
    split_seed = SEED
    n_acc = len(account_ids)
    n_train = int(round(0.70 * n_acc))
    n_val = int(round(0.15 * n_acc))

    train_accs = set()
    val_accs = set()
    test_accs = set()
    split_stats = {}

    while True:
        shuffled = np.random.default_rng(split_seed).permutation(account_ids)
        cur_train_accs = set(shuffled[:n_train])
        cur_val_accs = set(shuffled[n_train:n_train+n_val])
        cur_test_accs = set(shuffled[n_train+n_val:])

        train_rows_cnt = sum(len(acc) for acc in accounts_rows if acc[0]["account_id"] in cur_train_accs)
        val_rows_cnt = sum(len(acc) for acc in accounts_rows if acc[0]["account_id"] in cur_val_accs)
        test_rows_cnt = sum(len(acc) for acc in accounts_rows if acc[0]["account_id"] in cur_test_accs)

        train_fr_cnt = sum(fraud_counts_per_account[a] for a in cur_train_accs)
        val_fr_cnt = sum(fraud_counts_per_account[a] for a in cur_val_accs)
        test_fr_cnt = sum(fraud_counts_per_account[a] for a in cur_test_accs)

        train_rate = train_fr_cnt / train_rows_cnt if train_rows_cnt else 0.0
        val_rate = val_fr_cnt / val_rows_cnt if val_rows_cnt else 0.0
        test_rate = test_fr_cnt / test_rows_cnt if test_rows_cnt else 0.0

        # For small n_accounts (e.g. testing n=50), min fraud threshold is relaxed proportionally
        min_fr_required = 300 if n_accounts >= 1000 else 1
        cond_min_fraud = (train_fr_cnt >= min_fr_required and val_fr_cnt >= min_fr_required and test_fr_cnt >= min_fr_required)
        cond_diff = (
            abs(train_rate - overall_fraud_rate) <= 0.003 and
            abs(val_rate - overall_fraud_rate) <= 0.003 and
            abs(test_rate - overall_fraud_rate) <= 0.003
        ) if n_accounts >= 1000 else True

        if cond_min_fraud and cond_diff:
            train_accs = cur_train_accs
            val_accs = cur_val_accs
            test_accs = cur_test_accs
            split_stats = {
                "train": {"total": train_rows_cnt, "fraud": train_fr_cnt, "fraud_rate": round(train_rate, 5)},
                "val": {"total": val_rows_cnt, "fraud": val_fr_cnt, "fraud_rate": round(val_rate, 5)},
                "test": {"total": test_rows_cnt, "fraud": test_fr_cnt, "fraud_rate": round(test_rate, 5)},
            }
            break
        split_seed += 1

    # Flatten into final rows with split assigned
    flat_rows: List[Dict[str, Any]] = []
    for acc in accounts_rows:
        acc_id = acc[0]["account_id"]
        if acc_id in train_accs:
            s_name = "train"
        elif acc_id in val_accs:
            s_name = "val"
        else:
            s_name = "test"

        for r in acc:
            r["split"] = s_name
            flat_rows.append(r)

    df = pd.DataFrame(flat_rows)

    # Compute summary metrics
    total_legit = total_txns - total_fraud
    recall_any = rules_any_on_fraud / total_fraud if total_fraud else 0.0
    prec_any = rules_any_on_fraud / (rules_any_on_fraud + rules_any_on_legit) if (rules_any_on_fraud + rules_any_on_legit) else 0.0
    fpr_any = rules_any_on_legit / total_legit if total_legit else 0.0

    recall_30 = rules_30_on_fraud / total_fraud if total_fraud else 0.0
    prec_30 = rules_30_on_fraud / (rules_30_on_fraud + rules_30_on_legit) if (rules_30_on_fraud + rules_30_on_legit) else 0.0
    fpr_30 = rules_30_on_legit / total_legit if total_legit else 0.0

    summary = {
        "total_rows": int(total_txns),
        "fraud_count": int(total_fraud),
        "fraud_rate": round(float(overall_fraud_rate), 5),
        "counts_per_scenario": {k: int(v) for k, v in scenario_counts.items()},
        "splits": split_stats,
        "per_scenario_any_rule_fraction": {
            k: round(scenario_rules_any[k] / scenario_counts[k], 4) if scenario_counts[k] else 0.0
            for k in scenario_counts
        },
        "rules_metrics_any_rule": {
            "recall": round(float(recall_any), 4),
            "precision": round(float(prec_any), 4),
            "false_positive_rate": round(float(fpr_any), 4),
        },
        "rules_metrics_score_ge_30": {
            "recall": round(float(recall_30), 4),
            "precision": round(float(prec_30), 4),
            "false_positive_rate": round(float(fpr_30), 4),
        },
        "mean_key_features_by_class": {
            "legit": {
                feat: round(sum_features["legit"][feat] / total_legit, 4) if total_legit else 0.0
                for feat in sum_features["legit"]
            },
            "fraud": {
                feat: round(sum_features["fraud"][feat] / total_fraud, 4) if total_fraud else 0.0
                for feat in sum_features["fraud"]
            },
        },
    }

    return df, summary, split_seed


def plot_features_by_class(df: pd.DataFrame, output_path: Path) -> None:
    """Generate and save density plots of amount_ratio, transactions_last_2_min, transaction_hour by class."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fig, axes = plt.subplots(1, 3, figsize=(16, 4.5))

    legit = df[df["is_fraud"] == 0]
    fraud = df[df["is_fraud"] == 1]

    # 1. amount_ratio (log scale / clipped to 10 for density clarity)
    ax1 = axes[0]
    ratio_legit = np.clip(legit["amount_ratio"].to_numpy(), 0, 10)
    ratio_fraud = np.clip(fraud["amount_ratio"].to_numpy(), 0, 10)
    bins = np.linspace(0, 10, 40)
    ax1.hist(ratio_legit, bins=bins, density=True, alpha=0.5, label="Legit", color="#2b5c8f")
    ax1.hist(ratio_fraud, bins=bins, density=True, alpha=0.5, label="Fraud", color="#d95f02")
    ax1.set_title("amount_ratio (clipped at 10)")
    ax1.set_xlabel("amount_ratio")
    ax1.set_ylabel("Density")
    ax1.legend()
    ax1.grid(True, linestyle="--", alpha=0.5)

    # 2. transactions_last_2_min
    ax2 = axes[1]
    t2_legit = legit["transactions_last_2_min"].to_numpy()
    t2_fraud = fraud["transactions_last_2_min"].to_numpy()
    max_val = max(10, int(df["transactions_last_2_min"].max()))
    bins2 = np.arange(0, min(15, max_val + 2)) - 0.5
    ax2.hist(t2_legit, bins=bins2, density=True, alpha=0.5, label="Legit", color="#2b5c8f")
    ax2.hist(t2_fraud, bins=bins2, density=True, alpha=0.5, label="Fraud", color="#d95f02")
    ax2.set_title("transactions_last_2_min")
    ax2.set_xlabel("transactions_last_2_min")
    ax2.set_ylabel("Density")
    ax2.legend()
    ax2.grid(True, linestyle="--", alpha=0.5)

    # 3. transaction_hour
    ax3 = axes[2]
    h_legit = legit["transaction_hour"].to_numpy()
    h_fraud = fraud["transaction_hour"].to_numpy()
    bins3 = np.arange(0, 25) - 0.5
    ax3.hist(h_legit, bins=bins3, density=True, alpha=0.5, label="Legit", color="#2b5c8f")
    ax3.hist(h_fraud, bins=bins3, density=True, alpha=0.5, label="Fraud", color="#d95f02")
    ax3.set_title("transaction_hour (Asia/Kolkata)")
    ax3.set_xlabel("transaction_hour")
    ax3.set_ylabel("Density")
    ax3.set_xticks(range(0, 24, 2))
    ax3.legend()
    ax3.grid(True, linestyle="--", alpha=0.5)

    plt.tight_layout()
    plt.savefig(output_path, dpi=200)
    plt.close()


def main() -> None:
    """Generate synthetic dataset, save outputs, and evaluate sanity gates."""
    print("=" * 60)
    print("PayGuard Track 2: Synthetic Dataset Generation")
    print("=" * 60)
    t_start = time.time()

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    FIG_DIR.mkdir(parents=True, exist_ok=True)

    print(f"Generating synthetic dataset with {N_ACCOUNTS} accounts (seed={SEED}, p_fraud={P_FRAUD})...")
    df, summary, split_seed = generate_synthetic_data(n_accounts=N_ACCOUNTS, p_fraud=P_FRAUD, seed=SEED)
    gen_duration = time.time() - t_start
    summary["generation_runtime_seconds"] = round(gen_duration, 2)
    summary["split_seed_used"] = split_seed

    # Order columns as required by contract:
    # account_id, txn_id, timestamp, the 11 features in contract order, is_fraud, scenario, split
    csv_columns = [
        "account_id",
        "txn_id",
        "timestamp",
        *CONTRACT_FEATURE_NAMES,
        "is_fraud",
        "scenario",
        "split",
    ]
    df_out = df[csv_columns]

    print(f"Writing dataset CSV to {OUTPUT_CSV_PATH}...")
    df_out.to_csv(OUTPUT_CSV_PATH, index=False)

    print(f"Writing summary JSON to {SUMMARY_JSON_PATH}...")
    with open(SUMMARY_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    print(f"Plotting feature distributions to {FEATURE_PLOT_PATH}...")
    plot_features_by_class(df, FEATURE_PLOT_PATH)

    print("\n" + "=" * 60)
    print("GENERATION SUMMARY & SANITY GATES")
    print("=" * 60)
    fraud_rate = summary["fraud_rate"]
    recall = summary["rules_metrics_any_rule"]["recall"]
    precision = summary["rules_metrics_any_rule"]["precision"]

    gate_fraud_rate = (0.008 <= fraud_rate <= 0.015)
    gate_recall = (0.40 <= recall <= 0.85)
    gate_precision = (precision < 0.90)

    print(f"Total Rows:               {summary['total_rows']}")
    print(f"Fraud Rows:               {summary['fraud_count']}")
    print(f"Generation Runtime:       {gen_duration:.2f} s")
    print(f"Split Seed Used:          {split_seed}")
    print(f"Gate 1 - Fraud Rate:      {fraud_rate*100:.3f}% (Required: [0.8%, 1.5%]) -> {'PASS' if gate_fraud_rate else 'FAIL'}")
    print(f"Gate 2 - Rules Recall:    {recall*100:.2f}% (Required: [40.0%, 85.0%]) -> {'PASS' if gate_recall else 'FAIL'}")
    print(f"Gate 3 - Rules Precision: {precision*100:.2f}% (Required: < 90.0%) -> {'PASS' if gate_precision else 'FAIL'}")
    print("=" * 60)

    if not (gate_fraud_rate and gate_recall and gate_precision):
        print("SANITY GATE FAILED! Please inspect actual values above.")
        exit(1)


if __name__ == "__main__":
    main()
