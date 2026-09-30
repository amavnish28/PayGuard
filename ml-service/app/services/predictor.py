"""Prediction service for PayGuard ML inference and explanation."""

from __future__ import annotations

import time
from typing import Any

import numpy as np

from app.schemas.predict import PredictionRequest, PredictionResponse, ShapReason


class Predictor:
    """Encapsulates fraud inference, probability calibration, and SHAP explanation."""

    def __init__(
        self,
        model: Any,
        explainer: Any,
        metadata: dict[str, Any],
        schema: dict[str, Any],
    ) -> None:
        self.model = model
        self.explainer = explainer
        self.metadata = metadata
        self.schema = schema

        self.model_version: str = metadata.get("model_version", "unknown")
        self.review_threshold: float = float(metadata["review_threshold"])
        self.block_threshold: float = float(metadata["block_threshold"])

    def predict(self, request: PredictionRequest) -> PredictionResponse:
        """Run ML inference and optional SHAP explanation on the given prediction request."""
        start_time = time.perf_counter()

        # Build feature vector in the EXACT order defined by feature_schema_v1.json,
        # reading the contract order explicitly every time to prevent column misalignment.
        feature_names = [f["name"] for f in self.schema["features"]]
        feature_vector = [getattr(request, name) for name in feature_names]
        X = np.array([feature_vector], dtype=float)

        # Predict probability for fraud class (index 1)
        probs = self.model.predict_proba(X)
        fraud_probability = float(probs[0, 1])

        # Compute ML-only score band.
        # NOTE: This is the ML-only score band, NOT the final APPROVE/REVIEW/BLOCK decision.
        # The actual hybrid decision combining this with the Phase 4 rule score is a later Spring Boot responsibility.
        if fraud_probability < self.review_threshold:
            ml_band = "low"
        elif fraud_probability < self.block_threshold:
            ml_band = "review"
        else:
            ml_band = "block"

        # If probability >= review_threshold, compute SHAP explanation for top 5 features.
        # Below review_threshold, SHAP computation is skipped to minimize server latency.
        shap_reasons: list[ShapReason] | None = None
        if fraud_probability >= self.review_threshold and self.explainer is not None:
            shap_explanation = self.explainer(X)
            shap_vals = shap_explanation.values[0]

            reasons: list[ShapReason] = []
            for i, name in enumerate(feature_names):
                sv = float(shap_vals[i])
                fv = getattr(request, name)
                reasons.append(
                    ShapReason(
                        feature=name,
                        shap_value=round(sv, 6),
                        feature_value=fv,
                    )
                )

            # Sort descending by absolute SHAP contribution and take top 5
            reasons.sort(key=lambda r: abs(r.shap_value), reverse=True)
            shap_reasons = reasons[:5]

        latency_ms = (time.perf_counter() - start_time) * 1000.0

        return PredictionResponse(
            fraud_probability=round(fraud_probability, 6),
            model_version=self.model_version,
            review_threshold=self.review_threshold,
            block_threshold=self.block_threshold,
            ml_band=ml_band,
            shap_reasons=shap_reasons,
            latency_ms=round(latency_ms, 3),
        )
