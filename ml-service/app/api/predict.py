"""Prediction API route for PayGuard ML service."""

from __future__ import annotations

import logging
from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.schemas.predict import PredictionRequest, PredictionResponse
from app.services.predictor import Predictor

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["prediction"])


def get_predictor(request: Request) -> Predictor:
    """Dependency injection helper to retrieve the Predictor service from app state."""
    predictor: Predictor | None = getattr(request.app.state, "predictor", None)
    if predictor is None:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Prediction service is not available.",
        )
    return predictor


@router.post("/predict", response_model=PredictionResponse)
def predict(
    request: PredictionRequest,
    predictor: Predictor = Depends(get_predictor),
) -> PredictionResponse:
    """Score transaction risk and provide SHAP feature attribution when probability >= review threshold."""
    try:
        return predictor.predict(request)
    except Exception as e:
        logger.exception("Prediction failed unexpectedly: %s", e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Prediction service encountered an unexpected error.",
        )
