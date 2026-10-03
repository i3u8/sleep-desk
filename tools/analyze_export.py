#!/usr/bin/env python3
"""Local-only export audit. Run with uv run --python 3.12 --with numpy."""

import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import zipfile

import numpy as np

SR = 16000
FFT = 512
AUDIO_TYPES = {"SNORE", "COUGH", "SPEECH", "NIGHT_WAKE_SOUND",
               "ENV_NOISE", "ABNORMAL", "FALSE_TRIGGER"}


def summary(values):
    a = np.asarray([v for v in values if v is not None], dtype=float)
    if not a.size:
        return {"n": 0}
    return dict(n=int(a.size), min=float(a.min()), p10=float(np.quantile(a, .1)),
                median=float(np.median(a)), p90=float(np.quantile(a, .9)),
                max=float(a.max()), mean=float(a.mean()))


def member_path(relative):
    if not relative:
        return None
    p = PurePosixPath(relative)
    if p.is_absolute() or ".." in p.parts or "\\" in relative:
        raise ValueError("Unsafe clip reference")
    return "clips/" + relative.removeprefix("audio_clips/")


def duration(event):
    return event["endMs"] - event["timeMs"]


def spectrum(x, midpoint=False):
    if midpoint:
        starts = [max(0, len(x) // 2 - FFT // 2)]
    else:
        starts = list(range(0, max(1, len(x) - FFT + 1), FFT // 2))
        starts = sorted(set(starts + [max(0, len(x) - FFT)]))
    frames = np.zeros((len(starts), FFT))
    for i, start in enumerate(starts):
        part = x[start:start + FFT]
        frames[i, :len(part)] = part
    powers = abs(np.fft.rfft(frames * np.hanning(FFT), axis=1)) ** 2
    hz = np.fft.rfftfreq(FFT, 1 / SR)
    masks = [(hz >= 80) & (hz <= 300),
             (hz > 300) & (hz <= 1500),
             (hz > 1500) & (hz <= 6000)]
    active = (hz >= 80) & (hz <= 6000)
    power = powers.mean(axis=0)
    total = float(power[active].sum())
    bands = [float(power[m].sum() / max(total, 1e-12)) for m in masks]
    p = power[active]
    flatness = float(np.exp(np.log(np.maximum(p, 1e-20)).mean()) /
                     max(float(p.mean()), 1e-20)) if total > 1e-12 else None
    norm = powers[:, active] / np.maximum(
        powers[:, active].sum(axis=1, keepdims=True), 1e-20)
    return dict(low=bands[0], mid=bands[1], high=bands[2],
                centroid_hz=float((power[active] * hz[active]).sum() /
                                  max(total, 1e-12)),
                flatness=flatness, windows=len(starts),
                spectral_l1_variation=float(abs(norm - norm.mean(axis=0)).sum(axis=1).mean()))


def acoustic(x):
    if not len(x):
        raise ValueError("Empty PCM")
    env = np.array([np.sqrt(np.mean(x[i:i + 160] ** 2))
                    for i in range(0, len(x), 160)])
    rms = float(np.sqrt(np.mean(x ** 2)))
    db = 20 * np.log10(np.maximum(env, 1e-9))
    centered = env - env.mean()
    energy = float(centered @ centered)
    # Require at least two cycles; avoid interpreting a short tail as periodic.
    lags = range(30, min(300, len(env) // 2) + 1)
    scores = [(lag, float(centered[:-lag] @ centered[lag:] / energy))
              for lag in lags] if energy > 1e-16 else []
    best = max(scores, key=lambda v: v[1]) if scores else None
    return dict(duration_s=len(x) / SR,
                rms_dbfs=20 * math.log10(max(rms, 1e-9)),
                peak_dbfs=20 * math.log10(max(float(abs(x).max()), 1e-9)),
                crest_db=20 * math.log10(max(float(abs(x).max()), 1e-9) / max(rms, 1e-9)),
                near_full_scale_fraction=float(np.mean(abs(x) >= .999)),
                zcr=float(np.mean((x[1:] >= 0) != (x[:-1] >= 0))) if len(x) > 1 else 0.,
                envelope_cv=float(env.std() / max(float(env.mean()), 1e-9)),
                envelope_db_p90_p10=float(np.quantile(db, .9) - np.quantile(db, .1)),
                period_s=best[0] / 100 if best else None,
                periodicity=max(0., best[1]) if best else None,
                period_at_search_boundary=bool(best and best[0] in (30, min(300, len(env) // 2))),
                **spectrum(x))


def band_distance(a, b):
    return sum(abs(a[k] - b[k]) for k in ("low", "mid", "high"))


def union_ms(intervals):
    total, right = 0, None
    for a, b in sorted(intervals):
        if b <= a:
            continue
        total += b - max(a, right if right is not None else a) if right is None or b > right else 0
        right = max(b, right if right is not None else b)
    return total


def read_events(session):
    events = session.get("events", session.get("audioEvents", []))
    seen = set()
    for e in events:
        if e["id"] in seen:
            raise ValueError("Duplicate event ID in session")
        seen.add(e["id"])
    return events


def metadata(data, members):
    rows, pairs, sessions = [], [], []
    for s in data["sessions"]:
        events = read_events(s)
        audio = sorted((e for e in events if e["type"] in AUDIO_TYPES),
                       key=lambda e: (e["timeMs"], e["id"]))
        screens = sorted((e for e in events if e["type"].startswith("SCREEN_")),
                         key=lambda e: e["timeMs"])
        screen_time = np.array([e["timeMs"] for e in screens], dtype=np.int64)
        for e in events:
            ref = member_path(e.get("clipRelativePath"))
            past_screens = [v for v in screens if v["timeMs"] <= e["endMs"]]
            last_screen = past_screens[-1] if past_screens else None
            near_screen = (last_screen["type"] == "SCREEN_ON" or
                           e["endMs"] - last_screen["timeMs"] < 30000) if last_screen else None
            rows.append(dict(session_id=s["id"], event_id=e["id"], type=e["type"],
                             time_ms=e["timeMs"], end_ms=e["endMs"],
                             duration_ms=duration(e), peak_level=e.get("peakLevel"),
                             confidence=e.get("confidence"), member=ref,
                             clip_present=ref in members, is_audio=e["type"] in AUDIO_TYPES,
                             screen_context_near=near_screen,
                             screen_context_known=last_screen is not None,
                             screen_change_within_30s=bool(len(screen_time) and
                                 np.min(abs(screen_time - e["timeMs"])) <= 30000)))
        # Consecutive audio events, not all combinatorial pairs.
        for left, right in zip(audio, audio[1:]):
            delta = right["timeMs"] - left["timeMs"]
            lp, rp = left.get("peakLevel"), right.get("peakLevel")
            if delta <= 2000 and lp is not None and rp is not None and abs(lp - rp) <= 1e-6:
                pairs.append(dict(session_id=s["id"], left_id=left["id"], right_id=right["id"],
                                  delta_ms=delta, peak_level=lp,
                                  same_type=left["type"] == right["type"]))
        end = s.get("endMs")
        observed_on = []
        state, previous = None, s["startMs"]
        unknown_ms = 0
        for e in screens:
            t = min(max(e["timeMs"], s["startMs"]), end) if end is not None else e["timeMs"]
            if state == "SCREEN_ON":
                observed_on.append((previous, t))
            elif state is None:
                unknown_ms += max(0, t - previous)
            state, previous = e["type"], t
        if end is not None:
            if state == "SCREEN_ON":
                observed_on.append((previous, end))
            elif state is None:
                unknown_ms += max(0, end - previous)
        sessions.append(dict(id=s["id"], start_ms=s["startMs"], end_ms=end,
                             duration_ms=end - s["startMs"] if end is not None else None,
                             events=len(events), audio_events=len(audio),
                             segments=len(s.get("segments", [])),
                             aliases_equal=s.get("events") == s.get("audioEvents"),
                             screen_events=screens,
                             observed_screen_on_ms=union_ms(observed_on),
                             unknown_initial_screen_ms=unknown_ms,
                             audio_start_deltas_ms=summary(
                                 [b["timeMs"] - a["timeMs"] for a, b in zip(audio, audio[1:])])))
    return rows, pairs, sessions


def decode(z, info, ffmpeg):
    if info.file_size > 64 * 1024 * 1024:
        raise ValueError("Clip exceeds 64 MiB safety limit")
    encoded = z.read(info)
    result = subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
         "-protocol_whitelist", "pipe", "-f", "mov", "-i", "pipe:0",
         "-map", "0:a:0", "-vn", "-t", "60", "-ac", "1", "-ar", str(SR),
         "-f", "f32le", "pipe:1"],
        input=encoded, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace")[-2000:])
    x = np.frombuffer(result.stdout, dtype="<f4").astype(np.float64)
    if not len(x) or not np.isfinite(x).all() or len(x) >= 60 * SR:
        raise ValueError("Empty, nonfinite, or safety-truncated PCM")
    return x, hashlib.sha256(encoded).hexdigest()


def analyze_clip(x, event):
    full, old = acoustic(x), spectrum(x, midpoint=True)
    result = dict(full=full, old_midpoint=old)
    if event is None or event["duration_ms"] <= 0:
        result["candidate_unavailable"] = True
        return result
    seconds = event["duration_ms"] / 1000
    n = min(len(x), max(1, round(seconds * SR)))
    start = len(x) - n
    candidate = acoustic(x[start:])
    result.update(candidate_tail=candidate, candidate_start_s=start / SR,
                  candidate_truncated=seconds > len(x) / SR,
                  old_midpoint_before_candidate=(len(x) // 2) < start,
                  old_window_entirely_before_candidate=(len(x) // 2 + FFT // 2) <= start,
                  old_vs_full_band_l1=band_distance(old, full),
                  full_vs_candidate_band_l1=band_distance(full, candidate),
                  old_vs_candidate_band_l1=band_distance(old, candidate),
                  old_vs_candidate_centroid_delta_hz=candidate["centroid_hz"] - old["centroid_hz"],
                  candidate_minus_full_rms_db=candidate["rms_dbfs"] - full["rms_dbfs"])
    # Codec padding and wall-clock/sample-clock mismatch: report sensitivity.
    sensitivity = []
    for shift_ms in (-100, -50, 50, 100):
        a = max(0, min(len(x) - 1, start + round(shift_ms * SR / 1000)))
        sensitivity.append(band_distance(candidate, spectrum(x[a:])))
    result["candidate_boundary_sensitivity_max_band_l1"] = max(sensitivity)
    # Exploratory alternative only: old exports have no sample offsets.
    if len(x) > 2 * SR:
        pre2 = acoustic(x[2 * SR:])
        result.update(candidate_pre2s=pre2,
                      old_vs_pre2s_band_l1=band_distance(old, pre2),
                      tail_vs_pre2s_band_l1=band_distance(candidate, pre2))
    return result


def aggregate(data, rows, pairs, sessions, clips, errors, members):
    audio = [r for r in rows if r["is_audio"]]
    durations = [r["duration_ms"] for r in audio]
    compared = [c for c in clips if "candidate_tail" in c]
    coverage = {}
    for label in sorted({r["type"] for r in audio}):
        subset = [r for r in audio if r["type"] == label]
        coverage[label] = dict(events=len(subset),
                               references=sum(bool(r["member"]) for r in subset),
                               present=sum(r["clip_present"] for r in subset),
                               duration_lt150=sum(r["duration_ms"] < 150 for r in subset))
    referenced = {r["member"] for r in rows if r["member"]}
    representatives = {member_path(p) for s in data["sessions"]
                       for seg in s.get("segments", [])
                       for p in seg.get("representativeClipPaths", [])}
    by_scope = {}
    for scope in ("full", "candidate_tail", "candidate_pre2s"):
        features = [c[scope] for c in clips if scope in c]
        by_scope[scope] = {
            "distributions": {k: summary([f[k] for f in features]) for k in
                              ("duration_s", "rms_dbfs", "peak_dbfs", "envelope_cv",
                               "envelope_db_p90_p10", "flatness", "centroid_hz",
                               "periodicity", "period_s", "spectral_l1_variation")},
            "stable_envelope_cv_le_025": sum(f["envelope_cv"] <= .25 for f in features),
            "period_eligible": sum(f["periodicity"] is not None for f in features),
            "periodicity_ge_035": sum(f["periodicity"] is not None and f["periodicity"] >= .35
                                      for f in features),
            "period_search_boundary": sum(f["period_at_search_boundary"] for f in features),
        }
    estimated_union = 0
    for s in sessions:
        intervals = []
        for c in clips:
            if c.get("session_id") != s["id"]:
                continue
            end = c["event_end_ms"]
            a = max(s["start_ms"], end - c["full"]["duration_s"] * 1000)
            b = min(s["end_ms"], end) if s["end_ms"] is not None else end
            intervals.append((a, b))
        estimated_union += union_ms(intervals)
    return dict(
        sessions=sessions, event_total=len(rows), audio_event_total=len(audio),
        event_types=dict(collections.Counter(r["type"] for r in rows)),
        duration_ms=summary(durations),
        duration_counts={name: sum(test(d) for d in durations) for name, test in {
            "negative": lambda d: d < 0, "zero": lambda d: d == 0,
            "lt150": lambda d: d < 150, "eq150": lambda d: d == 150,
            "lt300": lambda d: d < 300, "lt1000": lambda d: d < 1000,
            "ge6000": lambda d: d >= 6000}.items()},
        duplicate_peak_neighbor_pairs=len(pairs),
        duplicate_peak_neighbor_events=len({(p["session_id"], p[k])
                                            for p in pairs for k in ("left_id", "right_id")}),
        audio_start_near_screen_30s=sum(r["screen_change_within_30s"] for r in audio),
        coverage_by_original_type=coverage,
        clip_members=len(members), decoded=len(clips), decode_errors=errors,
        event_references=len(referenced), missing_event_references=len(referenced - members),
        representative_references=len(representatives),
        missing_representatives=sorted(representatives - members),
        unreferenced_members=sorted(members - referenced),
        encoded_duplicate_count=len(clips) - len({c["sha256"] for c in clips}),
        decoded_duration_sum_s=sum(c["full"]["duration_s"] for c in clips),
        estimated_clip_union_s=estimated_union / 1000,
        session_duration_sum_s=sum(s["duration_ms"] or 0 for s in sessions) / 1000,
        acoustic=by_scope,
        comparison=dict(n=len(compared),
                        pre2s_n=sum("candidate_pre2s" in c for c in clips),
                        tail_vs_pre2s_band_l1=summary([c["tail_vs_pre2s_band_l1"]
                                                     for c in clips if "candidate_pre2s" in c]),
                        old_midpoint_before_candidate=sum(c["old_midpoint_before_candidate"] for c in compared),
                        old_window_entirely_before_candidate=sum(c["old_window_entirely_before_candidate"] for c in compared),
                        candidate_truncated=sum(c["candidate_truncated"] for c in compared),
                        metrics={k: summary([c[k] for c in compared]) for k in (
                            "old_vs_full_band_l1", "full_vs_candidate_band_l1",
                            "old_vs_candidate_band_l1", "old_vs_candidate_centroid_delta_hz",
                            "candidate_minus_full_rms_db",
                            "candidate_boundary_sensitivity_max_band_l1")},
                        band_l1_ge_05=sum(c["old_vs_candidate_band_l1"] >= .5 for c in compared)))


def flatten(record, prefix=""):
    out = {}
    for key, value in record.items():
        key = prefix + key
        if isinstance(value, dict):
            out.update(flatten(value, key + "."))
        elif isinstance(value, (list, tuple)):
            out[key] = json.dumps(value, ensure_ascii=False)
        else:
            out[key] = value
    return out


def write_csv(path, records):
    flat = [flatten(r) for r in records]
    fields = sorted({k for r in flat for k in r})
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(flat)


def report(a):
    c = a["comparison"]
    lines = [
        "# 本地音频导出客观分析",
        "",
        "录音未上传、未人工听辨。原分类不是标注真值；没有完整录音，不能计算准确率、召回率或声称分类改善。",
        "旧导出没有 sample offset，无法精确重建 candidate。尾部墙钟时长反推、跳过前 2 秒的 pre-roll 推定均为探索；AAC 延迟、补零、截断与时钟偏差未消除。",
        "",
        "## 事件与覆盖",
        f"- 会话 {len(a['sessions'])}；总事件 {a['event_total']}；声学事件 {a['audio_event_total']}。",
        f"- 声学时长分桶（可重叠）：`{json.dumps(a['duration_counts'])}`。",
        f"- 2 秒内相邻声学事件同峰值（容差 1e-6 dB）：{a['duplicate_peak_neighbor_pairs']} 对，涉及 {a['duplicate_peak_neighbor_events']} 条；不是重复声源的证明。",
        f"- 距屏幕切换 ±30 秒的声学事件起点：{a['audio_start_near_screen_30s']}；不代表清醒真值。",
        f"- ZIP m4a {a['clip_members']}；成功解码 {a['decoded']}；失败 {len(a['decode_errors'])}。",
        f"- 事件引用不同路径 {a['event_references']}，未随 ZIP 导出 {a['missing_event_references']}；代表引用 {a['representative_references']}，缺失 {len(a['missing_representatives'])}。",
        f"- 片段时长相加 {a['decoded_duration_sum_s']:.3f} 秒；按事件结束时间尾部对齐的估计并集 {a['estimated_clip_union_s']:.3f} 秒；会话共 {a['session_duration_sum_s']:.3f} 秒。",
        "片段可能重叠且经过代表片段选择，时长相加不是实际录音覆盖率；并集也仅是尾部对齐模型估计。",
        "",
        "| 原事件类型 | 数量 |",
        "| --- | ---: |",
    ]
    lines.extend(f"| {k} | {v} |" for k, v in sorted(a["event_types"].items()))
    lines += ["", "| 原声学类型 | 事件 | 有路径 | ZIP 内 | <150 ms |",
              "| --- | ---: | ---: | ---: | ---: |"]
    lines.extend(f"| {k} | {v['events']} | {v['references']} | {v['present']} | {v['duration_lt150']} |"
                 for k, v in a["coverage_by_original_type"].items())
    lines += ["", "## 中点、全片与候选尾部",
              f"- 可对比 {c['n']}；旧中点位于估计候选之前 {c['old_midpoint_before_candidate']}；整个旧 32 ms 窗在候选之前 {c['old_window_entirely_before_candidate']}。",
              f"- 可探索 pre2s 范围 {c['pre2s_n']}；尾部与 pre2s 频带 L1 差中位数 {c['tail_vs_pre2s_band_l1'].get('median', 0):.4f}。长候选可能已被 ring 截断，不应机械去掉 2 秒。",
              f"- 候选墙钟时长超过解码片段 {c['candidate_truncated']}；旧中点与候选频带 L1 距离 ≥0.5 共 {c['band_l1_ge_05']}。",
              "L1 为低/中/高三频带占比绝对差之和（0–2），不是分类错误率。以下为全部可比片段的分布：",
              "", "| 对照指标 | 中位数 | P90 |", "| --- | ---: | ---: |"]
    lines.extend(f"| {k} | {v.get('median', 0):.4f} | {v.get('p90', 0):.4f} |"
                 for k, v in c["metrics"].items())
    lines += ["", "旧中点→全片多窗主要展示单窗采样敏感性；全片多窗→候选尾部多窗展示范围选择敏感性。"
              "中点落在候选之前说明 pre-roll 可进入旧谱特征，但不能据此断言真实声音类别。",
              "", "## 稳定性与周期性"]
    for scope, v in a["acoustic"].items():
        lines += [f"- {scope}：包络 CV≤0.25 为 {v['stable_envelope_cv_le_025']}；"
                  f"至少容纳两个周期的可估计片段 {v['period_eligible']}；"
                  f"自相关≥0.35 为 {v['periodicity_ge_035']}；最佳滞后在搜索边界 {v['period_search_boundary']}。"]
    lines += [
        "稳定阈值只是探索性统计；不能识别风扇、空调等来源。周期是 10 ms RMS 包络的 0.3–3 s 滞后自相关，"
        "去均值后按完整包络能量归一化，并要求至少两周期；短片段为 null。周期峰不等于鼾声或呼吸周期。",
        "完整特征分布、平坦度、频谱变化、削顶比例及每条记录见 JSON/CSV。没有对整夜稳定噪声或周期出现率作外推。",
        "", "## 方法和限制",
        "16 kHz 单声道 float PCM；512 点 Hann FFT，低频 80–300 Hz、中频 (300,1500]、高频 (1500,6000]。"
        "多窗步长 256，包含首尾窗口，按功率聚合；旧方法取全片中点单窗。没有重跑分类器。",
        "6 秒 ring 和即时 slice 不会等待未来 post-roll；2 秒 pre-roll 会使短候选的片段中点落入上下文。"
        "源码分类用的 duration 下限 150 ms 不会改写导出 timeMs/endMs，因此导出仍可小于 150 ms。",
        "本报告仅审计已导出代表片段。缺少完整 PCM、运行参数快照、人工标注，不能判断漏检、真实夜醒或疾病。",
        "", "## 本次命令故障",
        "首次四条 Get-Content 使用了父目录作为工作目录，导致找不到源码/文档。"
        "已显式指定 sleep-desk 工作目录重试成功；后续命令固定 workdir。未找到项目 agent-incidents 记录工具，未改 AGENTS.md。",
        "",
    ]
    return "\n".join(lines)


def self_test():
    t = np.arange(SR) / SR
    low, high = .1 * np.sin(2 * np.pi * 125 * t), .1 * np.sin(2 * np.pi * 3000 * t)
    x = np.concatenate([low, low, high[:SR // 4]])
    r = analyze_clip(x, {"duration_ms": 250})
    assert r["old_midpoint"]["low"] > .99
    assert r["candidate_tail"]["high"] > .99
    assert r["old_window_entirely_before_candidate"]
    assert r["candidate_tail"]["periodicity"] is None
    assert acoustic(np.zeros(1000))["periodicity"] is None
    assert union_ms([(0, 10), (5, 15), (30, 40)]) == 25
    assert member_path("audio_clips/s/a.m4a") == "clips/s/a.m4a"
    for bad in ("../x", "/abs", "audio_clips/../x"):
        try:
            member_path(bad)
        except ValueError:
            pass
        else:
            raise AssertionError("Unsafe reference accepted")
    periodic_t = np.arange(SR * 4) / SR
    modulated = (.1 + .08 * np.sin(2 * np.pi * 2 * periodic_t)) * np.sin(2 * np.pi * 500 * periodic_t)
    assert abs(acoustic(modulated)["period_s"] - .5) <= .01
    print("Self-test passed: context separation, short/silent input, periodicity, union, paths.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", nargs="?", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--pcm-dir", type=Path,
                        help="Optional external directory for flat s16le PCM and manifest.tsv")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.zip_path or not args.output_dir:
        parser.error("zip_path and --output-dir are required")
    repo = Path(__file__).resolve().parents[1]
    out = args.output_dir.resolve()
    if out == repo or out.is_relative_to(repo):
        parser.error("Sensitive reports must be outside the repository")
    pcm_dir = args.pcm_dir.resolve() if args.pcm_dir else None
    if pcm_dir:
        if pcm_dir == repo or pcm_dir.is_relative_to(repo):
            parser.error("PCM must be outside the repository")
        pcm_dir.mkdir(parents=True, exist_ok=True)
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        parser.error("ffmpeg must be available on PATH")
    clips, errors, pcm_manifest = [], [], []
    with zipfile.ZipFile(args.zip_path) as z:
        names = z.namelist()
        if len(set(names)) != len(names):
            raise ValueError("Duplicate ZIP member names")
        info = z.getinfo("sessions.json")
        if info.file_size > 64 * 1024 * 1024:
            raise ValueError("Metadata exceeds 64 MiB safety limit")
        data = json.loads(z.read(info))
        infos = sorted((i for i in z.infolist() if i.filename.lower().endswith(".m4a")),
                       key=lambda i: i.filename)
        members = {i.filename for i in infos}
        rows, pairs, sessions = metadata(data, members)
        by_member = collections.defaultdict(list)
        for r in rows:
            if r["member"]:
                by_member[r["member"]].append(r)
        for index, info in enumerate(infos, 1):
            try:
                x, digest = decode(z, info, ffmpeg)
                matches = by_member[info.filename]
                e = matches[0] if len(matches) == 1 else None
                clip = dict(member=info.filename, sha256=digest, event_matches=len(matches),
                            **analyze_clip(x, e))
                if e:
                    clip.update(session_id=e["session_id"], event_id=e["event_id"],
                                original_type=e["type"], event_end_ms=e["end_ms"],
                                event_duration_ms=e["duration_ms"])
                if pcm_dir:
                    pcm_path = pcm_dir / f"{index:04d}-{digest[:16]}.s16le"
                    pcm_path.write_bytes(np.clip(np.rint(x * 32768), -32768, 32767)
                                         .astype("<i2").tobytes())
                    pcm_manifest.append(dict(
                        path=str(pcm_path), event_type=e["type"] if e else "",
                        duration_ms=e["duration_ms"] if e else "",
                        screen_context=(str(e["screen_context_near"]).lower()
                                        if e and e["screen_context_known"] else "unknown"),
                        screen_context_known=bool(e and e["screen_context_known"]),
                        sample_rate=SR, channels=1, samples=len(x),
                        decoded_duration_ms=len(x) * 1000 / SR,
                        candidate_tail_start_sample=(max(0, len(x) - round(e["duration_ms"] * SR / 1000))
                                                     if e and e["duration_ms"] > 0 else ""),
                        candidate_pre2s_start_sample=2 * SR if len(x) > 2 * SR else "",
                        candidate_offsets_are_estimates=True,
                        member=info.filename, event_id=e["event_id"] if e else ""))
                clips.append(clip)
            except (ValueError, RuntimeError, subprocess.TimeoutExpired, zipfile.BadZipFile) as exc:
                errors.append(dict(member=info.filename, error=str(exc)))
            if index % 20 == 0 or index == len(infos):
                print(f"Decoded {index}/{len(infos)}; errors={len(errors)}", flush=True)
    a = aggregate(data, rows, pairs, sessions, clips, errors, members)
    with args.zip_path.open("rb") as f:
        zip_hash = hashlib.file_digest(f, "sha256").hexdigest()
    payload = dict(schema_version=1, source_sha256=zip_hash,
                   source_name=args.zip_path.name, numpy_version=np.__version__,
                   ffmpeg_version=subprocess.run([ffmpeg, "-version"], capture_output=True,
                                                 text=True, check=True).stdout.splitlines()[0],
                   aggregate=a, clips=clips, events=rows, duplicate_peak_pairs=pairs)
    out.mkdir(parents=True, exist_ok=True)
    (out / "analysis.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2,
                                                allow_nan=False), encoding="utf-8")
    (out / "aggregate.json").write_text(json.dumps(a, ensure_ascii=False, indent=2,
                                                  allow_nan=False), encoding="utf-8")
    write_csv(out / "clips.csv", clips)
    write_csv(out / "events.csv", rows)
    write_csv(out / "duplicate-peak-pairs.csv", pairs)
    if pcm_dir:
        fields = ["path", "event_type", "duration_ms", "screen_context",
                  "screen_context_known", "sample_rate", "channels", "samples",
                  "decoded_duration_ms", "candidate_tail_start_sample",
                  "candidate_pre2s_start_sample", "candidate_offsets_are_estimates",
                  "member", "event_id"]
        with (pcm_dir / "manifest.tsv").open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fields, delimiter="\t")
            writer.writeheader()
            writer.writerows(pcm_manifest)
    (out / "report.md").write_text(report(a), encoding="utf-8")
    print(json.dumps({k: a[k] for k in ("event_total", "audio_event_total", "decoded",
                                      "duration_counts", "duplicate_peak_neighbor_pairs")},
                     ensure_ascii=False))
    print(f"Reports: {out}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
