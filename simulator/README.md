# PayGuard Transaction Simulator

A standalone external Python client for simulating realistic transactional traffic and fraud scenarios against the PayGuard Spring Boot backend.

The simulator interacts exclusively with the public HTTP endpoints (`POST /api/auth/login` and `POST /api/transactions`) and performs no server-side feature calculations. All behavioral features, rule evaluations, and machine learning predictions are computed server-side by PayGuard.

---

## 1. Installation

Ensure Python 3.10+ is installed. Install the minimal dependencies:

```bash
cd simulator
pip install -r requirements.txt
```

Dependencies:
- `requests` — HTTP communication with PayGuard API.
- `python-dotenv` — Loads environment configuration from `.env`.

---

## 2. Configuration (.env)

Copy `.env.example` to `.env`:

```bash
cp .env.example .env
```

Default configuration in `.env`:

```env
API_BASE_URL=http://localhost:8080
SIMULATOR_USERNAME=admin
SIMULATOR_PASSWORD=adminPassword123
```

- `API_BASE_URL`: Base URL of the running Spring Boot backend.
- `SIMULATOR_USERNAME`: Username for an `ADMIN` or `ANALYST` user.
- `SIMULATOR_PASSWORD`: User password for JWT token generation.

The simulator authenticates once on startup, captures the JWT Bearer token, and attaches it as `Authorization: Bearer <token>` to all transaction requests. If an unexpected `401 Unauthorized` occurs mid-run, the simulator automatically performs a single re-login attempt and retries the request.

---

## 3. CLI Configuration Flags

| Flag | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `--count N` | Integer | None | Total number of transactions to send. Mutually exclusive with `--duration`. |
| `--duration SEC` | Float | None | Total wall-clock duration in seconds to run. Mutually exclusive with `--count`. |
| `--tps RATE` | Float | `2.0` | Target transactions-per-second send rate in real wall-clock time. |
| `--seed N` | Integer | `42` | Random seed for deterministic generation of accounts, scenarios, amounts, devices, locations, and timestamps. |
| `--accounts N` | Integer | `50` | Size of the virtual account pool. |
| `--sim-start ISO` | String | `2026-10-01T00:00:00+05:30` | Initial simulated clock time in IST. Accepts ISO-8601 string or `"now"`. |
| `--fraud-rate F` | Float | `0.015` | Probability of triggering a fraud episode per eligible transaction (`transaction_count >= 10`). |
| `--export-txns` | Flag | `False` | Also exports the full transaction history to `last_run_transactions.json`. |

---

## 4. Usage Examples

### Quick Baseline Run (Deterministic)
Send 100 baseline transactions at 5 TPS with seed 42:
```bash
python simulator.py --count 100 --tps 5 --seed 42
```

### Recommended Interactive Demo Run (Exercises All Fraud Scenarios)
For demo runs (~200–500 transactions), use a compact account pool (`--accounts 15`) and `--fraud-rate 0.03` so accounts quickly cross the 10-transaction cold-start floor and trigger all 5 fraud scenarios:
```bash
python simulator.py --count 300 --tps 10 --accounts 15 --fraud-rate 0.03 --seed 42
```

### High-Volume Test Run
Send 1000 transactions to test hybrid rules and ML across large pools:
```bash
python simulator.py --count 1000 --tps 20 --accounts 20 --fraud-rate 0.04 --seed 42
```

### Timed Soak Run
Run for 10 minutes (600 seconds) at 10 TPS with 50 accounts:
```bash
python simulator.py --duration 600 --tps 10 --accounts 50
```

### Live Traffic Simulation (Anchored to Real Time)
Run with simulated time anchored to current wall-clock IST time:
```bash
python simulator.py --count 50 --tps 2 --sim-start now
```

---

## 4a. Pool Sizing and Cold-Start Dynamics

The simulator enforces a strict **cold-start floor**: virtual accounts must accumulate at least **10 legitimate transactions** before becoming eligible for fraud episodes (mirroring the Phase 5d behavioral rule).

- **Default Pool (`--accounts 50`)**: Spreading a small transaction count (e.g. `--count 100`) across 50 accounts yields an average of only 2.0 transactions per account. Under this setting, 78% of accounts have 0–2 transactions, and only 1 account reaches 10+ transactions. Combined with `--fraud-rate 0.015`, there is only a single 1.5% fraud roll in the entire run (~98.5% chance of 0 fraud).
- **Demo Recommendation**: For quick demonstrations (`--count 200–500`), set `--accounts 15` and `--fraud-rate 0.03` so accounts reach the 10-transaction threshold early, allowing `ACCOUNT_TAKEOVER`, `CARD_TESTING`, `ODD_HOUR_TRANSFER`, `SUBTLE`, and `STEALTH` to trigger and display in the dashboard.
- **Large/Soak Runs**: For `--count >= 1500` or soak runs (`--duration 600`), the default `--accounts 50` works effectively as each account accumulates 30+ transactions.


---

## 5. Architectural Features

