"""可复现基准（FRAME-6）：抽帧吞吐 + 端到端性能 + 稳定性重复。

问的是什么、怎么答的：

| 问题（plan §23 FRAME-6） | 本模块的实测口径 |
| --- | --- |
| 批量 vs 逐点 seek 的耗时 | 同一 `SamplingPolicy` 网格、同一媒体，两种方式各跑 N 次，记 wall time |
| CPU/磁盘 | 帧字节数逐文件统计（`perf.measure_files`）；CPU 不在进程内测（见下） |
| 逐点等价性 | 同时间点两路帧的**平均像素差**（> 容差即"不等价"，不掩盖） |
| Qwen 总调用数 | 端到端跑完后读 `metrics.counters`（逻辑调用）与 `visionRequests`（含重试） |
| Review 比例 | `metrics.derived.reviewRatio` |
| 每节课耗时与成本 | 端到端实测耗时；成本 = 实测 token × 部署方配置单价（未配置则为 null） |

**不做的事**：不把"估算"写成"实测"。CPU 时间不在本模块内测：ffmpeg 是子进程，
Python 侧 `process_time()` 测不到它的 CPU（Windows 也没有可用的 `RUSAGE_CHILDREN`），
所以只报 wall time 与字节数；需要精确 CPU 时用 `ffmpeg -benchmark` 单独跑（见 round 文档）。

测量用的视频如果不是真实课堂，报告里必然带 `caveats`：**性能可迁移、准确率不可迁移**。
"""

from __future__ import annotations

import hashlib
import json
import statistics
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence

from . import perf, wire
from .config import Config
from .media import FfmpegTools
from .pipeline import compute_stats, run_job
from .video.frame_extractor import (EQUIVALENCE_TOLERANCE, FrameExtractor, _mean_pixel_diff,
                                    verify_batch_equivalence)
from .video.probe import VideoMetadata, probe_video
from .video.sampling import SamplingPolicy

BENCHMARK_VERSION = "headup-bench-v1"

MODE_SEEK = "seek"
MODE_BATCH = "batch"

#: 默认的 Decord 决策线：批量抽帧单帧耗时低于该值就认为"不需要引入 Decord"
DEFAULT_DECORD_THRESHOLD_S_PER_FRAME = 0.5


@dataclass
class ModeRun:
    """一次抽帧运行（一个模式 × 一次重复）。"""

    mode: str
    repeat: int
    seconds: float
    frames: int
    byte_count: int
    timestamps: list[int] = field(default_factory=list)
    extractor_mode: str | None = None
    equivalence: dict[str, Any] | None = None
    error: str | None = None
    #: 逐点耗时（毫秒）——逐点 seek 的成本与**采样位置**强相关，必须能看出这条曲线
    point_timings_ms: list[dict[str, Any]] = field(default_factory=list)
    #: 整格抽取的拆分（等间隔前缀批量 / 尾部逐点）
    grid_split: dict[str, int] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "repeat": self.repeat,
            "seconds": round(self.seconds, 3),
            "secondsPerFrame": (round(self.seconds / self.frames, 4) if self.frames else None),
            "frames": self.frames,
            "bytes": self.byte_count,
            "bytesPerFrame": (round(self.byte_count / self.frames) if self.frames else None),
            "extractorMode": self.extractor_mode,
            "gridSplit": self.grid_split,
            "equivalence": self.equivalence,
            "error": self.error,
            "pointTimingsMs": self.point_timings_ms,
        }


def _summarize_runs(runs: Sequence[ModeRun]) -> dict[str, Any]:
    """把同一模式的多次重复汇总成 min/median/max（稳定性看极差，不看单次）。"""
    good = [run for run in runs if run.error is None]
    seconds = [run.seconds for run in good]
    per_frame = [run.seconds / run.frames for run in good if run.frames]
    return {
        "runs": [run.to_dict() for run in runs],
        "ok": len(good),
        "failed": len(runs) - len(good),
        "seconds": {"min": round(min(seconds), 3), "median": round(statistics.median(seconds), 3),
                    "max": round(max(seconds), 3)} if seconds else None,
        "secondsPerFrame": {
            "min": round(min(per_frame), 4),
            "median": round(statistics.median(per_frame), 4),
            "max": round(max(per_frame), 4),
        } if per_frame else None,
        "frames": max((run.frames for run in good), default=0),
        "bytes": max((run.byte_count for run in good), default=0),
    }


