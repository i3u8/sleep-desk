"""Evaluate nuisance augmentation without sharing any source clip across folds."""
import argparse
import json
from pathlib import Path

import numpy as np

from audio_feature_study import metrics, model

VIEWS = ("raw", "gain_minus_6db", "gain_plus_6db", "trim_start_100ms", "trim_end_100ms")


def train_augmented(records, vectors, train, held_out):
    train_sources = {records[i]["source_sha256"] for i in train}
    test_sources = {records[i]["source_sha256"] for i in held_out}
    assert not train_sources.intersection(test_sources), "Source recording leakage"
    x = np.concatenate([vectors[view][train] for view in VIEWS])
    labels = np.array([records[i]["sound_label"] == "SNORE" for i in train], dtype=int)
    y = np.tile(labels, len(VIEWS))
    # Augmentation weights sum to one per source, before balanced class weights.
    return model().fit(x, y, logisticregression__sample_weight=np.full(len(y), 1 / len(VIEWS)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    out = args.output_dir.resolve()
    if out.is_relative_to(Path(__file__).resolve().parents[1]):
        parser.error("Keep private evaluation output outside the repository")
    out.mkdir(parents=True, exist_ok=True)
    records = json.loads(args.annotations.read_text(encoding="utf-8"))["records"]
    assert len({r["source_sha256"] for r in records}) == len(records)
    with np.load(args.vectors) as loaded:
        assert loaded["sample_ids"].tolist() == [r["id"] for r in records], "Sample order changed"
        assert loaded["source_sha256"].tolist() == [r["source_sha256"] for r in records], "Source changed"
        vectors = {view: loaded[view + "_semantic_scores"] for view in VIEWS}
    eligible = np.array([i for i, r in enumerate(records) if not r["context_required"]])
    ids = [records[i]["id"] for i in eligible]
    y = np.array([records[i]["sound_label"] == "SNORE" for i in eligible], dtype=int)
    predictions = {view: [] for view in VIEWS}
    held_out_concerns = []
    for i in eligible:
        estimator = train_augmented(records, vectors, eligible[eligible != i], [i])
        for view in VIEWS:
            predictions[view].append(int(estimator.predict(vectors[view][i:i + 1])[0]))
    first = np.array([i for i in eligible if records[i]["annotation_round"] == 1])
    second = np.array([i for i in eligible if records[i]["annotation_round"] == 2])
    estimator = train_augmented(records, vectors, first, second)
    second_y = np.array([records[i]["sound_label"] == "SNORE" for i in second], dtype=int)
    all_models = train_augmented(records, vectors, eligible, [
        i for i, record in enumerate(records) if record["context_required"]
    ])
    for i, record in enumerate(records):
        if record["context_required"]:
            held_out_concerns.append(dict(
                id=record["id"], known_label=record["sound_label"],
                excluded_from_training_and_metrics=True,
                predictions={view: int(all_models.predict(vectors[view][i:i + 1])[0]) for view in VIEWS},
            ))
    view_results = {
        view: dict(**metrics(y, values, ids),
                   predictions=dict(zip(ids, values)),
                   flips_from_raw=[name for name, a, b in zip(ids, predictions["raw"], values) if a != b])
        for view, values in predictions.items()
    }
    matrix = np.array([predictions[view] for view in VIEWS])
    result = dict(
        model="16 semantic features; StandardScaler; balanced logistic regression C=0.1",
        source_count=len(eligible), training_views=VIEWS, augmentation_weight_per_source=1,
        class_weight="balanced; applied in addition to augmentation weights",
        group_key="original audio SHA-256, not slices or filename",
        views=view_results,
        all_views_correct_ids=[ids[j] for j in range(len(ids)) if np.all(matrix[:, j] == y[j])],
        varying_prediction_ids=[ids[j] for j in range(len(ids)) if len(set(matrix[:, j])) > 1],
        round1_to_round2={
            view: metrics(second_y, estimator.predict(vectors[view][second]),
                          [records[i]["id"] for i in second])
            for view in VIEWS
        },
        context_uncertain=held_out_concerns,
        limitations=[
            "Augmentation strategy chosen after first stress test; exploratory, not final test accuracy.",
            "One night, few selected labels and singleton non-snore subtypes.",
            "All augmented copies of a held-out original are absent from training.",
            "No clinical-risk target; K retains human review concern only.",
        ],
    )
    (out / "grouped-augmentation.json").write_text(
        json.dumps(result, indent=2, allow_nan=False), encoding="utf-8"
    )
    for view, values in view_results.items():
        print(view, json.dumps(values))
    print("Stable correct originals across five views:", result["all_views_correct_ids"])
    print("Context-only sample (not scored):", result["context_uncertain"])


if __name__ == "__main__":
    main()
