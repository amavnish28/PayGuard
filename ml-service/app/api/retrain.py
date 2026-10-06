"""API routes for model retraining and model activation in PayGuard ML Service."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import logging
from pathlib import Path
from typing import Any, Dict

from fastapi import APIRouter, HTTPException, Request, status
import joblib

from app.schemas.retrain import (
    ModelActivateRequest,
    ModelActivateResponse,
    RetrainResponse,
)
from app.services.predictor import Predictor
from app.services.retrainer import run_candidate_retraining

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["retraining"])


def _get_model_store_dir() -> Path:
    """Return Path to model_store directory."""
    service_root = Path(__file__).resolve().parents[2]
    return service_root / "model_store"


@router.post("/retrain", response_model=RetrainResponse)
async def retrain(request: Request) -> RetrainResponse:
    """Retrain candidate model on augmented dataset, calibrate, evaluate on test split, and export."""
    try:
        body = await request.json()
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Malformed request body: {e}",
        )

    if not isinstance(body, dict):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Request body must be a JSON object",
        )

    model_version_candidate = body.get("model_version_candidate")
    if not model_version_candidate or not isinstance(model_version_candidate, str) or not model_version_candidate.strip():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Field 'model_version_candidate' must be a non-empty string",
        )

    training_examples = body.get("training_examples")
    if training_examples is None or not isinstance(training_examples, list):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Field 'training_examples' must be a list of examples",
        )

    try:
        result = run_candidate_retraining(
            model_version_candidate=model_version_candidate.strip(),
            training_examples=training_examples,
        )
    except ValueError as e:
        logger.warning("Retrain validation error: %s", e)
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )
    except Exception as e:
        logger.exception("Unexpected error during candidate retraining: %s", e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Candidate retraining failed: {e}",
        )

    return RetrainResponse(**result)


def _load_and_validate_candidate(model_version: str, schema: dict[str, Any]) -> Tuple[Any, Any, Dict[str, Any], Predictor]:
    """Load model, explainer, and metadata for a version; validates files exist and are valid.

    Does NOT touch live app state.
    """
    model_store_dir = _get_model_store_dir()
    version_dir = model_store_dir / model_version

    if not version_dir.exists() or not version_dir.is_dir():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Model version directory '{model_version}' does not exist in model_store.",
        )

    model_path = version_dir / "model.joblib"
    explainer_path = version_dir / "explainer.joblib"
    metadata_path = version_dir / "metadata.json"

    for req_file in (model_path, explainer_path, metadata_path):
        if not req_file.exists():
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Required model artifact '{req_file.name}' not found for version '{model_version}'.",
            )

    try:
        model = joblib.load(model_path)
        explainer = joblib.load(explainer_path)
        with open(metadata_path, "r", encoding="utf-8") as f:
            metadata = json.load(f)

        for required_key in ("model_version", "block_threshold", "review_threshold"):
            if required_key not in metadata:
                raise ValueError(f"Missing required metadata key '{required_key}'")

        predictor = Predictor(
            model=model,
            explainer=explainer,
            metadata=metadata,
            schema=schema,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Failed to load or validate candidate model version '%s': %s", model_version, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Model files for version '{model_version}' are corrupted or unloadable: {e}",
        )

    return model, explainer, metadata, predictor


@router.post("/model/validate")
def validate_model(request: ModelActivateRequest, req: Request) -> Dict[str, Any]:
    """Validate that candidate model artifacts exist and load correctly without hot-swapping."""
    schema = getattr(req.app.state, "feature_schema", None)
    if schema is None:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Feature schema not available in app state.",
        )

    _load_and_validate_candidate(request.model_version.strip(), schema)
    return {
        "status": "valid",
        "model_version": request.model_version.strip(),
    }


@router.post("/model/activate", response_model=ModelActivateResponse)
def activate_model(request: ModelActivateRequest, req: Request) -> ModelActivateResponse:
    """Hot-swap the serving model to the specified version after load-then-swap validation."""
    model_version = request.model_version.strip()
    schema = getattr(req.app.state, "feature_schema", None)
    if schema is None:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Feature schema not available in app state.",
        )

    # 1. Load and validate completely BEFORE touching the live references
    new_model, new_explainer, new_metadata, new_predictor = _load_and_validate_candidate(model_version, schema)

    # 2. Hot-swap live references in app.state atomically in Python GIL
    req.app.state.model = new_model
    req.app.state.explainer = new_explainer
    req.app.state.metadata = new_metadata
    req.app.state.predictor = new_predictor
    req.app.state.model_loaded = True

    activated_at = datetime.now(timezone.utc).isoformat()
    logger.info("Successfully hot-swapped live serving model to version '%s'", model_version)

    return ModelActivateResponse(
        status="activated",
        model_version=model_version,
        activated_at=activated_at,
    )
