import json
from pathlib import Path


def test_feature_schema_contract():
    # Resolve contracts/feature_schema_v1.json relative to this test file
    test_file_path = Path(__file__).resolve()
    schema_path = test_file_path.parents[2] / "contracts" / "feature_schema_v1.json"
    assert schema_path.exists(), f"Schema file not found at {schema_path}"

    with open(schema_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    assert "features" in data
    features = data["features"]

    # exactly 11 features
    assert len(features) == 11, f"Expected exactly 11 features, got {len(features)}"

    # names are unique
    feature_names = [f["name"] for f in features]
    assert len(feature_names) == len(set(feature_names)), "Feature names must be unique"

    # every feature has name, type, and source
    for f in features:
        assert "name" in f and f["name"], f"Feature missing name: {f}"
        assert "type" in f and f["type"], f"Feature missing type: {f}"
        assert "source" in f and f["source"], f"Feature missing source: {f}"

    # types are only "int" or "float"
    valid_types = {"int", "float"}
    for f in features:
        assert f["type"] in valid_types, f"Invalid type '{f['type']}' in feature {f['name']}"

    # the canonical order is:
    canonical_order = [
        "amount",
        "transactions_last_2_min",
        "transactions_last_1_hour",
        "account_avg_amount",
        "amount_ratio",
        "time_since_previous_seconds",
        "is_first_transaction",
        "new_device",
        "new_location",
        "transaction_hour",
        "odd_hour"
    ]
    assert feature_names == canonical_order, (
        f"Feature order mismatch.\nExpected: {canonical_order}\nGot: {feature_names}"
    )
