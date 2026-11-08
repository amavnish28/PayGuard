"""Asynchronous Load Test Runner for PayGuard.

Measures:
1. Component Benchmark: FastAPI ML Service (/api/v1/predict)
2. End-to-End Pipeline: Spring Boot Backend (/api/transactions) with JWT Auth

Safety & Isolation Guarantees:
- Enforces db_isolation_guard before and after execution.
- Aborts escalation if error rate > 5% or latency > 5,000 ms.
- Never connects to the production/evaluation database 'payguard'.
- Outputs per-stage JSON, CSV, and Markdown summaries.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import json
import logging
import os
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx
import numpy as np

# Load internal modules
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR.parent) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR.parent))

from loadtest.db_isolation_guard import verify_pre_test, verify_post_test, IsolationViolationError
from loadtest.payload_generator import PayloadGenerator

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] [LoadTest]: %(message)s")
logger = logging.getLogger("LoadTest")


@dataclass
class RequestResult:
    """Stores the timing and outcome of a single HTTP request."""
    success: bool
    status_code: int
    latency_ms: float
    server_reported_latency_ms: Optional[float] = None
    is_timeout: bool = False
    error_message: Optional[str] = None


@dataclass
class StageMetrics:
    """Aggregated performance metrics for a concurrency stage."""
    stage_name: str
    target_endpoint: str
    concurrency: int
    total_requests: int
    successful_requests: int
    failed_requests: int
    timeout_requests: int
    error_rate_pct: float
    duration_seconds: float
    throughput_rps: float
    avg_latency_ms: float
    p50_latency_ms: float
    p95_latency_ms: float
    p99_latency_ms: float
    max_latency_ms: float
    min_latency_ms: float
    avg_server_ml_latency_ms: Optional[float] = None
    safety_aborted: bool = False
    raw_latencies: List[float] = field(default_factory=list)


def calculate_metrics(
    stage_name: str,
    target_endpoint: str,
    concurrency: int,
    results: List[RequestResult],
    wall_duration: float,
) -> StageMetrics:
    """Compute summary statistics and latency percentiles from individual results."""
    total = len(results)
    if total == 0:
        return StageMetrics(
            stage_name=stage_name,
            target_endpoint=target_endpoint,
            concurrency=concurrency,
            total_requests=0,
            successful_requests=0,
            failed_requests=0,
            timeout_requests=0,
            error_rate_pct=0.0,
            duration_seconds=wall_duration,
            throughput_rps=0.0,
            avg_latency_ms=0.0,
            p50_latency_ms=0.0,
            p95_latency_ms=0.0,
            p99_latency_ms=0.0,
            max_latency_ms=0.0,
            min_latency_ms=0.0,
        )

    successes = sum(1 for r in results if r.success)
    failures = sum(1 for r in results if not r.success)
    timeouts = sum(1 for r in results if r.is_timeout)
    error_rate = (failures / total) * 100.0
    throughput = successes / wall_duration if wall_duration > 0 else 0.0

    latencies = [r.latency_ms for r in results]
    p50 = float(np.percentile(latencies, 50))
    p95 = float(np.percentile(latencies, 95))
    p99 = float(np.percentile(latencies, 99))
    avg_lat = float(np.mean(latencies))
    max_lat = float(np.max(latencies))
    min_lat = float(np.min(latencies))

    server_latencies = [r.server_reported_latency_ms for r in results if r.server_reported_latency_ms is not None]
    avg_server_lat = float(np.mean(server_latencies)) if server_latencies else None

    return StageMetrics(
        stage_name=stage_name,
        target_endpoint=target_endpoint,
        concurrency=concurrency,
        total_requests=total,
        successful_requests=successes,
        failed_requests=failures,
        timeout_requests=timeouts,
        error_rate_pct=round(error_rate, 2),
        duration_seconds=round(wall_duration, 3),
        throughput_rps=round(throughput, 2),
        avg_latency_ms=round(avg_lat, 2),
        p50_latency_ms=round(p50, 2),
        p95_latency_ms=round(p95, 2),
        p99_latency_ms=round(p99, 2),
        max_latency_ms=round(max_lat, 2),
        min_latency_ms=round(min_lat, 2),
        avg_server_ml_latency_ms=round(avg_server_lat, 2) if avg_server_lat else None,
        raw_latencies=latencies,
    )


async def execute_request(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    payload: Dict[str, Any],
    headers: Optional[Dict[str, str]] = None,
    timeout_seconds: float = 10.0,
) -> RequestResult:
    """Execute a single HTTP request and record exact latency."""
    t0 = time.perf_counter()
    try:
        resp = await client.request(
            method=method,
            url=url,
            json=payload,
            headers=headers,
            timeout=timeout_seconds,
        )
        latency = (time.perf_counter() - t0) * 1000.0
        success = (resp.status_code in (200, 201))

        # Check for server-reported ML latency in JSON response
        server_reported = None
        if success:
            try:
                body = resp.json()
                if isinstance(body, dict) and "latency_ms" in body:
                    server_reported = float(body["latency_ms"])
            except Exception:
                pass

        return RequestResult(
            success=success,
            status_code=resp.status_code,
            latency_ms=latency,
            server_reported_latency_ms=server_reported,
            is_timeout=False,
            error_message=None if success else f"HTTP {resp.status_code}: {resp.text[:120]}",
        )
    except httpx.TimeoutException:
        latency = (time.perf_counter() - t0) * 1000.0
        return RequestResult(
            success=False,
            status_code=0,
            latency_ms=latency,
            is_timeout=True,
            error_message="Request timed out",
        )
    except Exception as e:
        latency = (time.perf_counter() - t0) * 1000.0
        return RequestResult(
            success=False,
            status_code=0,
            latency_ms=latency,
            is_timeout=False,
            error_message=str(e)[:120],
        )


async def run_stage_worker(
    queue: asyncio.Queue[Dict[str, Any]],
    client: httpx.AsyncClient,
    url: str,
    headers: Optional[Dict[str, str]],
    results: List[RequestResult],
    timeout_sec: float,
) -> None:
    """Worker task processing payloads from a shared queue."""
    while not queue.empty():
        try:
            payload = queue.get_nowait()
        except asyncio.QueueEmpty:
            break
        result = await execute_request(client, "POST", url, payload, headers, timeout_sec)
        results.append(result)
        queue.task_done()


async def run_concurrency_stage(
    stage_name: str,
    target_endpoint: str,
    url: str,
    headers: Optional[Dict[str, str]],
    concurrency: int,
    total_requests: int,
    warmup_requests: int,
    payload_fn: Any,
    timeout_sec: float = 10.0,
) -> StageMetrics:
    """Execute warm-up followed by measured load stage under controlled concurrency."""
    logger.info(f"--- Starting {stage_name} (Concurrency: {concurrency}, Requests: {total_requests}, Warm-up: {warmup_requests}) ---")

    limits = httpx.Limits(max_keepalive_connections=concurrency + 5, max_connections=concurrency + 10)
    async with httpx.AsyncClient(limits=limits) as client:
        # 1. Warm-up phase
        if warmup_requests > 0:
            warmup_queue: asyncio.Queue[Dict[str, Any]] = asyncio.Queue()
            for _ in range(warmup_requests):
                warmup_queue.put_nowait(payload_fn())
            warmup_results: List[RequestResult] = []
            workers = [
                asyncio.create_task(run_stage_worker(warmup_queue, client, url, headers, warmup_results, timeout_sec))
                for _ in range(min(concurrency, 5))
            ]
            await asyncio.gather(*workers)
            logger.info(f"Warm-up finished: {len(warmup_results)} requests sent.")

        # 2. Measured phase
        work_queue: asyncio.Queue[Dict[str, Any]] = asyncio.Queue()
        for _ in range(total_requests):
            work_queue.put_nowait(payload_fn())

        measured_results: List[RequestResult] = []
        t_start = time.perf_counter()

        workers = [
            asyncio.create_task(run_stage_worker(work_queue, client, url, headers, measured_results, timeout_sec))
            for _ in range(concurrency)
        ]
        await asyncio.gather(*workers)
        wall_duration = time.perf_counter() - t_start

    metrics = calculate_metrics(stage_name, target_endpoint, concurrency, measured_results, wall_duration)
    logger.info(
        f"Result {stage_name}: Throughput={metrics.throughput_rps} rps | "
        f"p50={metrics.p50_latency_ms} ms | p95={metrics.p95_latency_ms} ms | p99={metrics.p99_latency_ms} ms | "
        f"Errors={metrics.error_rate_pct}% ({metrics.failed_requests}/{metrics.total_requests})"
    )
    return metrics


def obtain_jwt_token(base_url: str) -> str:
    """Authenticate with backend /api/auth/login and return JWT token."""
    username = os.environ.get("PAYGUARD_ADMIN_USERNAME", "loadtest_admin")
    password = os.environ.get("PAYGUARD_ADMIN_PASSWORD", "loadtestAdminPassword123")

    login_url = f"{base_url.rstrip('/')}/api/auth/login"
    logger.info(f"Authenticating with backend at {login_url} (user: '{username}')...")

    with httpx.Client(timeout=10.0) as client:
        try:
            resp = client.post(login_url, json={"username": username, "password": password})
            if resp.status_code != 200:
                raise RuntimeError(f"Authentication failed with HTTP {resp.status_code}: {resp.text}")
            data = resp.json()
            token = data.get("token")
            if not token:
                raise RuntimeError("Authentication response missing 'token' attribute.")
            logger.info("Authentication successful; acquired JWT token.")
            return token
        except Exception as e:
            logger.critical(f"Failed to obtain JWT token from {login_url}: {e}")
            raise


def save_reports(
    results_dir: Path,
    summary_data: Dict[str, Any],
    stage_metrics_list: List[StageMetrics],
) -> None:
    """Save structured JSON, CSV, and Markdown load-test evaluation reports."""
    results_dir.mkdir(parents=True, exist_ok=True)

    # 1. JSON Report
    json_path = results_dir / "loadtest_summary.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(summary_data, f, indent=2)
    logger.info(f"Saved load test JSON report to: {json_path}")

    # 2. CSV Measurements
    csv_path = results_dir / "loadtest_measurements.csv"
    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "stage_name", "target_endpoint", "concurrency", "total_requests",
            "successful_requests", "failed_requests", "timeout_requests",
            "error_rate_pct", "duration_seconds", "throughput_rps",
            "avg_latency_ms", "p50_latency_ms", "p95_latency_ms",
            "p99_latency_ms", "max_latency_ms", "min_latency_ms",
            "avg_server_ml_latency_ms", "safety_aborted",
        ])
        for m in stage_metrics_list:
            writer.writerow([
                m.stage_name, m.target_endpoint, m.concurrency, m.total_requests,
                m.successful_requests, m.failed_requests, m.timeout_requests,
                m.error_rate_pct, m.duration_seconds, m.throughput_rps,
                m.avg_latency_ms, m.p50_latency_ms, m.p95_latency_ms,
                m.p99_latency_ms, m.max_latency_ms, m.min_latency_ms,
                m.avg_server_ml_latency_ms if m.avg_server_ml_latency_ms is not None else "N/A",
                m.safety_aborted,
            ])
    logger.info(f"Saved load test CSV metrics to: {csv_path}")

    # 3. Markdown Report
    md_path = results_dir / "loadtest_report.md"
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(generate_markdown_summary(summary_data, stage_metrics_list))
    logger.info(f"Saved load test Markdown report to: {md_path}")


def generate_markdown_summary(summary: Dict[str, Any], metrics_list: List[StageMetrics]) -> str:
    """Generate Markdown report for load-testing evaluation."""
    lines = [
        "# PayGuard Load Testing Performance Report",
        "",
        "## 1. Test Environment & System Configuration",
        f"- **Date & Time**: {summary.get('timestamp')}",
        f"- **Target Database**: `{summary.get('target_database')}` (Verified Isolated)",
        f"- **Evaluation Database**: `{summary.get('evaluation_database')}` (Verified Untouched)",
        f"- **Backend Base URL**: `{summary.get('backend_url')}`",
        f"- **ML Service Base URL**: `{summary.get('ml_service_url')}`",
        "",
        "## 2. Component Benchmark Results: FastAPI ML Service (/api/v1/predict)",
        "",
        "| Stage | Concurrency | Total Req | Success | Error Rate | Throughput (RPS) | Avg Latency | p50 | p95 | p99 | Max | Server ML Latency |",
        "| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |",
    ]

    for m in metrics_list:
        if "/api/v1/predict" in m.target_endpoint:
            server_ml = f"{m.avg_server_ml_latency_ms} ms" if m.avg_server_ml_latency_ms is not None else "N/A"
            lines.append(
                f"| **{m.stage_name}** | {m.concurrency} | {m.total_requests} | {m.successful_requests} | "
                f"{m.error_rate_pct}% | {m.throughput_rps} | {m.avg_latency_ms} ms | {m.p50_latency_ms} ms | "
                f"{m.p95_latency_ms} ms | {m.p99_latency_ms} ms | {m.max_latency_ms} ms | {server_ml} |"
            )

    lines.extend([
        "",
        "## 3. End-to-End Pipeline Results: Spring Boot Backend (/api/transactions)",
        "",
        "| Stage | Concurrency | Total Req | Success | Error Rate | Throughput (RPS) | Avg Latency | p50 | p95 | p99 | Max |",
        "| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |",
    ])

    for m in metrics_list:
        if "/api/transactions" in m.target_endpoint:
            lines.append(
                f"| **{m.stage_name}** | {m.concurrency} | {m.total_requests} | {m.successful_requests} | "
                f"{m.error_rate_pct}% | {m.throughput_rps} | {m.avg_latency_ms} ms | {m.p50_latency_ms} ms | "
                f"{m.p95_latency_ms} ms | {m.p99_latency_ms} ms | {m.max_latency_ms} ms |"
            )

    lines.extend([
        "",
        "## 4. Database Isolation Verification",
        f"- Evaluation Database Baseline: `{summary.get('pre_test_eval_counts')}`",
        f"- Evaluation Database Post-Test: `{summary.get('post_test_eval_counts')}`",
        f"- Evaluation Leaked Records: `{summary.get('leaked_records', 0)}`",
        f"- Load-Test Database Final Row Counts: `{summary.get('post_test_loadtest_counts')}`",
        "",
        "## 5. Observations & Safety Threshold Compliance",
        "- Safety condition: Error rate < 5.0% and p95 latency < 5,000 ms.",
        "- Escalation status: " + ("All scheduled stages completed successfully." if not summary.get("early_abort") else "Escalation halted due to safety guardrail violation."),
    ])
    return "\n".join(lines)


async def main_async() -> int:
    parser = argparse.ArgumentParser(description="PayGuard Load Test Runner")
    parser.add_argument("--mode", choices=["all", "e2e", "ml"], default="all", help="Test mode (default: all)")
    parser.add_argument("--backend-url", default="http://localhost:8080", help="Spring Boot base URL")
    parser.add_argument("--ml-url", default="http://localhost:8001", help="FastAPI ML service base URL")
    parser.add_argument("--target-db", default="payguard_loadtest", help="Target database name (strictly payguard_loadtest)")
    parser.add_argument("--results-dir", default=str(SCRIPT_DIR / "results"), help="Results output directory")
    args = parser.parse_args()

    results_dir = Path(args.results_dir)

    # -------------------------------------------------------------
    # 1. Database Isolation Pre-Check
    # -------------------------------------------------------------
    try:
        pre_check = verify_pre_test(target_db=args.target_db)
    except IsolationViolationError as e:
        logger.critical(f"ABORTING: Isolation check failed: {e}")
        return 1

    stage_metrics_list: List[StageMetrics] = []
    early_abort = False
    benchmark_exception: Optional[Exception] = None
    post_check: Optional[Dict[str, Any]] = None
    post_isolation_exception: Optional[Exception] = None

    try:
        payload_gen = PayloadGenerator(num_accounts=50, seed=42)

        # Staged plan: (StageName, Concurrency, Requests, Warmup)
        stages = [
            ("Baseline", 1, 100, 10),
            ("Light", 5, 250, 25),
            ("Moderate", 10, 500, 50),
            ("High", 25, 1000, 50),
            ("Stress", 50, 1500, 50),
        ]
        # -------------------------------------------------------------
        # 2. ML Component Benchmark
        # -------------------------------------------------------------
        if args.mode in ("all", "ml"):
            ml_predict_url = f"{args.ml_url.rstrip('/')}/api/v1/predict"
            logger.info(f"=== Starting ML Service Component Benchmark on {ml_predict_url} ===")

            for stage_name, conc, reqs, warmup in stages:
                m = await run_concurrency_stage(
                    stage_name=f"ML-{stage_name}",
                    target_endpoint=ml_predict_url,
                    url=ml_predict_url,
                    headers={"Content-Type": "application/json"},
                    concurrency=conc,
                    total_requests=reqs,
                    warmup_requests=warmup,
                    payload_fn=payload_gen.next_ml_prediction_payload,
                )
                stage_metrics_list.append(m)

                # Safety check
                if m.error_rate_pct > 5.0 or m.p95_latency_ms > 5000.0:
                    logger.warning(
                        f"SAFETY GUARD TRIGGERED on ML {stage_name}: ErrorRate={m.error_rate_pct}%, p95={m.p95_latency_ms} ms. "
                        f"Halting ML escalation."
                    )
                    m.safety_aborted = True
                    early_abort = True
                    break

        # -------------------------------------------------------------
        # 3. End-to-End Pipeline Benchmark
        # -------------------------------------------------------------
        if args.mode in ("all", "e2e") and not early_abort:
            backend_txn_url = f"{args.backend_url.rstrip('/')}/api/transactions"
            logger.info(f"=== Starting End-to-End Pipeline Benchmark on {backend_txn_url} ===")

            # Obtain JWT Token (raises RuntimeError on failure)
            token = obtain_jwt_token(args.backend_url)
            auth_headers = {
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
            }

            for stage_name, conc, reqs, warmup in stages:
                m = await run_concurrency_stage(
                    stage_name=f"E2E-{stage_name}",
                    target_endpoint=backend_txn_url,
                    url=backend_txn_url,
                    headers=auth_headers,
                    concurrency=conc,
                    total_requests=reqs,
                    warmup_requests=warmup,
                    payload_fn=payload_gen.next_transaction_payload,
                )
                stage_metrics_list.append(m)

                # Safety check
                if m.error_rate_pct > 5.0 or m.p95_latency_ms > 5000.0:
                    logger.warning(
                        f"SAFETY GUARD TRIGGERED on E2E {stage_name}: ErrorRate={m.error_rate_pct}%, p95={m.p95_latency_ms} ms. "
                        f"Halting E2E escalation."
                    )
                    m.safety_aborted = True
                    early_abort = True
                    break

    except Exception as exc:
        benchmark_exception = exc
        logger.critical(f"Benchmark execution encountered an exception: {exc}", exc_info=True)

    finally:
        # -------------------------------------------------------------
        # 4. Database Isolation Post-Check (Guaranteed Execution)
        # -------------------------------------------------------------
        logger.info("Executing post-test database isolation verification in finally block...")
        try:
            post_check = verify_post_test(target_db=args.target_db)
        except Exception as post_exc:
            post_isolation_exception = post_exc
            logger.critical(f"CRITICAL ISOLATION VERIFICATION FAILURE: {post_exc}", exc_info=True)

    # -------------------------------------------------------------
    # 5. Outcome Assessment & Report Generation
    # -------------------------------------------------------------
    if post_isolation_exception is not None:
        logger.critical(f"RUNNER FAILED: Post-test database isolation check failed: {post_isolation_exception}")
        return 1

    summary_data = {
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
        "target_database": args.target_db,
        "evaluation_database": "payguard",
        "backend_url": args.backend_url,
        "ml_service_url": args.ml_url,
        "pre_test_eval_counts": pre_check.get("eval_counts"),
        "post_test_eval_counts": post_check.get("eval_counts") if post_check else None,
        "post_test_loadtest_counts": post_check.get("loadtest_counts") if post_check else None,
        "leaked_records": post_check.get("leaked_records") if post_check else None,
        "early_abort": early_abort or (benchmark_exception is not None),
        "stages": [
            {
                "stage": m.stage_name,
                "endpoint": m.target_endpoint,
                "concurrency": m.concurrency,
                "total_requests": m.total_requests,
                "successful_requests": m.successful_requests,
                "failed_requests": m.failed_requests,
                "timeout_requests": m.timeout_requests,
                "error_rate_pct": m.error_rate_pct,
                "duration_seconds": m.duration_seconds,
                "throughput_rps": m.throughput_rps,
                "avg_latency_ms": m.avg_latency_ms,
                "p50_latency_ms": m.p50_latency_ms,
                "p95_latency_ms": m.p95_latency_ms,
                "p99_latency_ms": m.p99_latency_ms,
                "max_latency_ms": m.max_latency_ms,
                "min_latency_ms": m.min_latency_ms,
                "avg_server_ml_latency_ms": m.avg_server_ml_latency_ms,
                "safety_aborted": m.safety_aborted,
            }
            for m in stage_metrics_list
        ],
    }

    if benchmark_exception is not None:
        summary_data["benchmark_error"] = str(benchmark_exception)
        logger.error(f"RUNNER FAILED: Benchmark failed due to exception: {benchmark_exception}")
        if stage_metrics_list and post_check is not None:
            save_reports(results_dir, summary_data, stage_metrics_list)
        return 1

    save_reports(results_dir, summary_data, stage_metrics_list)
    logger.info("Load test execution cycle completed successfully.")
    return 0


def main() -> None:
    sys.exit(asyncio.run(main_async()))


if __name__ == "__main__":
    main()
