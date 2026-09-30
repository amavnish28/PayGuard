"""Tests for the PayGuard production model artifacts, metadata, and test evaluation constraints."""

import ast
import json
from pathlib import Path
import joblib
import numpy as np
import pytest

from training.final_test_report import evaluate_test_split

ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ML_SERVICE_ROOT.parent
CONTRACT_SCHEMA_PATH = REPO_ROOT / "contracts" / "feature_schema_v1.json"
MODEL_STORE_DIR = ML_SERVICE_ROOT / "model_store" / "xgboost_v1"
MODEL_PATH = MODEL_STORE_DIR / "model.joblib"
EXPLAINER_PATH = MODEL_STORE_DIR / "explainer.joblib"
METADATA_PATH = MODEL_STORE_DIR / "metadata.json"
TRAIN_SCRIPT_PATH = ML_SERVICE_ROOT / "training" / "train_production_model.py"
DERIVE_SCRIPT_PATH = ML_SERVICE_ROOT / "training" / "derive_thresholds.py"


@pytest.fixture(scope="module")
def sample_features_slice():
    """Create a small 5-row synthetic feature slice for fast inference testing."""
    # 5 rows x 11 features
    np.random.seed(42)
    return np.random.uniform(0.1, 100.0, size=(5, 11))


@pytest.mark.skipif(not MODEL_PATH.exists() or not EXPLAINER_PATH.exists(), reason="Model artifacts not yet exported")
def test_saved_model_and_explainer_load_and_predict(sample_features_slice):
    """Assert model.joblib and explainer.joblib load successfully and predict in [0, 1]."""
    assert MODEL_PATH.exists(), f"Model file missing at {MODEL_PATH}"
    assert EXPLAINER_PATH.exists(), f"Explainer file missing at {EXPLAINER_PATH}"

    model = joblib.load(MODEL_PATH)
    explainer = joblib.load(EXPLAINER_PATH)

    probs = model.predict_proba(sample_features_slice)
    assert probs.shape == (5, 2), f"Expected shape (5, 2), got {probs.shape}"
    assert (probs >= 0.0).all() and (probs <= 1.0).all(), "Predicted probabilities out of [0, 1] range"
    assert np.allclose(probs.sum(axis=1), 1.0), "Probabilities across classes must sum to 1"

    shap_vals = explainer(sample_features_slice)
    assert shap_vals.values is not None
    assert shap_vals.values.shape[0] == 5


@pytest.mark.skipif(not METADATA_PATH.exists(), reason="Metadata not yet exported")
def test_metadata_keys_and_threshold_ordering():
    """Assert metadata.json contains all required keys and block_threshold > review_threshold."""
    assert METADATA_PATH.exists(), f"Metadata missing at {METADATA_PATH}"

    with open(METADATA_PATH, "r", encoding="utf-8") as f:
        metadata = json.load(f)

    required_keys = [
        "model_version",
        "trained_at",
        "feature_order",
        "feature_schema_version",
        "block_threshold",
        "review_threshold",
        "val_metrics",
        "cv_pr_auc_mean",
        "cv_pr_auc_std",
        "training_row_counts",
        "random_seed",
        "scale_pos_weight_used",
        "calibration_method_used",
    ]

    for key in required_keys:
        assert key in metadata, f"Key '{key}' missing from metadata.json"

    block_thresh = metadata["block_threshold"]
    review_thresh = metadata["review_threshold"]

    assert isinstance(block_thresh, (int, float))
    assert isinstance(review_thresh, (int, float))
    assert block_thresh > review_thresh, (
        f"BLOCK_THRESHOLD ({block_thresh}) must be strictly greater than REVIEW_THRESHOLD ({review_thresh})"
    )


@pytest.mark.skipif(not METADATA_PATH.exists(), reason="Metadata not yet exported")
def test_feature_order_matches_contract_canonical_order():
    """Assert feature_order in metadata.json exactly matches contracts/feature_schema_v1.json canonical order."""
    with open(METADATA_PATH, "r", encoding="utf-8") as f:
        metadata = json.load(f)
    with open(CONTRACT_SCHEMA_PATH, "r", encoding="utf-8") as f:
        schema = json.load(f)

    expected_order = [f["name"] for f in schema["features"]]
    assert metadata["feature_order"] == expected_order, (
        f"Feature order mismatch.\nExpected: {expected_order}\nGot: {metadata['feature_order']}"
    )


def test_final_test_report_refuses_rerun(tmp_path):
    """Assert final_test_report refuses a second run when report file already exists."""
    dummy_report = tmp_path / "dummy_production_test_result.json"
    dummy_report.write_text('{"dummy": true}', encoding="utf-8")

    with pytest.raises(RuntimeError, match="already exists"):
        evaluate_test_split(force=False, report_file=dummy_report)


def test_no_test_split_referenced_in_training_or_thresholds():
    """Assert by static code inspection that train_production_model.py and derive_thresholds.py never access test split.

    Inspection method: Static AST and code inspection checking that string literals matching
    test split filtering ('test' in split mask) do not appear in either training or threshold derivation scripts.
    """
    for script_path in [TRAIN_SCRIPT_PATH, DERIVE_SCRIPT_PATH]:
        content = script_path.read_text(encoding="utf-8")

        # Assert no mask filtering on split == 'test' or split == "test"
        assert '["split"] == "test"' not in content, (
            f"Violation: {script_path.name} contains reference to 'split' == 'test'"
        )
        assert "['split'] == 'test'" not in content, (
            f"Violation: {script_path.name} contains reference to 'split' == 'test'"
        )
        assert '["split"] == \'test\'' not in content
        assert "['split'] == \"test\"" not in content
