"""Frozen, local YAMNet feature comparator. No model training or audio upload."""
import argparse
import hashlib
import json
from pathlib import Path
import time

from ai_edge_litert.interpreter import Interpreter
import numpy as np

from audio_feature_study import decode, metrics, model

MODEL_SHA256 = "141fba1cdaae842c816f28edc4937e8b4f0af4c8df21862ccc6b52dc567993c3"
SOURCE_REVISION = "522a02b47444e6016d5a0d4d1388b522bc2529f2"
SOUND_CLASSES = ("Snoring", "Breathing", "Speech", "Creak", "Sigh", "Cough", "Silence", "Noise")


def evaluate_representation(records, vectors, nearest=False):
    indices = [i for i, r in enumerate(records) if not r["context_required"]]
    ids = [records[i]["id"] for i in indices]
    y = np.array([records[i]["sound_label"] == "SNORE" for i in indices], dtype=int)
    x = np.asarray(vectors)[indices]
    if nearest:
        x = x / np.maximum(np.linalg.norm(x, axis=1, keepdims=True), 1e-12)
    predictions, neighbors = [], []
    for i in range(len(x)):
        train = np.arange(len(x)) != i
        if nearest:
            locations = np.flatnonzero(train)
            j = locations[np.argmax(x[train] @ x[i])]
            predictions.append(int(y[j]))
            neighbors.append(ids[j])
        else:
            predictions.append(int(model().fit(x[train], y[train]).predict(x[i:i + 1])[0]))
    first = np.array([records[i]["annotation_round"] == 1 for i in indices])
    later_ids = [name for name, chosen in zip(ids, ~first) if chosen]
    if nearest:
        locations = np.flatnonzero(first)
        later_predictions = [int(y[locations[np.argmax(x[first] @ v)]]) for v in x[~first]]
    else:
        later_predictions = model().fit(x[first], y[first]).predict(x[~first])
    return dict(
        leave_one_clip_out=metrics(y, predictions, ids),
        round1_to_round2=metrics(y[~first], later_predictions, later_ids),
        predictions=dict(zip(ids, predictions)),
        nearest_clip=dict(zip(ids, neighbors)) if nearest else None,
    )