# --------------------------------------------------------------------------
# 抽帧基准
# --------------------------------------------------------------------------

def sample_grid(meta: VideoMetadata, *, window_ms: int, max_points: int = 0,
                every: int = 1) -> list[int]:
    """按 `SamplingPolicy` 生成采样时间点（可截断/抽样，抽样时必须保持等间隔）。

    `every > 1` 只用于把长视频的网格抽稀成"等间隔子集"——批量抽帧要求等间隔，
    这样两种模式仍然可比（不允许用不等间隔子集做对比）。
    """
    points = SamplingPolicy(window_ms=window_ms).grid(0, meta.duration_ms)
    timestamps = [point.sample_at_ms for point in points]
    if every > 1:
        timestamps = timestamps[::every]
    if max_points > 0:
        timestamps = timestamps[:max_points]
    return timestamps


def _run_extraction_once(cfg: Config, tools: FfmpegTools, media_path: Path, timestamps: list[int],
                         mode: str, out_dir: Path, *, duration_ms: int | None,
                         verify_equivalence: bool = True) -> ModeRun:
    extractor = FrameExtractor(cfg, tools, media_path, duration_ms=duration_ms)
    started = time.perf_counter()
    points: list[dict[str, Any]] = []
    try:
        if mode == MODE_BATCH:
            samples = extractor.extract_grid(timestamps, out_dir / f"batch-{time.time_ns()}",
                                            verify_first=verify_equivalence)
            paths = [sample.image_path for sample in samples]
        else:
            directory = out_dir / f"seek-{time.time_ns()}"
            paths = []
            for timestamp in timestamps:
                point_started = time.perf_counter()
                paths.append(extractor.extract_at(timestamp, directory).image_path)
                point_seconds = time.perf_counter() - point_started
                points.append({"timestampMs": timestamp,
                               "seconds": round(point_seconds, 3)})
        seconds = time.perf_counter() - started
        stats = perf.measure_files(paths)
        return ModeRun(mode=mode, repeat=0, seconds=seconds, frames=stats.files,
                       byte_count=stats.bytes, timestamps=list(timestamps),
                       extractor_mode=getattr(extractor, "last_mode", None),
                       equivalence=getattr(extractor, "last_equivalence", None),
                       point_timings_ms=points,
                       grid_split=getattr(extractor, "last_grid_split", None))
    except Exception as exc:  # noqa: BLE001 - 基准必须把失败如实记下来
        return ModeRun(mode=mode, repeat=0, seconds=time.perf_counter() - started, frames=0,
                       byte_count=0, timestamps=list(timestamps),
                       point_timings_ms=points,
                       error=f"{type(exc).__name__}: {getattr(exc, 'message', exc)}")


