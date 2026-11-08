# PayGuard Load Testing Performance Report

## 1. Test Environment & System Configuration
- **Date & Time**: 2026-11-07 11:05:57 UTC
- **Target Database**: `payguard_loadtest` (Verified Isolated)
- **Evaluation Database**: `payguard` (Verified Untouched)
- **Backend Base URL**: `http://localhost:8080`
- **ML Service Base URL**: `http://localhost:8001`

## 2. Component Benchmark Results: FastAPI ML Service (/api/v1/predict)

| Stage | Concurrency | Total Req | Success | Error Rate | Throughput (RPS) | Avg Latency | p50 | p95 | p99 | Max | Server ML Latency |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **ML-Baseline** | 1 | 100 | 100 | 0.0% | 41.71 | 23.9 ms | 21.41 ms | 35.14 ms | 91.72 ms | 93.01 ms | 8.6 ms |
| **ML-Light** | 5 | 250 | 250 | 0.0% | 66.65 | 74.48 ms | 67.76 ms | 128.32 ms | 160.0 ms | 195.62 ms | 10.05 ms |
| **ML-Moderate** | 10 | 500 | 500 | 0.0% | 44.24 | 224.77 ms | 142.18 ms | 695.36 ms | 1428.09 ms | 2652.0 ms | 11.21 ms |
| **ML-High** | 25 | 1000 | 1000 | 0.0% | 32.97 | 752.56 ms | 424.69 ms | 2324.62 ms | 3861.26 ms | 6038.39 ms | 9.87 ms |
| **ML-Stress** | 50 | 1500 | 1500 | 0.0% | 29.93 | 1649.87 ms | 1168.91 ms | 4957.59 ms | 7148.12 ms | 13234.45 ms | 8.32 ms |

## 3. End-to-End Pipeline Results: Spring Boot Backend (/api/transactions)

| Stage | Concurrency | Total Req | Success | Error Rate | Throughput (RPS) | Avg Latency | p50 | p95 | p99 | Max |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |

## 4. Database Isolation Verification
- Evaluation Database Baseline: `{'transactions': 10000, 'transaction_features': 10000, 'alerts': 722, 'audit_logs': 5}`
- Evaluation Database Post-Test: `{'transactions': 10000, 'transaction_features': 10000, 'alerts': 722, 'audit_logs': 5}`
- Evaluation Leaked Records: `0`
- Load-Test Database Final Row Counts: `{'transactions': 0, 'transaction_features': 0, 'alerts': 0, 'audit_logs': 0}`

## 5. Observations & Safety Threshold Compliance
- Safety condition: Error rate < 5.0% and p95 latency < 5,000 ms.
- Escalation status: All scheduled stages completed successfully.