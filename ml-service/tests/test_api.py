"""Integration tests for the PayGuard FastAPI ML Serving service."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, get_args

from fastapi.testclient import TestClient
import pytest

from app.main import app, create_app
from app.schemas.predict import PredictionRequest

REPO_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_SCHEMA_PATH = REPO_ROOT / "contracts" / "feature_schema_v1.json"


@pytest.fixture(scope="module")
def client():
    """FastAPI TestClient with real model artifacts loaded."""
    with TestClient(app) as test_client:
        yield test_client


def test_health_endpoint_loaded(client: TestClient):
    """GET /health returns 200 and model_loaded: true when model is loaded."""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["model_loaded"] is True


def test_health_endpoint_unloaded():
    """GET /health returns 200 and model_loaded: false when model is not loaded."""
    unloaded_app = create_app(load_on_startup=False)
    with TestClient(unloaded_app) as unloaded_client:
        response = unloaded_client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert data["model_loaded"] is False


def test_model_info_endpoint(client: TestClient):
    """GET /api/v1/model/info returns all expected keys and feature_order matches contract."""
    response = client.get("/api/v1/model/info")
    assert response.status_code == 200
    data = response.json()

    expected_keys = {
        "model_version",
        "trained_at",
        "feature_order",
        "feature_schema_version",
        "block_threshold",
        "review_threshold",
        "val_metrics",
    }
    assert expected_keys.issubset(data.keys()), f"Missing keys in info response: {expected_keys - set(data.keys())}"

    # Load contract directly
    with open(CONTRACT_SCHEMA_PATH, "r", encoding="utf-8") as f:
        contract = json.load(f)
    expected_order = [feat["name"] for feat in contract["features"]]

    assert data["feature_order"] == expected_order, (
        f"Feature order mismatch in model/info.\nExpected: {expected_order}\nGot: {data['feature_order']}"
    )
    assert data["block_threshold"] > data["review_threshold"]


def test_predict_low_risk_payload(client: TestClient):
    """POST /api/v1/predict with low-risk payload returns 200, probability near 0, null SHAP, band 'low'."""
    payload = {
        "amount": 15.0,
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 0,
        "account_avg_amount": 15.0,
        "amount_ratio": 1.0,
        "time_since_previous_seconds": 3600.0,
        "is_first_transaction": 0,
        "new_device": 0,
        "new_location": 0,
        "transaction_hour": 14,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 200
    data = response.json()

    assert data["fraud_probability"] < data["review_threshold"]
    assert data["fraud_probability"] < 0.05
    assert data["shap_reasons"] is None
    assert data["ml_band"] == "low"
    assert data["latency_ms"] > 0


def test_predict_first_transaction_low_risk(client: TestClient):
    """POST /api/v1/predict with first transaction valid low-risk payload."""
    payload = {
        "amount": 20.0,
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 0,
        "account_avg_amount": 0.0,
        "amount_ratio": 0.0,
        "time_since_previous_seconds": -1.0,
        "is_first_transaction": 1,
        "new_device": 1,
        "new_location": 1,
        "transaction_hour": 11,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["fraud_probability"] < data["review_threshold"]
    assert data["ml_band"] == "low"
    assert data["shap_reasons"] is None


def test_predict_high_risk_payload(client: TestClient):
    """POST /api/v1/predict with high-risk payload returns 200, high probability, 5 SHAP reasons, band 'block'."""
    payload = {
        "amount": 50000.0,
        "transactions_last_2_min": 6,
        "transactions_last_1_hour": 12,
        "account_avg_amount": 100.0,
        "amount_ratio": 500.0,
        "time_since_previous_seconds": 10.0,
        "is_first_transaction": 0,
        "new_device": 1,
        "new_location": 1,
        "transaction_hour": 2,
        "odd_hour": 1,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 200
    data = response.json()

    assert data["fraud_probability"] >= data["block_threshold"]
    assert data["fraud_probability"] > 0.8
    assert data["ml_band"] == "block"
    assert isinstance(data["shap_reasons"], list)
    assert len(data["shap_reasons"]) == 5

    # Check structure of SHAP reasons
    for reason in data["shap_reasons"]:
        assert "feature" in reason
        assert "shap_value" in reason
        assert "feature_value" in reason
        assert isinstance(reason["feature"], str)
        assert isinstance(reason["shap_value"], (float, int))


def test_predict_first_transaction_validator_contradiction(client: TestClient):
    """POST /api/v1/predict with is_first_transaction=1 but new_device=0 returns 422."""
    payload = {
        "amount": 25.0,
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 0,
        "account_avg_amount": 0.0,
        "amount_ratio": 0.0,
        "time_since_previous_seconds": -1.0,
        "is_first_transaction": 1,
        "new_device": 0,  # Contradiction: first txn must have new_device=1
        "new_location": 1,
        "transaction_hour": 12,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 422


def test_predict_first_transaction_invalid_time_since_previous(client: TestClient):
    """POST /api/v1/predict with is_first_transaction=1 but time_since_previous_seconds != -1 returns 422."""
    payload = {
        "amount": 25.0,
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 0,
        "account_avg_amount": 0.0,
        "amount_ratio": 0.0,
        "time_since_previous_seconds": 120.0,  # Contradiction: first txn must have time_since_previous_seconds=-1
        "is_first_transaction": 1,
        "new_device": 1,
        "new_location": 1,
        "transaction_hour": 12,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 422


def test_predict_subsequent_transaction_negative_time_validator(client: TestClient):
    """POST /api/v1/predict with is_first_transaction=0 but time_since_previous_seconds=-1 returns 422."""
    payload = {
        "amount": 50.0,
        "transactions_last_2_min": 1,
        "transactions_last_1_hour": 2,
        "account_avg_amount": 40.0,
        "amount_ratio": 1.25,
        "time_since_previous_seconds": -1.0,  # Contradiction: non-first txn must have time_since_previous_seconds >= 0
        "is_first_transaction": 0,
        "new_device": 0,
        "new_location": 0,
        "transaction_hour": 15,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 422


def test_predict_subsequent_transaction_with_new_device_and_location_accepted(client: TestClient):
    """POST /api/v1/predict with is_first_transaction=0, new_device=1, new_location=1 returns 200, NOT 422.

    Confirms validator is strictly one-directional: returning customers with new device/location
    are valid and fraud-relevant, not contradictions.
    """
    payload = {
        "amount": 75.0,
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 1,
        "account_avg_amount": 50.0,
        "amount_ratio": 1.5,
        "time_since_previous_seconds": 450.0,
        "is_first_transaction": 0,
        "new_device": 1,
        "new_location": 1,
        "transaction_hour": 16,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "fraud_probability" in data
    assert "ml_band" in data


def test_predict_missing_required_field(client: TestClient):
    """POST /api/v1/predict with a missing required field returns 422."""
    payload = {
        # 'amount' is missing
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 0,
        "account_avg_amount": 10.0,
        "amount_ratio": 1.0,
        "time_since_previous_seconds": 100.0,
        "is_first_transaction": 0,
        "new_device": 0,
        "new_location": 0,
        "transaction_hour": 14,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 422


def test_predict_out_of_range_value(client: TestClient):
    """POST /api/v1/predict with out-of-range value (transaction_hour=25) returns 422."""
    payload = {
        "amount": 25.0,
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 0,
        "account_avg_amount": 25.0,
        "amount_ratio": 1.0,
        "time_since_previous_seconds": 100.0,
        "is_first_transaction": 0,
        "new_device": 0,
        "new_location": 0,
        "transaction_hour": 25,  # Invalid: max is 23
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 422


def test_predict_amount_below_minimum(client: TestClient):
    """POST /api/v1/predict with amount below minimum (amount=0.00) returns 422."""
    payload = {
        "amount": 0.00,  # Invalid: min is 0.01
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 0,
        "account_avg_amount": 25.0,
        "amount_ratio": 1.0,
        "time_since_previous_seconds": 100.0,
        "is_first_transaction": 0,
        "new_device": 0,
        "new_location": 0,
        "transaction_hour": 12,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 422


def test_predict_field_order_independence(client: TestClient):
    """Feature vector construction uses contract order even if request JSON has fields in different order."""
    in_order_payload = {
        "amount": 120.0,
        "transactions_last_2_min": 1,
        "transactions_last_1_hour": 3,
        "account_avg_amount": 80.0,
        "amount_ratio": 1.5,
        "time_since_previous_seconds": 250.0,
        "is_first_transaction": 0,
        "new_device": 1,
        "new_location": 0,
        "transaction_hour": 18,
        "odd_hour": 0,
    }
    # Reverse the order of keys completely
    reversed_keys = list(reversed(list(in_order_payload.keys())))
    reversed_payload = {k: in_order_payload[k] for k in reversed_keys}

    res_in_order = client.post("/api/v1/predict", json=in_order_payload)
    res_reversed = client.post("/api/v1/predict", json=reversed_payload)

    assert res_in_order.status_code == 200
    assert res_reversed.status_code == 200

    data_in_order = res_in_order.json()
    data_reversed = res_reversed.json()

    assert data_in_order["fraud_probability"] == data_reversed["fraud_probability"]
    assert data_in_order["ml_band"] == data_reversed["ml_band"]
    assert data_in_order["review_threshold"] == data_reversed["review_threshold"]
    assert data_in_order["block_threshold"] == data_reversed["block_threshold"]


def test_predict_latency_positive(client: TestClient):
    """latency_ms is present, positive, and reasonably small (< 500ms) on successful prediction."""
    payload = {
        "amount": 40.0,
        "transactions_last_2_min": 0,
        "transactions_last_1_hour": 1,
        "account_avg_amount": 40.0,
        "amount_ratio": 1.0,
        "time_since_previous_seconds": 600.0,
        "is_first_transaction": 0,
        "new_device": 0,
        "new_location": 0,
        "transaction_hour": 15,
        "odd_hour": 0,
    }
    response = client.post("/api/v1/predict", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "latency_ms" in data
    assert isinstance(data["latency_ms"], (float, int))
    assert data["latency_ms"] > 0
    assert data["latency_ms"] < 500


def test_prediction_request_schema_matches_contract_directly():
    """PredictionRequest's fields, types, and constraints exactly match contracts/feature_schema_v1.json."""
    with open(CONTRACT_SCHEMA_PATH, "r", encoding="utf-8") as f:
        contract = json.load(f)

    contract_features = contract["features"]
    expected_names = [f["name"] for f in contract_features]
    actual_names = list(PredictionRequest.model_fields.keys())

    # 1. Names and exact order
    assert actual_names == expected_names, (
        f"Dynamic model field order mismatch.\nExpected: {expected_names}\nGot: {actual_names}"
    )

    # 2. Constraints and types per feature
    for feat in contract_features:
        name = feat["name"]
        field_info = PredictionRequest.model_fields[name]

        if "min" in feat:
            ge_vals = [m.ge for m in field_info.metadata if hasattr(m, "ge")]
            assert ge_vals == [feat["min"]], f"Feature '{name}' min mismatch: expected {feat['min']}, got {ge_vals}"

        if "max" in feat:
            le_vals = [m.le for m in field_info.metadata if hasattr(m, "le")]
            assert le_vals == [feat["max"]], f"Feature '{name}' max mismatch: expected {feat['max']}, got {le_vals}"

        if "allowed" in feat:
            allowed_args = list(get_args(field_info.annotation))
            assert allowed_args == feat["allowed"], (
                f"Feature '{name}' allowed mismatch: expected {feat['allowed']}, got {allowed_args}"
            )
        elif feat["type"] == "int":
            assert field_info.annotation is int, f"Feature '{name}' expected type int"
        elif feat["type"] == "float":
            assert field_info.annotation is float, f"Feature '{name}' expected type float"