def run_extraction_benchmark(cfg: Config, media_path: Path, *, window_ms: int = 10_000,
                             timestamps: list[int] | None = None, max_points: int = 0,
                             every: int = 1, modes: Iterable[str] = (MODE_SEEK, MODE_BATCH),
                             repeats: int = 1, out_dir: Path | None = None,
                             verify_equivalence: bool = True,
                             verify_timestamps: bool = True) -> dict[str, Any]:
    """逐点 seek vs 单次批量：耗时、帧数、字节、逐点等价性。"""
    tools = FfmpegTools.locate(cfg)
    if not tools.available:
        raise RuntimeError(f"缺少 ffmpeg/ffprobe: {tools.detail}")
    meta = probe_video(media_path, cfg)
    points = list(timestamps) if timestamps else sample_grid(meta, window_ms=window_ms,
                                                             max_points=max_points, every=every)
    work = Path(out_dir) if out_dir else Path(cfg.work_dir) / "benchmark"
    work.mkdir(parents=True, exist_ok=True)

    runs: dict[str, list[ModeRun]] = {}
    for mode in modes:
        collected: list[ModeRun] = []
        for index in range(max(1, repeats)):
            run = _run_extraction_once(cfg, tools, media_path, points, mode, work,
                                       duration_ms=meta.duration_ms,
                                       verify_equivalence=verify_equivalence)
            run.repeat = index + 1
            collected.append(run)
            print(f"[extract] mode={mode} repeat={run.repeat} "
                  f"{run.seconds:.2f}s frames={run.frames} "
                  f"{run.byte_count / 1e6:.2f}MB"
                  + (f" error={run.error}" if run.error else ""))
        runs[mode] = collected

    comparison = compare_modes(cfg, work, runs)
    evidence = (timestamp_evidence(cfg, tools, media_path, points, meta.duration_ms)
                if verify_timestamps and points else None)
    report: dict[str, Any] = {
        "media": meta.to_dict(),
        "windowMs": window_ms,
        "samplePoints": len(points),
        "timestamps": points,
        "extraction": {mode: _summarize_runs(collected) for mode, collected in runs.items()},
        "equivalence": comparison,
        "timestampEvidence": evidence,
        "proof": (verify_batch_equivalence(
            FrameExtractor(cfg, tools, media_path, duration_ms=meta.duration_ms),
            points[0], work / "proof") if points and verify_equivalence else None),
    }
    report["conclusions"] = extraction_conclusions(report)
    return report


