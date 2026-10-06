"""Tests for PayGuard model retraining and hot-swap activation endpoints."""

from __future__ import annotations

import json
from pathlib import Path
import shutil
from typing import Any, Dict, List

from fastapi.testclient import TestClient
import numpy as np
import pandas as pd
import pytest

from app.main import app

ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
DATA_PATH = ML_SERVICE_ROOT / "data" / "synthetic" / "synthetic_v1.csv"
MODEL_STORE_DIR = ML_SERVICE_ROOT / "model_store"
ACTIVE_VERSION = "xgboost-v1"


@pytest.fixture(scope="module")
def client():
    """FastAPI TestClient with real model artifacts loaded."""
    with TestClient(app) as test_client:
        yield test_client
        # Ensure active model is restored to xgboost-v1 upon teardown
        test_client.post("/api/v1/model/activate", json={"model_version": ACTIVE_VERSION})


def _sample_valid_training_examples() -> List[Dict[str, Any]]:
    """Construct a small list of valid training examples matching the 11-feature contract."""
    return [
        {
            "features": {
                "amount": 25.50,
                "transactions_last_2_min": 0,
                "transactions_last_1_hour": 1,
                "account_avg_amount": 25.50,
                "amount_ratio": 1.0,
                "time_since_previous_seconds": 3600.0,
                "is_first_transaction": 0,
                "new_device": 0,
                "new_location": 0,
                "transaction_hour": 14,
                "odd_hour": 0,
            },
            "label": 0,
        },
        {
            "features": {
                "amount": 85000.00,
                "transactions_last_2_min": 5,
                "transactions_last_1_hour": 10,
                "account_avg_amount": 100.00,
                "amount_ratio": 850.0,
                "time_since_previous_seconds": 30.0,
                "is_first_transaction": 0,
                "new_device": 1,
                "new_location": 1,
                "transaction_hour": 3,
                "odd_hour": 1,
            },
            "label": 1,
        },
        {
            "features": {
                "amount": 100.00,
                "transactions_last_2_min": 0,
                "transactions_last_1_hour": 0,
                "account_avg_amount": 0.0,
                "amount_ratio": 0.0,
                "time_since_previous_seconds": -1.0,
                "is_first_transaction": 1,
                "new_device": 1,
                "new_location": 1,
                "transaction_hour": 10,
                "odd_hour": 0,
            },
            "label": 0,
        },
        {
            "features": {
                "amount": 55000.00,
                "transactions_last_2_min": 4,
                "transactions_last_1_hour": 8,
                "account_avg_amount": 75.00,
                "amount_ratio": 733.33,
                "time_since_previous_seconds": 45.0,
                "is_first_transaction": 0,
                "new_device": 1,
                "new_location": 1,
                "transaction_hour": 1,
                "odd_hour": 1,
            },
            "label": 1,
        },
    ]


def test_retrain_valid_payload_success(client: TestClient):
    """POST /retrain succeeds, outputs metrics, and writes candidate files without touching xgboost-v1."""
    candidate_name = "test-xgboost-candidate-valid"
    candidate_dir = MODEL_STORE_DIR / candidate_name
    xgboost_v1_dir = MODEL_STORE_DIR / "xgboost_v1"

    # Capture modification times of xgboost_v1 files to verify untouched
    xgboost_v1_mtimes = {
        f.name: f.stat().st_mtime for f in xgboost_v1_dir.iterdir() if f.is_file()
    }

    try:
        payload = {
            "model_version_candidate": candidate_name,
            "training_examples": _sample_valid_training_examples(),
        }

        response = client.post("/api/v1/retrain", json=payload)
        assert response.status_code == 200, f"Expected 200 but got {response.status_code}: {response.text}"

        data = response.json()
        assert data["model_version_candidate"] == candidate_name
        assert "metrics" in data
        assert "test_pr_auc" in data["metrics"]
        assert "val_pr_auc" in data["metrics"]
        assert "training_data_summary" in data
        assert data["training_data_summary"]["verdict_rows"] == 4
        assert data["training_data_summary"]["synthetic_rows"] > 0
        assert data["block_threshold"] > data["review_threshold"]

        # Check candidate files created under model_store/{candidate}/
        assert candidate_dir.exists()
        assert (candidate_dir / "model.joblib").exists()
        assert (candidate_dir / "explainer.joblib").exists()
        assert (candidate_dir / "metadata.json").exists()

        with open(candidate_dir / "metadata.json", "r", encoding="utf-8") as f:
            meta = json.load(f)
        assert meta["model_version"] == candidate_name
        assert "val_metrics" in meta
        assert "test_metrics" in meta

        # Confirm xgboost_v1 files were completely untouched
        for fname, prev_mtime in xgboost_v1_mtimes.items():
            current_mtime = (xgboost_v1_dir / fname).stat().st_mtime
            assert current_mtime == prev_mtime, f"File {fname} in xgboost_v1 was modified!"

    finally:
        # Clean up candidate directory
        if candidate_dir.exists():
            shutil.rmtree(candidate_dir, ignore_errors=True)


