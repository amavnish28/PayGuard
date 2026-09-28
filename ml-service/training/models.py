"""Track 1 benchmark model pipelines: 3 architectures x 3 class imbalance strategies."""

from typing import Union
from imblearn.over_sampling import SMOTE
from imblearn.pipeline import Pipeline
import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from xgboost import XGBClassifier

from training import config

VALID_MODELS = {"logreg", "random_forest", "xgboost"}
VALID_IMBALANCES = {"none", "class_weight", "smote"}


class LogregPreprocessor(BaseEstimator, TransformerMixin):
    """Preprocessor for Logistic Regression: log1p on Amount column, then StandardScaler on all 29 features."""

    def __init__(self):
        self.scaler = StandardScaler()

    def _apply_log1p(self, X: Union[pd.DataFrame, np.ndarray]) -> np.ndarray:
        if isinstance(X, pd.DataFrame):
            X_copy = X.copy()
            if "Amount" in X_copy.columns:
                X_copy["Amount"] = np.log1p(X_copy["Amount"].astype(np.float64))
            return X_copy.to_numpy(dtype=np.float64)
        else:
            X_arr = np.array(X, copy=True, dtype=np.float64)
            # In Track 1 features, Amount is the 29th feature (index 28)
            X_arr[:, -1] = np.log1p(X_arr[:, -1])
            return X_arr

    def fit(self, X: Union[pd.DataFrame, np.ndarray], y=None):
        X_trans = self._apply_log1p(X)
        self.scaler.fit(X_trans)
        return self

    def transform(self, X: Union[pd.DataFrame, np.ndarray]) -> np.ndarray:
        X_trans = self._apply_log1p(X)
        return self.scaler.transform(X_trans)


def build_pipeline(
    model_name: str,
    imbalance: str,
    y_train: Union[pd.Series, np.ndarray],
) -> Pipeline:
    """Build an imblearn Pipeline for the given model architecture and imbalance strategy.

    Args:
        model_name: One of {"logreg", "random_forest", "xgboost"}.
        imbalance: One of {"none", "class_weight", "smote"}.
        y_train: Training labels used only to compute scale_pos_weight for xgboost + class_weight.

    Returns:
        imblearn.pipeline.Pipeline containing "prep", optionally "smote", and "clf".
    """
    if model_name not in VALID_MODELS:
        raise ValueError(f"Unknown model_name '{model_name}'. Expected one of {VALID_MODELS}")
    if imbalance not in VALID_IMBALANCES:
        raise ValueError(f"Unknown imbalance '{imbalance}'. Expected one of {VALID_IMBALANCES}")

    # Compute scale_pos_weight for xgboost
    if isinstance(y_train, pd.Series):
        y_arr = y_train.values
    else:
        y_arr = np.asarray(y_train)

    n_positive = int((y_arr == 1).sum())
    n_negative = int((y_arr == 0).sum())

    if imbalance == "class_weight":
        scale_pos_weight = float(n_negative / n_positive) if n_positive > 0 else 1.0
    else:
        scale_pos_weight = 1

    steps = []

    # 1. "prep" step
    if model_name == "logreg":
        steps.append(("prep", LogregPreprocessor()))
    else:
        steps.append(("prep", "passthrough"))

    # 2. "smote" step (only when imbalance == "smote")
    if imbalance == "smote":
        steps.append(
            (
                "smote",
                SMOTE(
                    sampling_strategy=0.1,
                    k_neighbors=5,
                    random_state=config.RANDOM_SEED,
                ),
            )
        )

    # 3. "clf" step
    if model_name == "logreg":
        clf = LogisticRegression(
            max_iter=2000,
            C=1.0,
            random_state=config.RANDOM_SEED,
            class_weight="balanced" if imbalance == "class_weight" else None,
        )
    elif model_name == "random_forest":
        clf = RandomForestClassifier(
            n_estimators=200,
            max_depth=12,
            min_samples_leaf=2,
            n_jobs=-1,
            random_state=config.RANDOM_SEED,
            class_weight="balanced_subsample" if imbalance == "class_weight" else None,
        )
    elif model_name == "xgboost":
        clf = XGBClassifier(
            n_estimators=300,
            max_depth=6,
            learning_rate=0.1,
            subsample=0.8,
            colsample_bytree=0.8,
            tree_method="hist",
            eval_metric="aucpr",
            n_jobs=-1,
            random_state=config.RANDOM_SEED,
            scale_pos_weight=scale_pos_weight,
        )

    steps.append(("clf", clf))
    return Pipeline(steps)