def timestamp_evidence(cfg: Config, tools: FfmpegTools, media_path: Path, timestamps: list[int],
                       duration_ms: int | None, *, max_seek_points: int = 6) -> dict[str, Any]:
    """"帧确实来自那个时间点"的可核对证据：读实际解码帧的 pts 偏移（不写帧文件）。

    - 批量：一次 `fps` + `showinfo`，给出整段网格的实际偏移；
    - 逐点：均匀抽几个点逐点核（每点约 0.1s），给最大/平均偏移。
    """
    extractor = FrameExtractor(cfg, tools, media_path, duration_ms=duration_ms)
    batch = extractor.decoded_timestamps(timestamps, batch=True)
    step = max(1, len(timestamps) // max(1, max_seek_points))
    sampled = timestamps[::step][:max_seek_points]
    seek = extractor.decoded_timestamps(sampled, batch=False)
    return {
        "frameIntervalMs": (round(timestamps[1] - timestamps[0]) if len(timestamps) > 1 else None),
        "batch": batch,
        "seekSampled": seek,
        "note": ("offsetMs = 实际解码帧相对请求时间点晚多少毫秒（不加 -copyts，"
                 "因此偏移即为偏差）；只用于审计，生产路径不做这个额外调用"),
    }


def compare_modes(cfg: Config, work: Path, runs: dict[str, list[ModeRun]]) -> dict[str, Any]:
    """比较两种模式产出的帧（同一时间点逐张比平均像素差）。"""
    del cfg
    seek = next((run for run in runs.get(MODE_SEEK, []) if run.error is None), None)
    batch = next((run for run in runs.get(MODE_BATCH, []) if run.error is None), None)
    if seek is None or batch is None:
        return {"comparable": False,
                "reason": "seek 或 batch 未成功，无法逐点比较"}
    seek_dir = _frame_dir(work, "seek")
    batch_dir = _frame_dir(work, "batch")
    if seek_dir is None or batch_dir is None:
        return {"comparable": False, "reason": "找不到两路帧目录"}
    same = [ts for ts in seek.timestamps if ts in set(batch.timestamps)]
    rows: list[dict[str, Any]] = []
    for timestamp in same:
        left = _find_frame(seek_dir, timestamp)
        right = _find_frame(batch_dir, timestamp)
        if left is None or right is None:
            continue
        difference = _mean_pixel_diff(left, right)
        rows.append({"timestampMs": timestamp, "meanPixelDiff": round(float(difference), 4),
                     "equivalent": bool(difference <= EQUIVALENCE_TOLERANCE)})
    diffs = [row["meanPixelDiff"] for row in rows]
    batch_mode = batch.extractor_mode
    evidence = batch_mode != "SEEK_FALLBACK"
    return {
        "comparable": True,
        "batchMode": batch_mode,
        "batchEvidence": evidence,
        "note": (None if evidence else
                 "批量已整段回退（首帧不等价）：本比较实际是逐点 vs 逐点，"
                 "不能作为批量等价性证据"),
        "comparedPoints": len(rows),
        "tolerance": EQUIVALENCE_TOLERANCE,
        "maxMeanPixelDiff": round(max(diffs), 4) if diffs else None,
        "meanMeanPixelDiff": round(statistics.fmean(diffs), 4) if diffs else None,
        "equivalentPoints": sum(1 for row in rows if row["equivalent"]),
        "allEquivalent": bool(rows) and all(row["equivalent"] for row in rows),
        "points": rows,
    }


def _frame_dir(work: Path, prefix: str) -> Path | None:
    candidates = sorted([item for item in work.glob(f"{prefix}-*") if item.is_dir()],
                        key=lambda item: item.stat().st_mtime)
    return candidates[-1] if candidates else None


def _find_frame(directory: Path, timestamp_ms: int) -> Path | None:
    matches = sorted(directory.glob(f"frame-{int(timestamp_ms):09d}*.jpg"))
    return matches[0] if matches else None


def extraction_conclusions(report: dict[str, Any],
                           *, decord_threshold_s_per_frame: float =
                           DEFAULT_DECORD_THRESHOLD_S_PER_FRAME) -> list[str]:
    """从实测数据里生成结论（不写死任何数字）。"""
    lines: list[str] = []
    seek = report["extraction"].get(MODE_SEEK, {})
    batch = report["extraction"].get(MODE_BATCH, {})
    seek_seconds = (seek.get("seconds") or {}).get("median")
    batch_seconds = (batch.get("seconds") or {}).get("median")
    batch_runs = [run for run in batch.get("runs", []) if run.get("error") is None]
    # 只有"真的走了批量"的运行才能用来判 Decord：整段回退时的耗时其实是逐点 seek 的耗时
    real_batch = [run for run in batch_runs
                  if run.get("extractorMode") in ("BATCH", "BATCH+SEEK_TAIL")]
    if seek_seconds and batch_seconds:
        speedup = seek_seconds / batch_seconds
        lines.append(f"批量抽帧 {batch_seconds}s vs 逐点 seek {seek_seconds}s"
                     f"（{len(report['timestamps'])} 点，加速 {speedup:.2f}×）")
        seek_per = (seek.get("secondsPerFrame") or {}).get("median")
        if seek_per:
            lines.append(f"逐点单帧耗时 {seek_per}s（输入侧 accurate_seek：与采样位置无关）")
    if real_batch:
        batch_per = min(run["secondsPerFrame"] for run in real_batch
                        if run.get("secondsPerFrame"))
        lines.append(
            f"批量单帧耗时 {batch_per}s "
            + ("低于" if batch_per < decord_threshold_s_per_frame else "高于")
            + f" Decord 决策线 {decord_threshold_s_per_frame}s/帧 => "
            + ("不必引入 Decord（FFmpeg 批量已足够）"
               if batch_per < decord_threshold_s_per_frame
               else "需要评估 Decord（FFmpeg 批量吞吐不足）"))
    elif batch_runs:
        lines.append("批量在本网格上未走成批量（整段回退）=> 本次运行不能用来判 Decord，"
                     f"决策请用完整 {report.get('windowMs')}ms 网格的测量结果")
    if batch.get("failed"):
        lines.append("批量抽帧失败 —— 当前媒体上不可启用批量路径，必须走逐点 seek")
    if batch.get("runs"):
        good_runs = [run for run in batch["runs"] if run.get("error") is None]
        modes = {run.get("extractorMode") for run in good_runs}
        if "SEEK_FALLBACK" in modes:
            lines.append("批量路径出现整段回退（首帧不等价）=> 该媒体上批量不可靠")
        if "BATCH+SEEK_TAIL" in modes:
            split = next((run.get("gridSplit") for run in good_runs
                          if run.get("extractorMode") == "BATCH+SEEK_TAIL"), None)
            lines.append(f"网格末窗短 => 批量前缀 + 尾部逐点（{split}）："
                         "时间戳不变，只是尾部少量点走 seek")
    equivalence = report.get("equivalence") or {}
    if equivalence.get("comparable"):
        if not equivalence.get("batchEvidence", True):
            lines.append("批量等价性证据缺失：" + str(equivalence.get("note")))
        else:
            lines.append(f"逐点等价性：{equivalence['equivalentPoints']}/"
                         f"{equivalence['comparedPoints']} 点等价，最大平均像素差 "
                         f"{equivalence['maxMeanPixelDiff']}（容差 {equivalence['tolerance']}）")
            if not equivalence.get("allEquivalent"):
                lines.append("存在不等价采样点 => 不得启用批量路径（宁慢不假）")
    else:
        lines.append(f"未做逐点等价性比较：{equivalence.get('reason')}")
    evidence = report.get("timestampEvidence") or {}
    batch_offsets = (evidence.get("batch") or {}).get("maxOffsetMs")
    seek_offsets = (evidence.get("seekSampled") or {}).get("maxOffsetMs")
    interval = evidence.get("frameIntervalMs")
    if batch_offsets is not None or seek_offsets is not None:
        lines.append(f"时间点证据：批量帧实际 pts 最大偏移 {batch_offsets}ms、"
                     f"逐点抽样最大偏移 {seek_offsets}ms（抽样点间隔 {interval}ms 为网格间距，"
                     "偏移应远小于它）")
    return lines


# --------------------------------------------------------------------------
# 端到端基准
# --------------------------------------------------------------------------

def build_local_request(cfg: Config, media_path: Path, *, duration_ms: int, window_ms: int,
                        client_request_id: str, class_session_id: str = "session-bench-001",
                        media_id: str = "media-bench-001", start_ms: int = 0,
                        end_ms: int | None = None,
                        output_mode: str = "COUNTS") -> wire.JobRequest:
    """构造契约请求（本地文件走 `local_media`，不经过受控下载 —— 报告里会注明）。"""
    digest = hashlib.sha256()
    with media_path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    doc = {
        "schemaVersion": wire.SCHEMA_VERSION,
        "clientRequestId": client_request_id,
        "moduleType": wire.MODULE_TYPE,
        "classSessionId": class_session_id,
        "mediaId": media_id,
        "media": {"sha256": digest.hexdigest(), "durationMs": duration_ms,
                  "mimeType": "video/mp4",
                  "sourceUrl": "https://127.0.0.1/benchmark-local-media",
                  "urlExpiresAt": "2099-01-01T00:00:00Z"},
        "analysisRange": {"startMs": start_ms, "endMs": end_ms or duration_ms},
        "parameters": {"windowMs": window_ms, "outputMode": output_mode,
                       "definitionId": cfg.definition_id},
    }
    return wire.parse_job_request(doc, idempotency_key=client_request_id,
                                  allowed_definition_ids=cfg.allowed_definition_ids(),
                                  max_duration_ms=cfg.max_duration_ms,
                                  max_windows=cfg.max_windows)


def run_pipeline_benchmark(cfg: Config, media_path: Path, *, analyzer: Any,
                           window_ms: int = 10_000, duration_ms: int | None = None,
                           client_request_id: str | None = None,
                           work_root: Path | None = None,
                           analysis_range: tuple[int, int] | None = None) -> dict[str, Any]:
    """完整跑一遍 `run_job`（真实抽帧 + 给定分析器），返回指标与统计。

    传 `analyzer=None` 时使用 `build_analyzer(cfg)`；启发式/合成分析器**不会**产生
    真实人数，报告里必须用 `analyzer.synthetic` 标出来。
    """
    from .analyze import build_analyzer

    tools = FfmpegTools.locate(cfg)
    if not tools.available:
        raise RuntimeError(f"缺少 ffmpeg/ffprobe: {tools.detail}")
    meta = probe_video(media_path, cfg)
    total_ms = int(duration_ms or meta.duration_ms)
    start_ms, end_ms = analysis_range or (0, total_ms)
    request = build_local_request(
        cfg, media_path, duration_ms=total_ms, window_ms=window_ms,
        client_request_id=client_request_id or f"req-bench-{int(time.time())}",
        start_ms=start_ms, end_ms=end_ms)
    selected = analyzer if analyzer is not None else build_analyzer(cfg)
    metrics = perf.RunMetrics(enabled=cfg.perf_metrics_enabled)
    started = time.perf_counter()
    result = run_job(request=request, cfg=cfg, analyzer=selected,
                     work_root=work_root or (Path(cfg.work_dir) / "benchmark-jobs"),
                     local_media=media_path, metrics=metrics)
    seconds = time.perf_counter() - started
    stats = result.stats or compute_stats(result.payload, total_ms)
    return {
        "wallClockSeconds": round(seconds, 2),
        "analyzer": {"name": getattr(selected, "name", None),
                     "version": getattr(selected, "version", None),
                     "synthetic": getattr(selected, "name", "") != "qwen-vl"},
        "windows": len(result.windows),
        "stats": stats,
        "warnings": list(result.warnings),
        "metrics": perf.metrics_to_dict(metrics, cost=perf.estimate_cost(cfg, metrics)),
        "localMedia": True,
    }


# --------------------------------------------------------------------------
# 报告
# --------------------------------------------------------------------------

def build_report(*, extraction: dict[str, Any] | None = None,
                 pipeline: dict[str, Any] | None = None,
                 config: dict[str, Any] | None = None,
                 decord_threshold_s_per_frame: float = DEFAULT_DECORD_THRESHOLD_S_PER_FRAME,
                 caveats: Sequence[str] | None = None) -> dict[str, Any]:
    lines: list[str] = []
    if extraction:
        lines.extend(extraction.get("conclusions") or [])
    if pipeline:
        metrics = pipeline.get("metrics") or {}
        counters = metrics.get("counters") or {}
        derived = metrics.get("derived") or {}
        lines.append(f"端到端 {pipeline['wallClockSeconds']}s / {pipeline['windows']} 窗"
                     f"（分析器 {pipeline['analyzer']['name']}）")
        if counters:
            lines.append("调用：逻辑 " + str(counters.get(perf.COUNTER_VISION_CALLS))
                         + " 次、视觉窗口 " + str(counters.get(perf.COUNTER_WINDOWS_REVIEWED))
                         + " 个需复核、复核实际生效 "
                         + str(counters.get(perf.COUNTER_WINDOWS_REVIEW_APPLIED))
                         + f"；reviewRatio={derived.get('reviewRatio')}")
        tokens = metrics.get("tokens") or {}
        if tokens.get("promptTokens") or tokens.get("completionTokens"):
            lines.append(f"token：prompt={tokens.get('promptTokens')} "
                         f"completion={tokens.get('completionTokens')}")
        cost = metrics.get("cost")
        if cost:
            lines.append(f"成本估算：{cost['estimatedAmount']} {cost['currency']}"
                         f"（单价 {cost['unitPrices']}，估算非账单）")
        else:
            lines.append("成本未估算：部署方未配置单价（VISION_PRICE_*_PER_1K）")
    return {
        "benchmarkVersion": BENCHMARK_VERSION,
        "generatedAt": wire.utc_now(),
        "config": dict(config or {}),
        "decordDecision": {
            "thresholdSecondsPerFrame": decord_threshold_s_per_frame,
            "note": "决策线可配置；只有实测批量单帧耗时高于该线才需要评估 Decord",
        },
        "extraction": extraction,
        "pipeline": pipeline,
        "conclusions": lines,
        "caveats": list(caveats or [
            "所有数字都是本机实测；换机器/换视频会变，不能当 SLA",
            "CPU 时间未在进程内测量（ffmpeg 是子进程），只用 wall time 与字节数",
            "若媒体不是真实课堂，性能可迁移、准确率不可迁移",
        ]),
    }


def write_report(report: dict[str, Any], path: Path) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path
