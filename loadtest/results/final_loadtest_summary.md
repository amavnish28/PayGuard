# PayGuard — Final Consolidated Load Testing Performance Report

**Date & Time**: 2026-11-07 14:14:42 UTC  
**Environment**: Windows 11 (AMD Ryzen 3 5300U, 4 cores / 8 threads, 8.0 GB RAM)  
**Target Load-Test Database**: payguard_loadtest (Dedicated, Isolated)  
**Protected Evaluation Database**: payguard (Read-Only, Fully Verified)  
**FastAPI ML Service Base URL**: http://localhost:8001  
**Spring Boot Backend Base URL**: http://localhost:8080  

---

## 1. Executive Summary

A staged, isolated load-testing evaluation of the PayGuard fraud detection architecture was executed. Testing progressed through two primary evaluations:
1. **ML Service Component Benchmark**: Direct concurrent load on FastAPI (POST /api/v1/predict) testing raw XGBoost inference and SHAP explainability.
2. **End-to-End Pipeline Benchmark**: Full system integration through Spring Boot (POST /api/transactions) testing JWT security filters, advisory locking, PostgreSQL persistence, 11-feature behavioral window aggregation, rules evaluation, synchronous ML REST invocation, hybrid decision matrix calculation, and alert creation.

Both benchmarks were executed across five standardized concurrency tiers (1, 5, 10, 25, 50 virtual users), evaluating a total of **6,700 measured transactions** plus **370 warm-up transactions** with **0.0% HTTP error rates**.

---

## 2. Consolidated Performance Results

### A. Comparative Metrics by Concurrency Tier

| Concurrency | Stage | Requests | Success Rate | Error Rate | Throughput (RPS) | Avg Latency | p50 Latency | p95 Latency | p99 Latency | Max Latency | Server ML Latency | Safety Abort? |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **1** | **ML-Baseline** | 100 | 100.0% | 0.0% | **41.71** | 23.90 ms | 21.41 ms | 35.14 ms | 91.72 ms | 93.01 ms | 8.60 ms | No |
| **1** | **E2E-Baseline** | 100 | 100.0% | 0.0% | **10.11** | 98.81 ms | 92.01 ms | 137.11 ms | 260.61 ms | 340.25 ms | N/A | No |
| **5** | **ML-Light** | 250 | 100.0% | 0.0% | **66.65** | 74.48 ms | 67.76 ms | 128.32 ms | 160.00 ms | 195.62 ms | 10.05 ms | No |
| **5** | **E2E-Light** | 250 | 100.0% | 0.0% | **33.23** | 149.26 ms | 135.55 ms | 248.91 ms | 318.06 ms | 399.71 ms | N/A | No |
| **10** | **ML-Moderate** | 500 | 100.0% | 0.0% | **44.24** | 224.77 ms | 142.18 ms | 695.36 ms | 1428.09 ms | 2652.00 ms | 11.21 ms | No |
| **10** | **E2E-Moderate** | 500 | 100.0% | 0.0% | **32.13** | 309.39 ms | 250.04 ms | 634.04 ms | 1101.63 ms | 2110.99 ms | N/A | No |
| **25** | **ML-High** | 1000 | 100.0% | 0.0% | **32.97** | 752.56 ms | 424.69 ms | 2324.62 ms | 3861.26 ms | 6038.39 ms | 9.87 ms | No |
| **25** | **E2E-High** | 1000 | 100.0% | 0.0% | **19.33** | 1283.37 ms | 488.90 ms | 4919.91 ms | 8508.72 ms | 13973.62 ms | N/A | No |
| **50** | **ML-Stress** | 1500 | 100.0% | 0.0% | **29.93** | 1649.87 ms | 1168.91 ms | 4957.59 ms | 7148.12 ms | 13234.45 ms | 8.32 ms | No |
| **50** | **E2E-Stress** | 1500 | 100.0% | 0.0% | **21.01** | 2352.73 ms | 1492.04 ms | 7541.04 ms | 11813.38 ms | 23823.79 ms | N/A | **YES** |

---

## 3. Performance Analysis & Empirical Observations

### 1. Optimal Throughput & Best-Performing Concurrency
- **Peak Throughput**:
  - **ML Component**: Reached a maximum of **66.65 RPS** at Concurrency 5.
  - **End-to-End Pipeline**: Reached a maximum of **33.23 RPS** at Concurrency 5.
- **Sweet Spot**:
  - Across both systems, **Concurrency 5** proved to be the optimal operating point. At this level, server resources were fully utilized without inducing substantial thread queue buildup (p50 latency was 67.76 ms for ML and 135.55 ms for E2E).
  - Increasing concurrency beyond 5 virtual users showed throughput saturation and latency degradation (dropping to ~30 RPS for ML and ~20 RPS for E2E).

### 2. ML Service Latency vs. Queuing Behavior
- **Server Execution Stability**:
  - The internal compute time reported by the ML service (server_reported_latency_ms) remained strictly between **8.32 ms and 11.21 ms** regardless of load.
  - This demonstrates that XGBoost matrix operations and SHAP value derivations execute with near-deterministic speed on the hardware.