def evaluate_stress(records, original, changed):
    indices = [i for i, r in enumerate(records) if not r["context_required"]]
    ids = [records[i]["id"] for i in indices]
    y = np.array([records[i]["sound_label"] == "SNORE" for i in indices], dtype=int)
    original, changed = np.asarray(original)[indices], np.asarray(changed)[indices]
    base, predictions = [], []
    for i in range(len(indices)):
        train = np.arange(len(indices)) != i
        estimator = model().fit(original[train], y[train])
        base.append(int(estimator.predict(original[i:i + 1])[0]))
        predictions.append(int(estimator.predict(changed[i:i + 1])[0]))
    return dict(
        **metrics(y, predictions, ids),
        flipped_ids=[name for name, a, b in zip(ids, base, predictions) if a != b],
        predictions=dict(zip(ids, predictions)),
        training_view="original raw clips only; variants never enter training",
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    if hashlib.sha256(args.model.read_bytes()).hexdigest() != MODEL_SHA256:
        raise ValueError("Unexpected model bytes; do not silently change the comparator")
    out = args.output_dir.resolve()
    if out.is_relative_to(Path(__file__).resolve().parents[1]):
        parser.error("Private results must stay outside the repository")
    out.mkdir(parents=True, exist_ok=True)
    labels = args.labels.read_text(encoding="utf-8").splitlines()
    assert len(labels) == 521 and all(label in labels for label in SOUND_CLASSES)
    records = json.loads(args.annotations.read_text(encoding="utf-8"))["records"]
    assert len({r["source_sha256"] for r in records}) == len(records)
    classifier = Interpreter(model_path=str(args.model), num_threads=2)
    input_index = classifier.get_input_details()[0]["index"]
    selected = [labels.index(label) for label in SOUND_CLASSES]
    vectors, details = {}, []
    for record in records:
        original = decode(record).astype(np.float32)
        rms = max(float(np.sqrt(np.mean(original ** 2))), 1e-12)
        peak = max(float(abs(original).max()), 1e-12)
        gain = min(10 ** (24 / 20), .1 / rms, .95 / peak)
        gain_up = min(10 ** (6 / 20), .95 / peak)
        views = {
            "raw": (original, 1.),
            "rms_normalized": (original * gain, gain),
            "gain_minus_6db": (original * 10 ** (-6 / 20), 10 ** (-6 / 20)),
            "gain_plus_6db": (original * gain_up, gain_up),
            "trim_start_100ms": (original[1600:], 1.),
            "trim_end_100ms": (original[:-1600], 1.),
        }
        for view, (pcm, actual_gain) in views.items():
            started = time.perf_counter()
            classifier.resize_tensor_input(input_index, [len(pcm)], strict=True)
            classifier.allocate_tensors()
            classifier.set_tensor(input_index, pcm.astype(np.float32))
            classifier.invoke()
            tensors = [classifier.get_tensor(item["index"])
                       for item in classifier.get_output_details()]
            scores = next(value for value in tensors if value.shape[-1] == 521)
            embedding = next(value for value in tensors if value.shape[-1] == 1024)
            assert scores.ndim == embedding.ndim == 2
            assert scores.shape[0] == embedding.shape[0] and scores.shape[0] > 0
            assert np.isfinite(scores).all() and np.isfinite(embedding).all()
            pooled_scores = np.r_[scores[:, selected].mean(axis=0), scores[:, selected].max(axis=0)]
            vectors.setdefault(view + "_semantic_scores", []).append(pooled_scores)
            vectors.setdefault(view + "_embedding_mean", []).append(embedding.mean(axis=0))
            vectors.setdefault(view + "_embedding_max", []).append(embedding.max(axis=0))
            mean = scores.mean(axis=0)
            top = np.argsort(mean)[-5:][::-1]
            details.append(dict(
                id=record["id"], human_label=record["sound_label"], view=view,
                gain_db=float(20 * np.log10(actual_gain)),
                frames=len(scores), inference_seconds=time.perf_counter() - started,
                mean_scores={label: float(scores[:, labels.index(label)].mean()) for label in SOUND_CLASSES},
                max_scores={label: float(scores[:, labels.index(label)].max()) for label in SOUND_CLASSES},
                top5_mean=[dict(label=labels[i], score=float(mean[i])) for i in top],
                clinical_interpretation=None,
            ))
        print(f'Inferred {record["id"]}', flush=True)
    results = {
        name: evaluate_representation(records, values, nearest="embedding" in name)
        for name, values in vectors.items()
        if name.startswith(("raw_", "rms_normalized_"))
    }
    stress = {
        view: evaluate_stress(records, vectors["raw_semantic_scores"],
                              vectors[view + "_semantic_scores"])
        for view in views if view != "raw"
    }
    payload = dict(
        model_source_revision=SOURCE_REVISION, model_sha256=MODEL_SHA256,
        label_sha256=hashlib.sha256(args.labels.read_bytes()).hexdigest(),
        input_sample_rate=16000, semantic_classes=SOUND_CLASSES,
        embedding_estimator="L2-normalized cosine 1-nearest-clip; no fitted high-dimensional head",
        semantic_estimator="train-fold StandardScaler + balanced LogisticRegression C=0.1",
        results=results, stress=stress, details=details,
        script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        annotation_sha256=hashlib.sha256(args.annotations.read_bytes()).hexdigest(),
        limitations=[
            "Small selected one-night development set; no population accuracy claim.",
            "Round-2 labels already seen; transfer check is not blind.",
            "No clinical risk classifier. Snoring scores do not validate user health concern K.",
            "Short last model windows may include internal zero padding, not recovered audio.",
            "Desktop inference time is not Android power/latency measurement.",
            "This comparator was added after hand-crafted feature results were inspected.",
        ],
    )
    (out / "yamnet-results.json").write_text(json.dumps(payload, indent=2, allow_nan=False), encoding="utf-8")
    np.savez_compressed(
        out / "yamnet-vectors.npz",
        sample_ids=np.array([r["id"] for r in records]),
        source_sha256=np.array([r["source_sha256"] for r in records]),
        **{k: np.array(v) for k, v in vectors.items()},
    )
    for name, result in results.items():
        print(name, json.dumps(result["leave_one_clip_out"]))
    for view, result in stress.items():
        print("STRESS", view, json.dumps(result))


if __name__ == "__main__":
    main()
