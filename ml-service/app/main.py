"""Main FastAPI application for PayGuard ML Service."""

from __future__ import annotations

from contextlib import asynccontextmanager
import json
import logging
from pathlib import Path
import sys
from typing import Any

from fastapi import FastAPI
import joblib

from app.api.health import router as health_router
from app.api.predict import router as predict_router
from app.services.predictor import Predictor

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger(__name__)


def _find_path(candidates: list[Path], description: str) -> Path:
    """Find the first existing path among candidates or raise a clear error."""
    for c in candidates:
        if c.resolve().exists():
            return c.resolve()
    searched = "\n  - " + "\n  - ".join(str(c) for c in candidates)
    raise RuntimeError(f"Startup failed: Could not locate required {description}. Checked:{searched}")


def load_app_state(app: FastAPI) -> None:
    """Load schema, model, explainer, and metadata into app.state on startup."""
    app_dir = Path(__file__).resolve().parent
    ml_service_dir = app_dir.parent
    repo_root = ml_service_dir.parent

    # 1. Feature schema contract
    schema_path = _find_path(
        [
            repo_root / "contracts" / "feature_schema_v1.json",
            Path.cwd() / "contracts" / "feature_schema_v1.json",
            Path.cwd().parent / "contracts" / "feature_schema_v1.json",
        ],
        "feature schema contract ('contracts/feature_schema_v1.json')",
    )
    try:
        with open(schema_path, "r", encoding="utf-8") as f:
            feature_schema: dict[str, Any] = json.load(f)
    except Exception as e:
        raise RuntimeError(f"Startup failed: Could not parse feature schema JSON at '{schema_path}': {e}") from e

    # 2. Model
    model_path = _find_path(
        [
            ml_service_dir / "model_store" / "xgboost_v1" / "model.joblib",
            Path.cwd() / "model_store" / "xgboost_v1" / "model.joblib",
        ],
        "model artifact ('model_store/xgboost_v1/model.joblib')",
    )
    try:
        model: Any = joblib.load(model_path)
    except Exception as e:
        raise RuntimeError(f"Startup failed: Could not load model from '{model_path}': {e}") from e

    # 3. Explainer
    explainer_path = _find_path(
        [
            ml_service_dir / "model_store" / "xgboost_v1" / "explainer.joblib",
            Path.cwd() / "model_store" / "xgboost_v1" / "explainer.joblib",
        ],
        "explainer artifact ('model_store/xgboost_v1/explainer.joblib')",
    )
    try:
        explainer: Any = joblib.load(explainer_path)
    except Exception as e:
        raise RuntimeError(f"Startup failed: Could not load explainer from '{explainer_path}': {e}") from e

    # 4. Metadata
    metadata_path = _find_path(
        [
            ml_service_dir / "model_store" / "xgboost_v1" / "metadata.json",
            Path.cwd() / "model_store" / "xgboost_v1" / "metadata.json",
        ],
        "metadata file ('model_store/xgboost_v1/metadata.json')",
    )
    try:
        with open(metadata_path, "r", encoding="utf-8") as f:
            metadata: dict[str, Any] = json.load(f)
    except Exception as e:
        raise RuntimeError(f"Startup failed: Could not parse metadata JSON at '{metadata_path}': {e}") from e

    # Store in FastAPI app.state (not globals), accessible via dependency injection
    app.state.feature_schema = feature_schema
    app.state.model = model
    app.state.explainer = explainer
    app.state.metadata = metadata
    app.state.model_loaded = True
    app.state.predictor = Predictor(
        model=model,
        explainer=explainer,
        metadata=metadata,
        schema=feature_schema,
    )

    model_version = metadata.get("model_version", "unknown")
    feature_count = len(feature_schema.get("features", []))

    # Log to stdout on successful startup
    startup_msg = f"[STARTUP] Successfully loaded model_version='{model_version}' with {feature_count} features."
    sys.stdout.write(startup_msg + "\n")
    sys.stdout.flush()
    logger.info(startup_msg)


def create_app(load_on_startup: bool = True) -> FastAPI:
    """Create and configure FastAPI application instance."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if load_on_startup:
            load_app_state(app)
        yield

    app = FastAPI(
        title="PayGuard ML Service",
        description="FastAPI service serving production XGBoost fraud detection model",
        version="1.0.0",
        lifespan=lifespan,
    )

    # Initialize default state attributes
    app.state.feature_schema = None
    app.state.model = None
    app.state.explainer = None
    app.state.metadata = None
    app.state.model_loaded = False
    app.state.predictor = None

    # Register routers
    app.include_router(health_router)
    app.include_router(predict_router)

    return app


# Default app instance for uvicorn
app = create_app()
