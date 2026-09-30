"""Pydantic schemas for PayGuard ML service prediction requests and responses.

PredictionRequest is dynamically generated from contracts/feature_schema_v1.json.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, Field, create_model, model_validator


def _resolve_contract_schema_path(explicit_path: Path | str | None = None) -> Path:
    """Resolve the path to contracts/feature_schema_v1.json across execution contexts."""
    if explicit_path is not None:
        p = Path(explicit_path).resolve()
        if p.exists():
            return p

    candidates = [
        # Relative to this file: ml-service/app/schemas/predict.py -> ../../../contracts/
        Path(__file__).resolve().parents[3] / "contracts" / "feature_schema_v1.json",
        # Relative to ml-service working directory
        Path.cwd() / "contracts" / "feature_schema_v1.json",
        Path.cwd().parent / "contracts" / "feature_schema_v1.json",
    ]
    for candidate in candidates:
        if candidate.resolve().exists():
            return candidate.resolve()

    raise FileNotFoundError(
        "Could not resolve 'contracts/feature_schema_v1.json'. Checked candidates: "
        + ", ".join(str(c) for c in candidates)
    )


def load_feature_schema(schema_path: Path | str | None = None) -> dict[str, Any]:
    """Load the feature contract JSON file."""
    path = _resolve_contract_schema_path(schema_path)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


class BasePredictionRequest(BaseModel):
    """Base class providing consistency validation across transaction history features."""

    @model_validator(mode="after")
    def validate_transaction_history_consistency(self) -> BasePredictionRequest:
        # NOTE: This validator is ONE-DIRECTIONAL. It must NOT reject new_device=1 or
        # new_location=1 when is_first_transaction=0 — a returning customer using a
        # new device/location on a later transaction is valid and fraud-relevant,
        # not a contradiction.
        is_first = getattr(self, "is_first_transaction", None)
        new_dev = getattr(self, "new_device", None)
        new_loc = getattr(self, "new_location", None)
        time_prev = getattr(self, "time_since_previous_seconds", None)

        if is_first == 1:
            if not (new_dev == 1 and new_loc == 1 and time_prev == -1):
                raise ValueError(
                    f"First transaction (is_first_transaction=1) requires new_device=1, "
                    f"new_location=1, and time_since_previous_seconds=-1. "
                    f"Received new_device={new_dev}, new_location={new_loc}, "
                    f"time_since_previous_seconds={time_prev}."
                )
        elif is_first == 0:
            if time_prev is not None and time_prev < 0:
                raise ValueError(
                    f"Subsequent transaction (is_first_transaction=0) requires "
                    f"time_since_previous_seconds >= 0 (received {time_prev})."
                )
        return self


def build_prediction_request_model(schema_dict: dict[str, Any] | None = None) -> type[BasePredictionRequest]:
    """Dynamically generate PredictionRequest from contracts/feature_schema_v1.json."""
    if schema_dict is None:
        schema_dict = load_feature_schema()

    features = schema_dict.get("features", [])
    if not features:
        raise ValueError("Feature contract must define a non-empty 'features' list.")

    field_definitions: dict[str, Any] = {}
    for feat in features:
        name = feat["name"]
        field_kwargs: dict[str, Any] = {}

        if "min" in feat:
            field_kwargs["ge"] = feat["min"]
        if "max" in feat:
            field_kwargs["le"] = feat["max"]
        if "source" in feat:
            field_kwargs["description"] = feat["source"]

        if "allowed" in feat:
            field_type = Literal[tuple(feat["allowed"])]
        elif feat["type"] == "int":
            field_type = int
        elif feat["type"] == "float":
            field_type = float
        else:
            raise ValueError(f"Unsupported feature type '{feat.get('type')}' for feature '{name}'")

        field_definitions[name] = (field_type, Field(..., **field_kwargs))

    return create_model(
        "PredictionRequest",
        __base__=BasePredictionRequest,
        __doc__="Dynamically generated prediction request model from contracts/feature_schema_v1.json",
        **field_definitions,
    )


# Generate PredictionRequest at module import time from the single-source-of-truth contract
PredictionRequest = build_prediction_request_model()


class ShapReason(BaseModel):
    """Individual SHAP explanation contribution."""

    feature: str
    shap_value: float
    feature_value: float | int


class PredictionResponse(BaseModel):
    """PayGuard ML prediction response."""

    fraud_probability: float
    model_version: str
    review_threshold: float
    block_threshold: float

    # NOTE: ml_band is the ML-only score band, NOT the final APPROVE/REVIEW/BLOCK decision.
    # The actual hybrid decision combining this with the Phase 4 rule score is a later Spring Boot responsibility.
    ml_band: Literal["low", "review", "block"]

    shap_reasons: list[ShapReason] | None = None
    latency_ms: float