### Virtual Account Pool
- Maintained completely in-memory.
- Each account has a fixed home location (e.g., Delhi, Mumbai, Bangalore), home device, lognormally distributed median spending amount, and activity weight.
- Cold-start protection: Accounts must accumulate at least 10 baseline transactions before becoming eligible for fraud episodes.

### Simulated Clock
- Decoupled from wall-clock send rate (`--tps`).
- Operates in Indian Standard Time (`Asia/Kolkata`, UTC+05:30).
- Advances realistically between transactions (e.g., 30s–10m for normal traffic, bursts of 8–14s for card testing, jumps to odd hours 00:00–05:59 IST for odd-hour transfers).

### Scenarios
1. **`LEGIT`**: Baseline traffic using known devices, home locations, normal daytime hours, and median amounts with realistic noise (occasional travel or new device).
2. **`ACCOUNT_TAKEOVER`**: 1–3 transactions within ~30 simulated minutes, new device, new location, elevated odd-hour chance, high amount (3–10x median).
3. **`CARD_TESTING`**: 5–8 transactions within ~90 simulated seconds with small amounts (0.02–0.30x median), ending optionally with a larger transaction. Triggers `VELOCITY` rule.
4. **`ODD_HOUR_TRANSFER`**: 1–2 transactions in odd hours (00:00–05:59 IST), known device and location, high amount (2.5–6x median). Triggers `ODD_HOUR_HIGH_VALUE`.
5. **`SUBTLE`**: 1–2 transactions calibrated to stay strictly below rule engine thresholds (e.g. ratio < 3.0x, ratio < 2.0x in odd hour).
6. **`STEALTH`**: 1 transaction statistically identical to normal legit traffic to demonstrate transactions that bypass heuristic rules.

---

## 6. End-of-Run Report (`last_run_report.json`)

At the conclusion of each simulation run, results are output to the terminal and saved to `simulator/last_run_report.json`:

```json
{
  "run_config": {
    "count": 100,
    "duration": null,
    "tps": 5.0,
    "seed": 42,
    "accounts": 50,
    "fraud_rate": 0.015,
    "sim_start": "2026-10-01T00:00:00+05:30"
  },
  "total_sent": 100,
  "total_failed": 0,
  "decision_counts": {
    "APPROVE": 96,
    "REVIEW": 4,
    "BLOCK": 0
  },
  "scenario_breakdown": {
    "LEGIT": {
      "sent": 100,
      "flagged_review_or_block": 4
    },
    "ACCOUNT_TAKEOVER": {
      "sent": 0,
      "flagged_review_or_block": 0
    },
    "CARD_TESTING": {
      "sent": 0,
      "flagged_review_or_block": 0
    },
    "ODD_HOUR_TRANSFER": {
      "sent": 0,
      "flagged_review_or_block": 0
    },
    "SUBTLE": {
      "sent": 0,
      "flagged_review_or_block": 0
    },
    "STEALTH": {
      "sent": 0,
      "flagged_review_or_block": 0
    }
  },
  "ml_availability_rate": 1.0,
  "wall_clock_duration_seconds": 20.08,
  "simulated_time_range": {
    "start": "2026-10-01T00:04:12+05:30",
    "end": "2026-10-01T15:23:45+05:30"
  }
}
```

- `total_sent`: Total transactions submitted to the API.
- `total_failed`: Number of failed or unrecoverable HTTP requests.
- `decision_counts`: Aggregated server decisions (`APPROVE`, `REVIEW`, `BLOCK`).
- `scenario_breakdown`: Transaction volume and alert counts per scenario.
- `ml_availability_rate`: Fraction of responses where the ML service successfully returned predictions.
- `wall_clock_duration_seconds`: Real time taken to execute the simulation.
- `simulated_time_range`: Span of virtual time covered by the generated transactions.

---

## 6. Seeding Retraining Data

To generate ground-truth analyst verdicts from simulated traffic for model retraining, use the two-step flow:

### Step 1: Run Simulator with Transaction Export
Run the simulator with `--export-txns` to generate transactions and save payloads to `last_run_transactions.json`:
```bash
python simulator.py --count 300 --accounts 15 --fraud-rate 0.03 --seed 42 --export-txns
```

### Step 2: Seed Analyst Verdicts
Run `seed_verdicts.py` to match generated alerts to their simulated ground truth scenarios and submit analyst verdicts:
```bash
python seed_verdicts.py --txns-file last_run_transactions.json
```

The script:
1. Authenticates against PayGuard using credentials in `.env` (or `--username`/`--password`).
2. Fetches alerts from `GET /api/v1/alerts`.
3. Skips already verdicted alerts (`alert.verdict != null`).
4. Submits verdicts via `POST /api/v1/alerts/{id}/verdict`:
   - `FRAUD` for simulated fraud scenarios (`ACCOUNT_TAKEOVER`, `CARD_TESTING`, `ODD_HOUR_TRANSFER`, `SUBTLE`, `STEALTH`).
   - `LEGITIMATE` for `LEGIT` transactions.
5. Prints a summary of inspected alerts, submitted verdicts, and reports whether the cumulative database totals satisfy the retraining eligibility gate (`min-total-verdicts >= 20`, `min-per-class >= 5`).

