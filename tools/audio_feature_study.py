"""Local exploratory feature study; not a deployed classifier or clinical model.

Run with uv and the versions in tools/audio-study-requirements.txt.
No audio, user labels, or trained model is written into the repository.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import time

import librosa
import numpy as np
from scipy import signal, stats
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

SR = 16000
NFFT = 512
HOP = 160
GROUPS = {
    "coarse_reference": ("coarse.",),
    "envelope": ("env.",),
    "spectral_shape": ("spec.",),
    "spectral_dynamics": ("dyn.",),
    "mfcc": ("mfcc.",),
    "harmonic_structure": ("harm.",),
    "temporal_spectral": ("env.", "spec.", "dyn."),
    "all_new": ("env.", "spec.", "dyn.", "mfcc.", "harm."),
    "foreground_relative": ("fg.",),
    "foreground_harmonic": ("fg.", "harm."),
}


def describe(out, prefix, values):
    values = np.asarray(values, dtype=float)
    values = values[np.isfinite(values)]
    if not len(values):
        values = np.zeros(1)
    out[prefix + ".median"] = float(np.median(values))
    out[prefix + ".iqr"] = float(np.quantile(values, .75) - np.quantile(values, .25))


def harmonic_features(y):
    """Short-time waveform periodicity (milliseconds), not respiratory intervals."""
    padded = np.pad(y, (0, max(0, 1024 - len(y))))
    frames = librosa.util.frame(padded, frame_length=1024, hop_length=320).T
    energy = np.mean(frames ** 2, axis=1)
    active = energy > max(float(energy.max()) * .01, 1e-18)
    strengths = np.full(len(frames), np.nan)
    # YIN resolves period-multiple ambiguity; a largest-ACF-peak frequency does not.
    frequencies = librosa.yin(padded, fmin=60, fmax=500, sr=SR,
                              frame_length=1024, hop_length=320, center=False)
    lo, hi = int(SR / 500), int(SR / 60)
    for index in np.flatnonzero(active):
        frame = frames[index]
        frame = frame - frame.mean()
        ac = signal.correlate(frame, frame, mode="full", method="fft")[len(frame) - 1:]
        squares = np.r_[0., np.cumsum(frame * frame)]
        lags = np.arange(lo - 1, hi + 2)
        denom = np.sqrt(np.maximum(
            squares[len(frame) - lags] * (squares[-1] - squares[lags]), 1e-24
        ))
        curve = ac[lags] / denom
        peaks, _ = signal.find_peaks(curve)
        peaks = peaks[(lags[peaks] >= lo) & (lags[peaks] <= hi)]
        if not len(peaks):
            strengths[index] = 0.
            continue
        best = peaks[np.argmax(curve[peaks])]
        strength = float(np.clip(curve[best], 0, 1))
        strengths[index] = strength
    out = {}
    describe(out, "harm.strength", strengths)
    valid = active & (strengths >= .45) & np.isfinite(frequencies)
    out["harm.voiced_fraction"] = float(valid.sum() / max(int(active.sum()), 1))
    describe(out, "harm.log_frequency", np.log2(frequencies[valid]))
    describe(out, "harm.frequency_step", adjacent_frequency_steps(frequencies, valid))
    return out


def adjacent_frequency_steps(frequencies, valid):
    """Inactive/invalid original frames remain barriers; never bridge quiet gaps."""
    adjacent = valid[1:] & valid[:-1]
    return np.abs(np.diff(np.log2(np.where(valid, frequencies, 1.))))[adjacent]


def extract_features(samples):
    y = np.asarray(samples, dtype=np.float64)
    if y.ndim != 1 or not len(y) or not np.all(np.isfinite(y)):
        raise ValueError("Expected nonempty finite mono PCM")
    y = y - y.mean()
    # Fixed within-clip normalization; never fit any preprocessing across recordings.
    peak = float(np.max(abs(y)))
    if peak > 1e-12:
        y = y / peak
    padded = np.pad(y, (0, max(0, NFFT - len(y))))
    frames = librosa.util.frame(padded, frame_length=NFFT, hop_length=HOP)
    rms = np.sqrt(np.mean(frames ** 2, axis=0))
    active = rms >= max(float(rms.max()) * .25, 1e-12)
    if not active.any():
        active[:] = True
    spectrum = abs(librosa.stft(padded, n_fft=NFFT, hop_length=HOP, center=False))
    power = spectrum ** 2
    hz = librosa.fft_frequencies(sr=SR, n_fft=NFFT)
    mask = (hz >= 80) & (hz <= 6000)
    selected = power[mask]
    selected_hz = hz[mask]
    totals = np.maximum(selected.sum(axis=0), 1e-18)
    norm = selected / totals
    aggregate = selected.sum(axis=1)
    aggregate /= max(float(aggregate.sum()), 1e-18)
    out = {}
    out["coarse.low"] = float(aggregate[selected_hz < 300].sum())
    out["coarse.mid"] = float(aggregate[(selected_hz >= 300) & (selected_hz < 1500)].sum())
    out["coarse.high"] = float(aggregate[selected_hz >= 1500].sum())
    out["coarse.centroid"] = float(aggregate @ selected_hz)
    out["coarse.duration"] = len(y) / SR
    out["coarse.envelope_cv"] = float(rms.std() / max(float(rms.mean()), 1e-12))
    out["coarse.crest_db"] = float(20 * np.log10(max(float(abs(y).max()), 1e-12) /
                                              max(float(np.sqrt(np.mean(y * y))), 1e-12)))
    zcr = np.mean(np.diff(np.signbit(frames), axis=0), axis=0)
    out["coarse.zcr_mean"] = float(zcr.mean())
    out["coarse.zcr_std"] = float(zcr.std())
    adjacent = active[1:] & active[:-1]
    flux = .5 * abs(np.diff(norm, axis=1)).sum(axis=0)
    out["coarse.flux"] = float(flux[adjacent].mean()) if adjacent.any() else 0.
    _spectral_features(out, spectrum, power, norm, hz, selected_hz, active, adjacent)
    _envelope_features(out, y, rms, active)
    out.update(harmonic_features(y))
    out.update(foreground_features(power, hz, len(y) / SR))
    if not all(np.isfinite(v) for v in out.values()):
        raise ValueError("Nonfinite acoustic features")
    return out


def foreground_features(power, hz, duration):
    """Within-recording spectral contrast, using only the early context as reference.

    The reference is not guaranteed silent; this is a testable contextual feature,
    not restoration of a clean source signal or recovered missing post-roll.
    """
    keep = (hz >= 80) & (hz <= 6000)
    p, frequencies = power[keep], hz[keep]
    prefix = max(1, min(p.shape[1], int(min(1.5, duration / 2) * SR / HOP)))
    floor = np.median(p[:, :prefix], axis=1)
    baseline = max(float(floor.sum()), 1e-18)
    excess = np.maximum(p - 2 * floor[:, None], 0)
    excess_energy = excess.sum(axis=0)
    contrast_db = 10 * np.log10(np.maximum(p.sum(axis=0), 1e-18) / baseline)
    foreground = contrast_db > 3
    # Keep feature dimension stable even if no foreground is measurable.
    chosen = foreground if foreground.any() else np.ones(p.shape[1], dtype=bool)
    norm = excess / np.maximum(excess_energy, 1e-18)
    out = {
        "fg.occupancy": float(foreground.mean()),
        "fg.excess_power_ratio": float(excess.sum() / max(float(p.sum()), 1e-18)),
        "fg.contrast_p90_db": float(np.quantile(contrast_db, .9)),
        "fg.contrast_p50_db": float(np.quantile(contrast_db, .5)),
        "fg.contrast_peak_db": float(contrast_db.max()),
        "fg.peak_median_excess": float(np.log1p(excess_energy.max() /
                                               max(float(np.median(excess_energy)), baseline * .01))),
    }
    aggregate = excess[:, chosen].sum(axis=1)
    aggregate /= max(float(aggregate.sum()), 1e-18)
    for low, high in ((80, 200), (200, 400), (400, 800),
                      (800, 1600), (1600, 3200), (3200, 6001)):
        out[f"fg.band_{low}_{high}"] = float(
            aggregate[(frequencies >= low) & (frequencies < high)].sum()
        )
    centroid = (norm * frequencies[:, None]).sum(axis=0)
    entropy = -(norm * np.log(np.maximum(norm, 1e-18))).sum(axis=0) / np.log(len(frequencies))
    describe(out, "fg.centroid", centroid[chosen])
    describe(out, "fg.entropy", entropy[chosen])
    adjacent = chosen[1:] & chosen[:-1]
    describe(out, "fg.flux", (.5 * abs(np.diff(norm, axis=1)).sum(axis=0))[adjacent])
    # Distinguish a brief broad impulse from a sustained foreground component.
    runs = np.diff(np.r_[False, foreground, False].astype(int))
    starts, ends = np.flatnonzero(runs == 1), np.flatnonzero(runs == -1)
    lengths = ends - starts
    out["fg.longest_run_fraction"] = float(lengths.max() / len(foreground)) if len(lengths) else 0.
    out["fg.run_density"] = len(lengths) / max(duration, .01)
    return out


def _spectral_features(out, spectrum, power, norm, hz, selected_hz, active, adjacent):
    band_edges = (80, 200, 400, 800, 1600, 3200, 6001)
    for low, high in zip(band_edges, band_edges[1:]):
        values = norm[(selected_hz >= low) & (selected_hz < high)].sum(axis=0)
        describe(out, f"spec.band_{low}_{high}", values[active])
    centroid = (norm * selected_hz[:, None]).sum(axis=0)
    bandwidth = np.sqrt((norm * (selected_hz[:, None] - centroid) ** 2).sum(axis=0))
    flatness = np.exp(np.log(np.maximum(norm, 1e-18)).mean(axis=0)) * len(selected_hz)
    entropy = -(norm * np.log(np.maximum(norm, 1e-18))).sum(axis=0) / np.log(len(selected_hz))
    rolloff = selected_hz[np.argmax(np.cumsum(norm, axis=0) >= .85, axis=0)]
    for name, values in (("centroid", centroid), ("bandwidth", bandwidth),
                         ("flatness", flatness), ("entropy", entropy), ("rolloff", rolloff)):
        describe(out, "spec." + name, values[active])
    describe(out, "dyn.flux", .5 * abs(np.diff(norm, axis=1)).sum(axis=0)[adjacent])
    describe(out, "dyn.centroid_step",
             np.abs(np.diff(np.log1p(centroid)))[adjacent])
    for lag in (1, 3, 10):
        a, b = norm[:, lag:], norm[:, :-lag]
        valid = active[lag:] & active[:-lag]
        similarity = (a * b).sum(axis=0) / np.maximum(
            np.linalg.norm(a, axis=0) * np.linalg.norm(b, axis=0), 1e-18)
        describe(out, f"dyn.cosine_lag_{lag}", similarity[valid])
    mel = librosa.feature.melspectrogram(
        S=power, sr=SR, n_fft=NFFT, n_mels=40, fmin=60, fmax=6000
    )
    logmel = librosa.power_to_db(mel, ref=np.max, top_db=80)
    mfcc = librosa.feature.mfcc(S=logmel, n_mfcc=13)[1:]
    # Exclude coefficient zero (overall log energy); preserve within-frame shape.
    for i, values in enumerate(mfcc, 1):
        out[f"mfcc.c{i}.mean"] = float(values[active].mean())
        out[f"mfcc.c{i}.std"] = float(values[active].std())
        out[f"mfcc.c{i}.step"] = (
            float(abs(np.diff(values))[adjacent].mean()) if adjacent.any() else 0.
        )


def _envelope_features(out, y, rms, active):
    mean = max(float(rms.mean()), 1e-12)
    q10, q50, q90 = np.quantile(rms, (.1, .5, .9))
    span = max(float(rms.max() - rms.min()), 1e-12)
    out["env.cv"] = float(rms.std() / mean)
    out["env.active_fraction"] = float(active.mean())
    out["env.crest_db"] = out["coarse.crest_db"]
    out["env.p90_p10_db"] = float(20 * np.log10(max(q90, 1e-12) / max(q10, 1e-12)))
    out["env.median_peak_ratio"] = float(q50 / max(float(rms.max()), 1e-12))
    out["env.kurtosis"] = float(stats.kurtosis(rms, fisher=False)) if rms.std() > 1e-10 else 0.
    out["env.wave_kurtosis"] = float(stats.kurtosis(y, fisher=False)) if y.std() > 1e-10 else 0.
    deltas = np.diff(rms) / span
    out["env.rise_p95"] = float(np.quantile(np.maximum(deltas, 0), .95)) if len(deltas) else 0.
    out["env.fall_p95"] = float(np.quantile(np.maximum(-deltas, 0), .95)) if len(deltas) else 0.
    peaks, properties = signal.find_peaks(
        rms, prominence=.25 * span, distance=max(1, int(.08 * SR / HOP))
    )
    out["env.peaks_per_second"] = len(peaks) / max(len(y) / SR, .01)
    out["env.prominence_mean"] = (
        float(properties["prominences"].mean() / span) if len(peaks) else 0.
    )
    # The carrier-band envelope captures faster rattle/flutter than breathing cycles.
    sos = signal.butter(3, (80, 2500), fs=SR, btype="bandpass", output="sos")
    carrier = signal.sosfilt(sos, y)
    envelope = abs(signal.hilbert(carrier))
    envelope = signal.resample_poly(envelope, 1, 80)  # 200 Hz envelope.
    envelope -= envelope.mean()
    modulation = abs(np.fft.rfft(envelope * np.hanning(len(envelope)))) ** 2
    frequencies = np.fft.rfftfreq(len(envelope), 1 / 200)
    total = max(float(modulation[(frequencies >= .5) & (frequencies < 90)].sum()), 1e-18)
    for low, high in ((.5, 3), (3, 8), (8, 20), (20, 50), (50, 90)):
        out[f"env.mod_{low}_{high}"] = float(
            modulation[(frequencies >= low) & (frequencies < high)].sum() / total
        )


def model():
    # Fixed before inspecting scores. Fit the scaler only on each training fold.
    return make_pipeline(StandardScaler(), LogisticRegression(
        C=.1, class_weight="balanced", solver="liblinear", max_iter=1000, random_state=17
    ))


def metrics(truth, predictions, ids):
    truth, predictions = np.asarray(truth), np.asarray(predictions)
    tp = int(np.sum((truth == 1) & (predictions == 1)))
    tn = int(np.sum((truth == 0) & (predictions == 0)))
    positives, negatives = int(truth.sum()), int(len(truth) - truth.sum())
    return dict(correct=tp + tn, total=len(truth), snore_detected=tp,
                snore_total=positives, nonsnore_correct=tn, nonsnore_total=negatives,
                errors=[ids[i] for i in range(len(ids)) if truth[i] != predictions[i]])


def evaluate(records, matrices, names):
    eligible = [i for i, r in enumerate(records) if not r["context_required"]]
    ids = [records[i]["id"] for i in eligible]
    truth = np.array([records[i]["sound_label"] == "SNORE" for i in eligible], dtype=int)
    first = np.array([records[i]["annotation_round"] == 1 for i in eligible])
    results = {}
    for group, prefixes in GROUPS.items():
        columns = [i for i, name in enumerate(names) if name.startswith(prefixes)]
        x = matrices["full"][eligible][:, columns]
        predictions, scores, perturbed = [], [], {key: [] for key in matrices if key != "full"}
        for test in range(len(eligible)):
            train = np.arange(len(eligible)) != test
            estimator = model().fit(x[train], truth[train])
            predictions.append(int(estimator.predict(x[test:test + 1])[0]))
            scores.append(float(estimator.predict_proba(x[test:test + 1])[0, 1]))
            for key in perturbed:
                xtest = matrices[key][eligible[test], columns].reshape(1, -1)
                perturbed[key].append(int(estimator.predict(xtest)[0]))
        transfer = model().fit(x[first], truth[first])
        later = transfer.predict(x[~first])
        results[group] = dict(
            feature_count=len(columns),
            leave_one_clip_out=metrics(truth, predictions, ids),
            predictions=dict(zip(ids, predictions)), uncalibrated_scores=dict(zip(ids, scores)),
            round1_to_round2=metrics(truth[~first], later,
                                     [name for name, selected in zip(ids, ~first) if selected]),
            perturbations={key: dict(
                **metrics(truth, values, ids),
                flipped_ids=[name for name, original, changed in zip(ids, predictions, values)
                             if original != changed]
            ) for key, values in perturbed.items()},
        )
    return results


def decode(record):
    path = Path(record["original_path"])
    if hashlib.sha256(path.read_bytes()).hexdigest() != record["source_sha256"]:
        raise ValueError(f"Original audio changed: {record['id']}")
    result = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-t", "15", "-ac", "1",
         "-ar", str(SR), "-f", "f32le", "pipe:1"],
        capture_output=True, check=True, timeout=30,
    )
    samples = np.frombuffer(result.stdout, dtype="<f4").astype(float)
    if not len(samples) or not np.all(np.isfinite(samples)):
        raise ValueError("Invalid decoded PCM")
    return samples


def plots(records, waves, rows, names, results, out):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, axes = plt.subplots(3, 4, figsize=(15, 9), constrained_layout=True)
    for record, y, ax in zip(records, waves, axes.flat):
        spec = abs(librosa.stft(y, n_fft=512, hop_length=160)) ** 2
        db = librosa.power_to_db(spec, ref=np.max, top_db=65)
        ax.imshow(db[:129], origin="lower", aspect="auto", cmap="magma",
                  extent=(0, len(y) / SR, 0, 4000), vmin=-65, vmax=0)
        suffix = " / review flag" if record["review_flags"] else ""
        ax.set_title(f'{record["id"]}: {record["sound_label"]}{suffix}', fontsize=10)
        ax.set_xlabel("Time (s)")
        ax.set_ylabel("Frequency (Hz)")
    fig.suptitle("Original clips: per-clip relative power (not comparable loudness)", fontsize=14)
    fig.savefig(out / "labeled-spectrograms.png", dpi=130)
    plt.close(fig)

    fig, axes = plt.subplots(2, 3, figsize=(13, 7), constrained_layout=True)
    chosen = ["env.crest_db", "env.mod_20_50", "dyn.flux.median",
              "harm.strength.median", "harm.voiced_fraction", "spec.entropy.median"]
    colors = {"SNORE": "#00876c", "ENV_NOISE": "#686868", "SPEECH": "#1865b6",
              "BED_MOVEMENT": "#b25e09", "ABNORMAL": "#bb3648", "SIGH": "#774fa0"}
    for ax, feature in zip(axes.flat, chosen):
        values = [row[feature] for row in rows]
        for i, (record, value) in enumerate(zip(records, values)):
            ax.scatter(i, value, color=colors[record["sound_label"]], s=45)
            ax.annotate(record["id"], (i, value), xytext=(0, 5),
                        textcoords="offset points", ha="center")
        ax.set_xticks([])
        ax.set_title(feature, fontsize=11)
        ax.grid(axis="y", alpha=.2)
        ax.margins(y=.2)
    fig.suptitle("Exploratory measurements; green = user-labeled snore", fontsize=13)
    fig.savefig(out / "feature-comparison.png", dpi=130)
    plt.close(fig)


def self_test():
    t = np.arange(SR) / SR
    tone = .15 * np.sin(2 * np.pi * 200 * t)
    repeated = (.2 + .8 * np.sin(2 * np.pi * 2 * t) ** 2) * tone
    for y in (np.zeros(200), np.zeros(SR), tone, repeated):
        f = extract_features(y)
        assert len(f) > 70 and all(np.isfinite(v) for v in f.values())
    a, b = extract_features(repeated), extract_features(repeated * .1)
    for name in a:
        assert np.isclose(a[name], b[name], rtol=1e-5, atol=1e-6), name
    assert extract_features(tone)["harm.strength.median"] > .9
    for frequency in (100, 200, 300):
        f = harmonic_features(.15 * np.sin(2 * np.pi * frequency * t))
        assert abs(2 ** f["harm.log_frequency.median"] - frequency) < 3, f
        assert f["harm.frequency_step.median"] < .005, f
    assert len(adjacent_frequency_steps(np.array([100., np.nan, 120.]),
                                        np.array([True, False, True]))) == 0
    rng = np.random.default_rng(5)
    noise = extract_features(rng.normal(0, .1, SR))
    assert noise["harm.strength.median"] < .3
    assert len({name for prefixes in GROUPS.values() for name in a if name.startswith(prefixes)}) == len(a)
    estimator = model().fit(np.array([[0.], [1.], [2.], [3.]]), [0, 0, 1, 1])
    mean_before = estimator[0].mean_.copy()
    estimator.predict(np.array([[10000.]]))
    assert np.array_equal(estimator[0].mean_, mean_before)
    print("Self-test passed: silence/short PCM, finite features, gain invariance, "
          "tone vs noise harmonicity, feature groups and train-only preprocessing.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path)
    parser.add_argument("--clips-csv", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not all((args.annotations, args.clips_csv, args.output_dir)):
        parser.error("--annotations, --clips-csv and --output-dir are required")
    repo = Path(__file__).resolve().parents[1]
    out = args.output_dir.resolve()
    if out.is_relative_to(repo):
        parser.error("Private results must be outside the repository")
    out.mkdir(parents=True, exist_ok=True)
    records = json.loads(args.annotations.read_text(encoding="utf-8"))["records"]
    if len({r["source_sha256"] for r in records}) != len(records):
        raise ValueError("Duplicate source audio would leak across folds")
    with args.clips_csv.open(encoding="utf-8", newline="") as f:
        metadata = {r["member"]: r for r in csv.DictReader(f)}
    feature_rows = {key: [] for key in (
        "full", "gain_minus_12db", "trim_start_100ms", "trim_end_100ms", "estimated_tail"
    )}
    waves, timings = [], []
    for record in records:
        y = decode(record)
        waves.append(y)
        duration = float(metadata[record["source_member"]]["event_duration_ms"]) / 1000
        count = min(len(y), max(1, round(duration * SR)))
        variants = {
            "full": y, "gain_minus_12db": y * 10 ** (-12 / 20),
            "trim_start_100ms": y[min(1600, len(y) // 4):],
            "trim_end_100ms": y[:-min(1600, len(y) // 4)],
            "estimated_tail": y[-count:],
        }
        for view, data in variants.items():
            started = time.perf_counter()
            feature_rows[view].append(extract_features(data))
            if view == "full":
                timings.append(time.perf_counter() - started)
        print(f'Extracted {record["id"]} ({len(y) / SR:.2f}s)', flush=True)
    names = sorted(feature_rows["full"][0])
    matrices = {key: np.array([[row[name] for name in names] for row in rows])
                for key, rows in feature_rows.items()}
    results = evaluate(records, matrices, names)
    payload = dict(
        study_version="feature-study-v4-yin", model="StandardScaler + balanced LogisticRegression C=0.1",
        sample_ids=[r["id"] for r in records], excluded_context_uncertain=["C"],
        independent_clips=11, binary_positive="SNORE", model_features=names,
        groups=GROUPS, results=results, full_extraction_seconds=timings,
        annotation_sha256=hashlib.sha256(args.annotations.read_bytes()).hexdigest(),
        script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        limitations=[
            "All recordings are selected from one night/device; not a population test.",
            "Round-2 labels were previously seen. Transfer check is not blind validation.",
            "Feature-family comparison is exploratory; no hyperparameter search performed.",
            "Foreground groups were added after reviewing v1 results and spectrograms, not a blind test.",
            "v1/v2 harmonic groups had ACF frequency ambiguity and gap-adjacency bugs; superseded by YIN.",
            "C is not used as a definite positive or negative.",
            "K is only a snore label; clinical review concern is not a training target.",
            "Estimated tails do not have human event boundaries; sensitivity only.",
            "Scores are not calibrated probabilities. No model is exported to the app.",
        ],
    )
    (out / "results.json").write_text(json.dumps(payload, indent=2, allow_nan=False), encoding="utf-8")
    with (out / "features.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["id", "sound_label", "view"] + names)
        writer.writeheader()
        for view, rows in feature_rows.items():
            for record, row in zip(records, rows):
                writer.writerow(dict(id=record["id"], sound_label=record["sound_label"],
                                     view=view, **row))
    plots(records, waves, feature_rows["full"], names, results, out)
    for group, result in results.items():
        print(group, json.dumps(result["leave_one_clip_out"]), flush=True)
    print(f"Saved exploratory results: {out}")


if __name__ == "__main__":
    main()
