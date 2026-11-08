# PayGuard Load Testing Suite

Comprehensive, reproducible, and strictly isolated performance and load-testing suite for the PayGuard fraud detection platform.

---

## 1. Safety & Database Isolation Guarantee

- **Target Database**: `payguard_loadtest` on PostgreSQL container `payguard-postgres` (port 5433).
- **Protected Database**: `payguard` (evaluation database with 10,000 transactions, 722 alerts, 5 audit logs).
- **Strict Enforcement**:
  - `loadtest/db_isolation_guard.py` verifies database identity before any load request is issued.
  - If the configured database is anything other than `payguard_loadtest`, execution aborts immediately.
  - Generates exclusive account IDs (`LOAD-ACC-xxxx`) and transaction IDs (`LOAD-xxxx-xxxx`), preventing data collision.
  - Post-test verification confirms zero `LOAD-` records exist in `payguard`.

---

## 2. Architecture & Components

```text
[Load Test Runner] (async httpx)
        |
        +---> [ML Component Benchmark] ----> FastAPI ML Service (/api/v1/predict) on :8001
        |
        +---> [End-to-End Pipeline]  ----> Spring Boot Backend (/api/transactions) on :8080
                                                    |
                                                    v
                                        PostgreSQL :5433 (payguard_loadtest)
```

1. **ML Service Component Benchmark**:
   - Directly tests `POST /api/v1/predict` on FastAPI.
   - Measures raw XGBoost inference and SHAP calculation latency under concurrent load.
2. **End-to-End Pipeline Benchmark**:
   - Authenticates against `POST /api/auth/login` to obtain a JWT Bearer token.
   - Tests `POST /api/transactions` on Spring Boot.
   - Exercises advisory locking, transaction persistence, behavioral feature derivation (11 features), rules engine evaluation, ML service REST call, hybrid decision calculation, and alert persistence.

---

## 3. Concurrency Stages & Safety Circuit Breakers

| Stage | Concurrency (Virtual Users) | Total Requests | Warm-up Requests |
| :--- | :---: | :---: | :---: |
| **Baseline** | 1 | 100 | 10 |
| **Light** | 5 | 250 | 25 |
| **Moderate** | 10 | 500 | 50 |
| **High** | 25 | 1,000 | 50 |
| **Stress** | 50 | 1,500 | 50 |

### Automatic Circuit Breakers:
- Escalation halts if the error rate (HTTP errors + timeouts) exceeds **5.0%**.
- Escalation halts if the 95th percentile latency exceeds **5,000 ms**.
- Stages 25 and 50 run only if the previous stage remains healthy.

---

## 4. Execution Instructions

### A. Environment Preparation (Stage D)

1. **FastAPI ML Service**:
   ```bash
   ml-service\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8001
   ```

2. **Spring Boot Backend**:
   Configure environment variables targeting `payguard_loadtest`:
   ```powershell
   $env:PAYGUARD_DB_URL = "jdbc:postgresql://localhost:5433/payguard_loadtest"
   $env:PAYGUARD_DB_USERNAME = "payguard"
   $env:PAYGUARD_DB_PASSWORD = "your_postgres_password"
   $env:PAYGUARD_JWT_SECRET = "loadtest-jwt-secret-key-at-least-32-characters-long"
   $env:PAYGUARD_ML_BASE_URL = "http://localhost:8001"
   $env:PAYGUARD_ADMIN_USERNAME = "loadtest_admin"
   $env:PAYGUARD_ADMIN_PASSWORD = "loadtestAdminPassword123"
   $env:PAYGUARD_ADMIN_EMAIL = "loadtest_admin@payguard.local"

   cd backend
   mvn spring-boot:run
   ```

### B. Running Load Tests

- **Run Full Load Test Suite (ML + E2E)**:
  ```powershell
  $env:PAYGUARD_ADMIN_USERNAME = "loadtest_admin"
  $env:PAYGUARD_ADMIN_PASSWORD = "loadtestAdminPassword123"
  ml-service\.venv\Scripts\python.exe loadtest/loadtest_runner.py --mode all
  ```

- **Run ML Component Benchmark Only**:
  ```powershell
  ml-service\.venv\Scripts\python.exe loadtest/loadtest_runner.py --mode ml
  ```

- **Run End-to-End Pipeline Only**:
  ```powershell
  ml-service\.venv\Scripts\python.exe loadtest/loadtest_runner.py --mode e2e
  ```

---

## 5. Generated Artifacts

Results are saved to `loadtest/results/`:
- `loadtest_summary.json`: Complete machine-readable metrics, per-stage stats, and isolation audit.
- `loadtest_measurements.csv`: Tabular performance data across all stages.
- `loadtest_report.md`: Markdown summary report with latency and throughput tables.
