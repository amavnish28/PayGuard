"""Pydantic schemas for PayGuard ML service retraining and model activation."""

from __future__ import annotations

from typing import Any, Dict, List, Literal
from pydantic import BaseModel, Field


class TrainingExampleInput(BaseModel):
    """A single verdict-derived training example with features and label."""

    features: Dict[str, Any] = Field(..., description="The 11 contract features dictionary")
    label: Literal[0, 1] = Field(..., description="0 for LEGITIMATE, 1 for FRAUD")


class RetrainRequest(BaseModel):
    """Payload for POST /api/v1/retrain."""

    model_version_candidate: str = Field(..., min_length=1, description="Candidate model version name, e.g. xgboost-v2")
    training_examples: List[TrainingExampleInput] = Field(..., description="List of verdict-derived training examples")


class RetrainResponse(BaseModel):
    """Response returned by POST /api/v1/retrain."""

    model_version_candidate: str
    metrics: Dict[str, Any]
    training_data_summary: Dict[str, Any]
    model_store_path: str
    block_threshold: float
    review_threshold: float


class ModelActivateRequest(BaseModel):
    """Payload for POST /api/v1/model/activate and POST /api/v1/model/validate."""

    model_version: str = Field(..., min_length=1, description="Model version name in model_store to activate")


class ModelActivateResponse(BaseModel):
    """Response returned by POST /api/v1/model/activate."""

    status: str
    model_version: str
    activated_at: str
