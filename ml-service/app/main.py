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
from app.api.retrain import router as retrain_router
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


def _resolve_version_dir(model_store_dirs: list[Path], version_name: str) -> Path | None:
    """Resolve version directory allowing hyphen/underscore variants."""
    for base_dir in model_store_dirs:
        candidates = [
            base_dir / version_name,
            base_dir / version_name.replace("-", "_"),
            base_dir / version_name.replace("_", "-"),
        ]
        for c in candidates:
            if c.resolve().exists() and c.resolve().is_dir():
                return c.resolve()
    return None


def _load_version_artifacts(version_dir: Path, feature_schema: dict[str, Any]) -> tuple[Any, Any, dict[str, Any], Predictor]:
    """Load model, explainer, metadata from directory and construct Predictor."""
    model_path = version_dir / "model.joblib"
    explainer_path = version_dir / "explainer.joblib"
    metadata_path = version_dir / "metadata.json"

    for req_file in (model_path, explainer_path, metadata_path):
        if not req_file.exists():
            raise FileNotFoundError(f"Required model artifact '{req_file.name}' not found in '{version_dir}'.")

    model = joblib.load(model_path)
    explainer = joblib.load(explainer_path)
    with open(metadata_path, "r", encoding="utf-8") as f:
        metadata = json.load(f)

    for required_key in ("model_version", "block_threshold", "review_threshold"):
        if required_key not in metadata:
            raise ValueError(f"Missing required metadata key '{required_key}' in '{metadata_path}'")

    predictor = Predictor(
        model=model,
        explainer=explainer,
        metadata=metadata,
        schema=feature_schema,
    )
    return model, explainer, metadata, predictor


def load_app_state(app: FastAPI, model_store_dir_override: Path | None = None) -> None:
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

    # 2. Resolve model_store directory
    if model_store_dir_override is not None:
        model_store_dir = Path(model_store_dir_override).resolve()
    elif getattr(app.state, "model_store_dir", None):
        model_store_dir = Path(app.state.model_store_dir).resolve()
    else:
        model_store_dirs = [
            ml_service_dir / "model_store",
            Path.cwd() / "model_store",
        ]
        model_store_dir = None
        for d in model_store_dirs:
            if d.resolve().exists() and d.resolve().is_dir():
                model_store_dir = d.resolve()
                break
        if model_store_dir is None:
            model_store_dir = ml_service_dir / "model_store"

    app.state.model_store_dir = model_store_dir

    # 3. Load active model if specified in active_model.json and valid; otherwise fallback to xgboost_v1
    active_model_file = model_store_dir / "active_model.json"
    loaded = False
    model, explainer, metadata, predictor = None, None, None, None

    if active_model_file.exists():
        try:
            with open(active_model_file, "r", encoding="utf-8") as f:
                active_data = json.load(f)
            target_version = active_data.get("model_version")
            if not target_version or not isinstance(target_version, str):
                raise ValueError(f"active_model.json does not contain a valid 'model_version': {active_data}")
            version_dir = _resolve_version_dir([model_store_dir], target_version.strip())
            if version_dir is None:
                raise FileNotFoundError(f"Model version directory '{target_version}' not found in '{model_store_dir}'")
            model, explainer, metadata, predictor = _load_version_artifacts(version_dir, feature_schema)
            loaded = True
            logger.info("Loaded active model '%s' specified in %s", target_version, active_model_file)
        except Exception as e:
            logger.warning(
                "Failed to load active model from '%s': %s. Falling back to xgboost_v1.",
                active_model_file,
                e,
            )

    if not loaded:
        v1_dir = _resolve_version_dir([model_store_dir], "xgboost_v1")
        if v1_dir is None:
            raise RuntimeError(f"Startup failed: Default model directory 'xgboost_v1' not found in '{model_store_dir}'.")
        try:
            model, explainer, metadata, predictor = _load_version_artifacts(v1_dir, feature_schema)
            logger.info("Loaded default model version 'xgboost_v1'.")
        except Exception as e:
            raise RuntimeError(f"Startup failed: Could not load default xgboost_v1 model: {e}") from e

    # Store in FastAPI app.state (not globals), accessible via dependency injection
    app.state.feature_schema = feature_schema
    app.state.model = model
    app.state.explainer = explainer
    app.state.metadata = metadata
    app.state.model_loaded = True
    app.state.predictor = predictor

    model_version = metadata.get("model_version", "unknown")
    feature_count = len(feature_schema.get("features", []))

    # Log to stdout on successful startup
    startup_msg = f"[STARTUP] Successfully loaded model_version='{model_version}' with {feature_count} features."
    sys.stdout.write(startup_msg + "\n")
    sys.stdout.flush()
    logger.info(startup_msg)


def create_app(load_on_startup: bool = True, model_store_dir_override: Path | None = None) -> FastAPI:
    """Create and configure FastAPI application instance."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if load_on_startup:
            load_app_state(app, model_store_dir_override=model_store_dir_override)
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
    app.state.model_store_dir = model_store_dir_override

    # Register routers
    app.include_router(health_router)
    app.include_router(predict_router)
    app.include_router(retrain_router)

    return app


# Default app instance for uvicorn
app = create_app()
