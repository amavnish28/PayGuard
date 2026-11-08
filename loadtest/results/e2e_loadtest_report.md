# PayGuard Load Testing Performance Report

## 1. Test Environment & System Configuration
- **Date & Time**: 2026-11-07 14:14:42 UTC
- **Target Database**: `payguard_loadtest` (Verified Isolated)
- **Evaluation Database**: `payguard` (Verified Untouched)
- **Backend Base URL**: `http://localhost:8080`
- **ML Service Base URL**: `http://localhost:8001`

## 2. Component Benchmark Results: FastAPI ML Service (/api/v1/predict)

| Stage | Concurrency | Total Req | Success | Error Rate | Throughput (RPS) | Avg Latency | p50 | p95 | p99 | Max | Server ML Latency |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |

## 3. End-to-End Pipeline Results: Spring Boot Backend (/api/transactions)

| Stage | Concurrency | Total Req | Success | Error Rate | Throughput (RPS) | Avg Latency | p50 | p95 | p99 | Max |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **E2E-Baseline** | 1 | 100 | 100 | 0.0% | 10.11 | 98.81 ms | 92.01 ms | 137.11 ms | 260.61 ms | 340.25 ms |
| **E2E-Light** | 5 | 250 | 250 | 0.0% | 33.23 | 149.26 ms | 135.55 ms | 248.91 ms | 318.06 ms | 399.71 ms |
| **E2E-Moderate** | 10 | 500 | 500 | 0.0% | 32.13 | 309.39 ms | 250.04 ms | 634.04 ms | 1101.63 ms | 2110.99 ms |
| **E2E-High** | 25 | 1000 | 1000 | 0.0% | 19.33 | 1283.37 ms | 488.9 ms | 4919.91 ms | 8508.72 ms | 13973.62 ms |
| **E2E-Stress** | 50 | 1500 | 1500 | 0.0% | 21.01 | 2352.73 ms | 1492.04 ms | 7541.04 ms | 11813.38 ms | 23823.79 ms |

## 4. Database Isolation Verification
- Evaluation Database Baseline: `{'transactions': 10000, 'transaction_features': 10000, 'alerts': 722, 'audit_logs': 5}`
- Evaluation Database Post-Test: `{'transactions': 10000, 'transaction_features': 10000, 'alerts': 722, 'audit_logs': 5}`
- Evaluation Leaked Records: `0`
- Load-Test Database Final Row Counts: `{'transactions': 3535, 'transaction_features': 3535, 'alerts': 3336, 'audit_logs': 328}`

## 5. Observations & Safety Threshold Compliance
- Safety condition: Error rate < 5.0% and p95 latency < 5,000 ms.
- Escalation status: Escalation halted due to safety guardrail violation.