def test_retrain_malformed_features_returns_400(client: TestClient):
    """POST /retrain with malformed features returns 400."""
    candidate_name = "test-malformed-candidate"

    # Case 1: Missing required field ('amount')
    payload_missing_field = {
        "model_version_candidate": candidate_name,
        "training_examples": [
            {
                "features": {
                    # 'amount' is missing
                    "transactions_last_2_min": 0,
                    "transactions_last_1_hour": 1,
                    "account_avg_amount": 25.50,
                    "amount_ratio": 1.0,
                    "time_since_previous_seconds": 3600.0,
                    "is_first_transaction": 0,
                    "new_device": 0,
                    "new_location": 0,
                    "transaction_hour": 14,
                    "odd_hour": 0,
                },
                "label": 0,
            }
        ],
    }
    res1 = client.post("/api/v1/retrain", json=payload_missing_field)
    assert res1.status_code == 400

    # Case 2: Wrong type / invalid range (transaction_hour = 99)
    payload_invalid_type = {
        "model_version_candidate": candidate_name,
        "training_examples": [
            {
                "features": {
                    "amount": 25.50,
                    "transactions_last_2_min": 0,
                    "transactions_last_1_hour": 1,
                    "account_avg_amount": 25.50,
                    "amount_ratio": 1.0,
                    "time_since_previous_seconds": 3600.0,
                    "is_first_transaction": 0,
                    "new_device": 0,
                    "new_location": 0,
                    "transaction_hour": 99,  # Invalid: max is 23
                    "odd_hour": 0,
                },
                "label": 0,
            }
        ],
    }
    res2 = client.post("/api/v1/retrain", json=payload_invalid_type)
    assert res2.status_code == 400

    # Case 3: Invalid label (not 0 or 1)
    payload_invalid_label = {
        "model_version_candidate": candidate_name,
        "training_examples": [
            {
                "features": _sample_valid_training_examples()[0]["features"],
                "label": 2,  # Invalid
            }
        ],
    }
    res3 = client.post("/api/v1/retrain", json=payload_invalid_label)
    assert res3.status_code == 400


def test_retrain_test_split_unmodified_invariant(client: TestClient):
    """Enforcement test: Held-out test split is byte-identical before and after /retrain."""
    df_before = pd.read_csv(DATA_PATH)
    test_rows_before = df_before[df_before["split"] == "test"]
    indices_before = test_rows_before.index.to_numpy().copy()
    csv_bytes_before = test_rows_before.to_csv().encode("utf-8")

    candidate_name = "test-test-split-invariant"
    candidate_dir = MODEL_STORE_DIR / candidate_name

    try:
        payload = {
            "model_version_candidate": candidate_name,
            "training_examples": _sample_valid_training_examples(),
        }
        res = client.post("/api/v1/retrain", json=payload)
        assert res.status_code == 200

        # Read dataset again and compare held-out test split
        df_after = pd.read_csv(DATA_PATH)
        test_rows_after = df_after[df_after["split"] == "test"]
        indices_after = test_rows_after.index.to_numpy()
        csv_bytes_after = test_rows_after.to_csv().encode("utf-8")

        np.testing.assert_array_equal(indices_before, indices_after)
        assert csv_bytes_before == csv_bytes_after, "Held-out test split CSV bytes changed after /retrain call!"

    finally:
        if candidate_dir.exists():
            shutil.rmtree(candidate_dir, ignore_errors=True)


