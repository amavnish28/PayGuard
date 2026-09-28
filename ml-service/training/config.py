"""Configuration settings and filesystem paths for PayGuard ML model training."""

from pathlib import Path

# Base directories (all absolute, independent of current working directory)
ML_SERVICE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ML_SERVICE_ROOT.parent

# Data paths
DATA_PATH = ML_SERVICE_ROOT / "data" / "creditcard.csv"
SPLIT_DIR = ML_SERVICE_ROOT / "data" / "splits"

# Report and figure paths
REPORT_DIR = ML_SERVICE_ROOT / "reports"
FIG_DIR = REPORT_DIR / "figures"

# Random seed and split configuration
RANDOM_SEED = 42
TRAIN_FRAC, VAL_FRAC, TEST_FRAC = 0.70, 0.15, 0.15
DEDUPLICATE = True

# Specific artifact files
SPLIT_FILE = SPLIT_DIR / f"split_seed{RANDOM_SEED}.npz"
SPLIT_MANIFEST_PATH = REPORT_DIR / "split_manifest.json"
EDA_SUMMARY_PATH = REPORT_DIR / "eda_summary.json"


def ensure_output_dirs() -> None:
    """Create output directories for splits, reports, and figures if they do not exist."""
    SPLIT_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    FIG_DIR.mkdir(parents=True, exist_ok=True)