- **Queuing Bottleneck**:
  - Because Uvicorn ran as a single-process ASGI server, concurrent incoming requests were queued sequentially on the asyncio event loop.
  - Client-perceived latency inflation at concurrency 25 (p95: 2,324.62 ms) and 50 (p95: 4,957.59 ms) was driven by external connection queue wait time, not model compute time.

### 3. PostgreSQL & Spring Boot Pipeline Contention
- **Pipeline Overhead**:
  - The latency delta between the isolated ML component and the E2E pipeline at baseline was approximately **75 ms** (98.81 ms vs. 23.90 ms).
  - This overhead accounts for JWT parsing/validation, PostgreSQL advisory lock acquisition, transaction persistence, SQL queries aggregating historical 2-minute and 1-hour transaction features, rules evaluation, and alert persistence.
- **Lock Contention**:
  - In the E2E pipeline, advisory locking ensures serial processing per account. When multiple concurrent workers submit transactions for shared virtual accounts, thread waits increase latency variance (p99 reaching 8,508 ms at C=25 and 11,813 ms at C=50).

### 4. ML Fallback Resilience & Circuit Breaker Verification
- **Timeout Triggering**:
  - During the High (C=25) and Stress (C=50) E2E tiers, queue wait times on the ML service occasionally exceeded the configured payguard.ml-service.timeout-ms: 150.
  - Resilience4j registered **328 fallback events** in payguard_loadtest.audit_logs with action ML_SERVICE_UNAVAILABLE.
- **System Stability**:
  - Rather than throwing 500 Internal Server Errors, Spring Boot automatically routed these requests through rules-only fallback evaluation.
  - **100% of these transactions were evaluated and approved/alerted successfully**, maintaining a **0.0% HTTP error rate**.

### 5. The 50-Concurrency Safety Abort
- In E2E-Stress, p95 latency reached **7,541.04 ms**, exceeding the pre-approved safety guardrail of 5,000 ms.
- The load test runner’s circuit breaker immediately tripped (early_abort = True), halting further concurrency escalation beyond 50 virtual users.
- All 1,500 scheduled transactions for the Stress stage completed with zero errors before escalation was halted.

---

## 4. Transaction Reconciliation & Decision Distribution

### A. End-to-End Transaction Reconciliation
- **Planned Measured Requests**: 3,350
- **Planned Warm-Up Requests**: 185
- **Total Requests Dispatched**: 3,535
- **Persisted 	ransactions in payguard_loadtest**: **3,535**
- **Persisted 	ransaction_features in payguard_loadtest**: **3,535**
- **Persisted lerts in payguard_loadtest**: **3,336**
- **Persisted udit_logs in payguard_loadtest**: **328**
- **Duplicate Transaction IDs**: **0** (Verified via GROUP BY transaction_id HAVING count(*) > 1)
- **Data Reconciliation Accuracy**: **100.0% exact match (0 dropped transactions, 0 duplicates)**.

### B. Decision Distribution Breakdown
- **REVIEW**: **2,976 (84.19%)** — Alerts created with decision='REVIEW', status='OPEN'.
- **BLOCK**: **360 (10.18%)** — Alerts created with decision='BLOCK', status='OPEN'.
- **APPROVE**: **199 (5.63%)** — Clean transactions auto-approved without alert generation.
- **Total Evaluated Transactions**: **3,535 (100.0%)**.

---

## 5. Database Isolation & Integrity Audit

| Database | Table | Expected Baseline | Actual Count | Status |
| :--- | :--- | :---: | :---: | :---: |
| **payguard** (Evaluation) | 	ransactions | 10,000 | **10,000** | PASS (Untouched) |
| **payguard** (Evaluation) | 	ransaction_features | 10,000 | **10,000** | PASS (Untouched) |
| **payguard** (Evaluation) | lerts | 722 | **722** | PASS (Untouched) |
| **payguard** (Evaluation) | udit_logs | 5 | **5** | PASS (Untouched) |
| **payguard** (Evaluation) | LOAD-% Leaked IDs | 0 | **0** | PASS (Zero Leaks) |
| **payguard_loadtest** (Load-Test) | 	ransactions | N/A | **3,535** | PASS (Populated) |
| **payguard_loadtest** (Load-Test) | 	ransaction_features | N/A | **3,535** | PASS (Populated) |
| **payguard_loadtest** (Load-Test) | lerts | N/A | **3,336** | PASS (Populated) |
| **payguard_loadtest** (Load-Test) | udit_logs | N/A | **328** | PASS (Populated) |
| **payguard_loadtest** (Load-Test) | users | 1 | **1** | PASS (loadtest_admin) |
| **payguard_loadtest** (Load-Test) | model_versions | 1 | **1** | PASS (xgboost-v1) |

---

## 6. Generated Artifacts Repository

All generated load testing artifacts are preserved under loadtest/results/:
- **Consolidated Summary (Markdown)**: loadtest/results/final_loadtest_summary.md
- **Consolidated Summary (JSON)**: loadtest/results/final_loadtest_summary.json
- **End-to-End Reports**:
  - loadtest/results/e2e_loadtest_summary.json
  - loadtest/results/e2e_loadtest_measurements.csv
  - loadtest/results/e2e_loadtest_report.md
- **ML Component Reports**:
  - loadtest/results/ml_loadtest_summary.json
  - loadtest/results/ml_loadtest_measurements.csv
  - loadtest/results/ml_loadtest_report.md