def test_model_activate_valid_hot_swap(client: TestClient):
    """POST /model/activate with valid version hot-swaps live model, verified via info and predict."""
    candidate_name = "test-activate-valid-v2"
    candidate_dir = MODEL_STORE_DIR / candidate_name

    try:
        # First train a valid candidate
        payload = {
            "model_version_candidate": candidate_name,
            "training_examples": _sample_valid_training_examples(),
        }
        retrain_res = client.post("/api/v1/retrain", json=payload)
        assert retrain_res.status_code == 200

        # Confirm currently active is xgboost-v1
        info_before = client.get("/api/v1/model/info").json()
        assert info_before["model_version"] == "xgboost-v1"

        # Hot-swap to candidate
        activate_res = client.post("/api/v1/model/activate", json={"model_version": candidate_name})
        assert activate_res.status_code == 200
        assert activate_res.json()["status"] == "activated"
        assert activate_res.json()["model_version"] == candidate_name

        # Verify GET /model/info shows new version
        info_after = client.get("/api/v1/model/info").json()
        assert info_after["model_version"] == candidate_name

        # Verify POST /predict shows new version
        pred_res = client.post(
            "/api/v1/predict",
            json={
                "amount": 25.0,
                "transactions_last_2_min": 0,
                "transactions_last_1_hour": 0,
                "account_avg_amount": 25.0,
                "amount_ratio": 1.0,
                "time_since_previous_seconds": 3600.0,
                "is_first_transaction": 0,
                "new_device": 0,
                "new_location": 0,
                "transaction_hour": 14,
                "odd_hour": 0,
            },
        )
        assert pred_res.status_code == 200
        assert pred_res.json()["model_version"] == candidate_name

    finally:
        # Restore active model to xgboost-v1
        client.post("/api/v1/model/activate", json={"model_version": "xgboost-v1"})
        if candidate_dir.exists():
            shutil.rmtree(candidate_dir, ignore_errors=True)


def test_model_activate_non_existent_version_returns_404(client: TestClient):
    """POST /model/activate with non-existent version returns 404 and does not change active model."""
    info_before = client.get("/api/v1/model/info").json()

    res = client.post("/api/v1/model/activate", json={"model_version": "non-existent-version-99999"})
    assert res.status_code == 404

    info_after = client.get("/api/v1/model/info").json()
    assert info_after["model_version"] == info_before["model_version"]


def test_model_activate_corrupted_files_fails_cleanly(client: TestClient):
    """POST /model/activate with corrupted files fails and leaves active model untouched."""
    corrupted_version = "test-corrupted-candidate"
    corrupted_dir = MODEL_STORE_DIR / corrupted_version
    corrupted_dir.mkdir(parents=True, exist_ok=True)

    try:
        # Write corrupted / invalid files
        (corrupted_dir / "model.joblib").write_bytes(b"NOT_A_VALID_JOBLIB_FILE")
        (corrupted_dir / "explainer.joblib").write_bytes(b"NOT_A_VALID_JOBLIB_FILE")
        (corrupted_dir / "metadata.json").write_text("INVALID_JSON", encoding="utf-8")

        info_before = client.get("/api/v1/model/info").json()

        res = client.post("/api/v1/model/activate", json={"model_version": corrupted_version})
        assert res.status_code in (400, 500)

        # Active model must be completely untouched
        info_after = client.get("/api/v1/model/info").json()
        assert info_after["model_version"] == info_before["model_version"]

    finally:
        if corrupted_dir.exists():
            shutil.rmtree(corrupted_dir, ignore_errors=True)
