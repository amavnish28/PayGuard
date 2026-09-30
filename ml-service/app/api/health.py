"""Health and model metadata endpoints for PayGuard ML service."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Request, status
from pydantic import BaseModel

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    """Health check status."""

    status: str
    model_loaded: bool


class ModelInfoResponse(BaseModel):
    """Model performance and metadata information."""

    model_version: str
    trained_at: str
    feature_order: list[str]
    feature_schema_version: str
    block_threshold: float
    review_threshold: float
    val_metrics: dict[str, Any]


@router.get("/health", response_model=HealthResponse)
def health(request: Request) -> HealthResponse:
    """Return service health status and whether the ML model is currently loaded.

    Must work even if model loading failed (returns model_loaded: false, does not crash),
    for container orchestrator health checks.
    """
    model = getattr(request.app.state, "model", None)
    is_loaded = bool(getattr(request.app.state, "model_loaded", False) and model is not None)
    return HealthResponse(status="ok", model_loaded=is_loaded)


@router.get("/api/v1/model/info", response_model=ModelInfoResponse)
def model_info(request: Request) -> ModelInfoResponse:
    """Return model version, trained timestamp, feature ordering, and validation metrics."""
    metadata: dict[str, Any] | None = getattr(request.app.state, "metadata", None)
    if metadata is None:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Model metadata is not available.",
        )

    return ModelInfoResponse(
        model_version=metadata["model_version"],
        trained_at=metadata["trained_at"],
        feature_order=metadata["feature_order"],
        feature_schema_version=metadata["feature_schema_version"],
        block_threshold=float(metadata["block_threshold"]),
        review_threshold=float(metadata["review_threshold"]),
        val_metrics=metadata.get("val_metrics", {}),
    )
