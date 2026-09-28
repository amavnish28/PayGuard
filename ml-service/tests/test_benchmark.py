"""Tests for Track 1 benchmark: models, pipelines, threshold tuning, and test eval."""

import json
from pathlib import Path
import numpy as np
import pandas as pd
import pytest

from training import config
from training.benchmark import find_best_f1_threshold
from training.final_test_eval import evaluate_on_test
from training.models import VALID_IMBALANCES, VALID_MODELS, build_pipeline


@pytest.fixture(scope="module")
def synthetic_data():
    """Generate small synthetic imbalanced dataset: ~2000 rows, ~5% positives, 29 named columns."""
    np.random.seed(42)
    n_samples = 2000
    n_positives = 100  # 5% positives

    cols = config.TRACK_1_FEATURES  # V1..V28 and Amount
    X = pd.DataFrame(np.random.randn(n_samples, 29), columns=cols)
    X["Amount"] = np.abs(X["Amount"]) * 50.0

    y = np.zeros(n_samples, dtype=int)
    y[:n_positives] = 1

    # Shuffle rows
    perm = np.random.permutation(n_samples)
    X = X.iloc[perm].reset_index(drop=True)
    y = pd.Series(y[perm], name="Class")

    return X, y


def test_track_1_feature_set():
    """Assert Track 1 feature set has 29 features: no Time, no Class, only V1..V28 and Amount."""
    features = config.TRACK_1_FEATURES
    assert len(features) == 29
    assert "Time" not in features
    assert "Class" not in features
    expected_order = [f"V{i}" for i in range(1, 29)] + ["Amount"]
    assert features == expected_order


def test_smote_step_presence(synthetic_data):
    """Assert 'smote' appears in named_steps only when imbalance == 'smote'."""
    X, y = synthetic_data
    for model_name in VALID_MODELS:
        for imbalance in VALID_IMBALANCES:
            pipe = build_pipeline(model_name, imbalance, y)
            assert "prep" in pipe.named_steps
            assert "clf" in pipe.named_steps
            if imbalance == "smote":
                assert "smote" in pipe.named_steps
            else:
                assert "smote" not in pipe.named_steps


def test_scale_pos_weight_value(synthetic_data):
    """Assert scale_pos_weight equals n_neg/n_pos for xgboost + class_weight, and is 1 otherwise."""
    X, y = synthetic_data
    n_pos = int((y == 1).sum())
    n_neg = int((y == 0).sum())
    expected_scale_pos_weight = n_neg / n_pos

    pipe_cw = build_pipeline("xgboost", "class_weight", y)
    clf_cw = pipe_cw.named_steps["clf"]
    assert clf_cw.scale_pos_weight == pytest.approx(expected_scale_pos_weight)

    pipe_none = build_pipeline("xgboost", "none", y)
    clf_none = pipe_none.named_steps["clf"]
    assert clf_none.scale_pos_weight == 1

    pipe_smote = build_pipeline("xgboost", "smote", y)
    clf_smote = pipe_smote.named_steps["clf"]
    assert clf_smote.scale_pos_weight == 1


def test_f1_optimal_threshold_helper():
    """Assert the F1-optimal threshold helper returns the threshold with maximum F1 on a hand-made example."""
    # Hand-crafted probabilities and labels:
    # Negatives have probabilities <= 0.40, positives have probabilities >= 0.60
    y_true = np.array([0, 0, 0, 1, 1, 1])
    y_probs = np.array([0.05, 0.15, 0.35, 0.65, 0.85, 0.95])

    thresh, f1, precision, recall = find_best_f1_threshold(y_true, y_probs)
    assert f1 == pytest.approx(1.0)
    assert precision == pytest.approx(1.0)
    assert recall == pytest.approx(1.0)
    assert 0.35 < thresh <= 0.65


def test_final_test_eval_refuses_rerun(tmp_path: Path):
    """Assert final_test_eval refuses a second run when test_result.json exists unless force=True."""
    mock_winner = {
        "winner": {"model": "logreg", "imbalance": "none"},
        "val_tuned_threshold": 0.5,
    }
    winner_file = tmp_path / "benchmark_winner.json"
    with open(winner_file, "w", encoding="utf-8") as f:
        json.dump(mock_winner, f)

    # Place an existing test_result.json
    test_result_file = tmp_path / "test_result.json"
    with open(test_result_file, "w", encoding="utf-8") as f:
        json.dump({"already_ran": True}, f)

    # Calling without force must raise RuntimeError
    with pytest.raises(RuntimeError, match="Evaluation refused"):
        evaluate_on_test(reports_dir=tmp_path, force=False)


@pytest.mark.parametrize("model_name", ["logreg", "random_forest", "xgboost"])
@pytest.mark.parametrize("imbalance", ["none", "class_weight", "smote"])
def test_all_9_pipelines_fit_and_predict(synthetic_data, model_name, imbalance):
    """Assert build_pipeline works for all 9 combinations, fits on synthetic data, and returns predict_proba."""
    X, y = synthetic_data
    pipe = build_pipeline(model_name, imbalance, y)
    pipe.fit(X, y)
    proba = pipe.predict_proba(X)
    assert proba.shape == (len(X), 2)
    assert np.all(proba >= 0.0)
    assert np.all(proba <= 1.0)
