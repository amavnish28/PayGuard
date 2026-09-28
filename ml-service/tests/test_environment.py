import numpy as np
import pandas as pd
import sklearn
import imblearn
import xgboost
import shap
import fastapi
import pydantic
from xgboost import XGBClassifier
from imblearn.over_sampling import SMOTE
from imblearn.pipeline import Pipeline
from sklearn.dummy import DummyClassifier


def test_environment_imports():
    assert np.__version__
    assert pd.__version__
    assert sklearn.__version__
    assert imblearn.__version__
    assert xgboost.__version__
    assert shap.__version__
    assert fastapi.__version__
    assert pydantic.__version__


def test_environment_stack():
    # 1. Generate random imbalanced data: a few hundred rows, 11 features, ~5% positives
    np.random.seed(42)
    n_samples = 300
    n_features = 11
    n_positives = int(n_samples * 0.05)  # 15 positives out of 300

    X = np.random.randn(n_samples, n_features).astype(np.float32)
    y = np.zeros(n_samples, dtype=int)
    y[:n_positives] = 1

    # Shuffle dataset
    indices = np.arange(n_samples)
    np.random.shuffle(indices)
    X = X[indices]
    y = y[indices]

    # 2. Train a tiny XGBClassifier on random imbalanced data
    model = XGBClassifier(n_estimators=10, max_depth=3, random_state=42, eval_metric="logloss")
    model.fit(X, y)

    # 3. Run shap.TreeExplainer on it and compute SHAP values for one row; assert result has 11 values
    explainer = shap.TreeExplainer(model)
    row = X[0:1]
    shap_explanation = explainer(row)
    values = shap_explanation.values

    # Check that SHAP values contain 11 feature values
    if values.ndim == 3:
        # Binary classification format (n_samples, n_features, n_classes)
        assert values.shape[1] == 11, f"Expected 11 feature values, got {values.shape[1]}"
    elif values.ndim == 2:
        # Single output format (n_samples, n_features)
        assert values.shape == (1, 11), f"Expected shape (1, 11), got {values.shape}"
        assert len(values[0]) == 11
    else:
        assert len(values) == 11

    # 4. Run SMOTE fit_resample on the same data inside an imblearn Pipeline and assert it fits
    pipeline = Pipeline([
        ("smote", SMOTE(random_state=42, k_neighbors=3)),
        ("classifier", DummyClassifier(strategy="most_frequent"))
    ])
    pipeline.fit(X, y)

    # Verify SMOTE resampled the minority class
    smote_step = pipeline.named_steps["smote"]
    X_resampled, y_resampled = smote_step.fit_resample(X, y)
    assert len(X_resampled) > n_samples, "SMOTE should have generated synthetic samples"
    assert (y_resampled == 1).sum() == (y_resampled == 0).sum(), "Classes should be balanced after SMOTE"
