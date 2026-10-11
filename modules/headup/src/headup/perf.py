"""性能与稳定性可观测性（FRAME-6）。

三条硬规则（plan §23 FRAME-6 + 用户全局规则）：

1. **指标只报实测值**：分阶段耗时（monotonic 时钟）、抽帧方式与帧数、视觉调用次数
   （含重试与回退模型）、token 用量、落盘字节。没有测到的量一律不出现，不写"估算耗时"。
2. **成本必须显式建模**：只有在配置了单价（`VISION_PRICE_PROMPT_PER_1K` /
   `VISION_PRICE_COMPLETION_PER_1K`）时才给金额，并在结果里带上单价与口径说明——
   估算 ≠ 账单；未配置单价时只给 token 用量（`cost = None`）。
3. **指标不进契约 payload**：payload 只描述抬头率结果，保持确定性。指标落
   `head_up_job.metrics_json`，通过 `GET /api/v1/jobs/{id}` 的增量字段 `metrics` 暴露，
   主项目可以完全忽略。

调用次数有两个层级，二者不能混为一谈：

- **逻辑调用**（`CountingAnalyzer`）：`analyze()` / `analyze_window()` 各算一次，
  一个窗口观测 = 一次逻辑调用；
- **HTTP 请求**（`QwenVlAnalyzer.request_count`）：包含计数不自洽重试与备用模型重试，
  是真正计费的次数。
"""

from __future__ import annotations

import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any, Callable, Iterator

METRICS_VERSION = "headup-perf-v1"

#: 逻辑调用计数（一次 analyze/analyze_window = 一次窗口观测）
COUNTER_VISION_CALLS = "vision.calls"
#: 送进模型的图片张数（多图复核一次请求算 3 张）
COUNTER_VISION_IMAGES = "vision.images"
#: 多图一次请求的调用数（FRAME-4 决策③ 的实际执行次数）
COUNTER_VISION_MULTI_IMAGE_CALLS = "vision.multiImageCalls"
COUNTER_WINDOWS_TOTAL = "windows.total"
COUNTER_WINDOWS_REVIEWED = "windows.reviewed"
COUNTER_WINDOWS_REVIEW_APPLIED = "windows.reviewApplied"
COUNTER_FRAMES_PRIMARY = "frames.primary"
COUNTER_FRAMES_REVIEW = "frames.review"
COUNTER_EXTRACTION_SEEK_FRAMES = "extraction.seekFrames"
COUNTER_EXTRACTION_BATCH_RUNS = "extraction.batchRuns"
COUNTER_EXTRACTION_BATCH_FALLBACKS = "extraction.batchFallbacks"
COUNTER_ANALYSIS_FAILURES = "analysis.failures"
COUNTER_FRAME_EXTRACTION_FAILURES = "extraction.failures"

DISK_PRIMARY_FRAME_BYTES = "primaryFrameBytes"
DISK_REVIEW_FRAME_BYTES = "reviewFrameBytes"
DISK_MEDIA_BYTES = "downloadedMediaBytes"

#: 阶段名（与 pipeline 的 STAGE_* 同名，便于和进度字段对照）
STAGE_DOWNLOAD = "DOWNLOAD_MEDIA"
STAGE_VISION_PRIMARY = "VISION_PRIMARY"
STAGE_VISION_REVIEW = "VISION_REVIEW"
STAGE_CLEANUP = "CLEANUP"

PHASE_PRIMARY = "PRIMARY"
PHASE_REVIEW = "REVIEW"


@dataclass
class StageTiming:
    """一个阶段被执行的次数与耗时（毫秒，来自实测）。"""

    count: int = 0
    total_ms: float = 0.0
    max_ms: float = 0.0

    def add(self, milliseconds: float) -> None:
        self.count += 1
        self.total_ms += float(milliseconds)
        self.max_ms = max(self.max_ms, float(milliseconds))

    def to_dict(self) -> dict[str, Any]:
        return {
            "count": self.count,
            "totalMs": round(self.total_ms, 1),
            "meanMs": round(self.total_ms / self.count, 1) if self.count else None,
            "maxMs": round(self.max_ms, 1),
        }


class RunMetrics:
    """一次任务运行期间的指标收集器（线程内使用，不跨线程共享）。"""

    def __init__(self, *, enabled: bool = True,
                 clock: Callable[[], float] | None = None) -> None:
        self.enabled = bool(enabled)
        self._clock = clock or time.perf_counter
        self.started = self._clock()
        self.stages: dict[str, StageTiming] = {}
        self.counters: dict[str, int] = {}
        self.tokens: dict[str, Any] = {"promptTokens": 0, "completionTokens": 0,
                                       "callsWithUsage": 0}
        self.models: dict[str, int] = {}
        self.disk: dict[str, int] = {}
        self.notes: dict[str, Any] = {}

    # -- 计时 -------------------------------------------------------------
    @contextmanager
    def stage(self, name: str) -> Iterator[None]:
        if not self.enabled:
            yield
            return
        start = self._clock()
        try:
            yield
        finally:
            self.record_stage(name, (self._clock() - start) * 1000.0)

    def record_stage(self, name: str, milliseconds: float) -> None:
        if not self.enabled:
            return
        self.stages.setdefault(name, StageTiming()).add(milliseconds)

    def elapsed_ms(self) -> float:
        return (self._clock() - self.started) * 1000.0

    def clock(self) -> float:
        """当前单调时钟读数（供包装器测量单个调用耗时）。"""
        return self._clock()

    # -- 计数 -------------------------------------------------------------
    def bump(self, name: str, value: int = 1) -> None:
        if not self.enabled:
            return
        self.counters[name] = int(self.counters.get(name, 0)) + int(value)

    def count_model(self, name: str | None) -> None:
        if not self.enabled or not name:
            return
        self.models[name] = int(self.models.get(name, 0)) + 1

    def add_usage(self, trace: dict[str, Any] | None) -> None:
        """从一次模型请求的追溯信息里累计 token 用量（缺失时不计、不猜）。"""
        if not self.enabled or not trace:
            return
        prompt = trace.get("promptTokens")
        completion = trace.get("completionTokens")
        if isinstance(prompt, int) and isinstance(completion, int):
            self.tokens["promptTokens"] += prompt
            self.tokens["completionTokens"] += completion
            self.tokens["callsWithUsage"] += 1

    def add_disk(self, key: str, byte_count: int) -> None:
        if not self.enabled:
            return
        self.disk[key] = int(self.disk.get(key, 0)) + max(0, int(byte_count))

    def add_frame_files(self, count: int, *, review: bool, byte_count: int = 0) -> None:
        if not self.enabled:
            return
        self.bump(COUNTER_FRAMES_REVIEW if review else COUNTER_FRAMES_PRIMARY, count)
        self.add_disk(DISK_REVIEW_FRAME_BYTES if review else DISK_PRIMARY_FRAME_BYTES,
                      byte_count)

    def note(self, key: str, value: Any) -> None:
        if not self.enabled:
            return
        self.notes[key] = value

    # -- 输出 -------------------------------------------------------------
    def derived(self) -> dict[str, Any]:
        total = int(self.counters.get(COUNTER_WINDOWS_TOTAL, 0))
        reviewed = int(self.counters.get(COUNTER_WINDOWS_REVIEWED, 0))
        calls = int(self.counters.get(COUNTER_VISION_CALLS, 0))
        images = int(self.counters.get(COUNTER_VISION_IMAGES, 0))
        return {
            "reviewRatio": round(reviewed / total, 4) if total else None,
            "visionCallsPerWindow": round(calls / total, 3) if total else None,
            "visionImagesPerWindow": round(images / total, 3) if total else None,
        }

    def to_dict(self, *, cost: dict[str, Any] | None = None,
                extra_notes: dict[str, Any] | None = None,
                wall_clock_ms: float | None = None) -> dict[str, Any]:
        notes = dict(self.notes)
        if extra_notes:
            notes.update(extra_notes)
        return {
            "metricsVersion": METRICS_VERSION,
            "enabled": self.enabled,
            "wallClockMs": round(self.elapsed_ms() if wall_clock_ms is None
                                 else float(wall_clock_ms), 1),
            "stageTimingsMs": {name: timing.to_dict() for name, timing in self.stages.items()},
            "counters": dict(self.counters),
            "models": dict(self.models),
            "tokens": dict(self.tokens),
            "diskBytes": dict(self.disk),
            "derived": self.derived(),
            "cost": cost,
            "notes": notes,
        }


def null_metrics() -> RunMetrics:
    """关闭状态的指标收集器（调用方不必到处写 `if metrics`）。"""
    return RunMetrics(enabled=False)


def metrics_to_dict(metrics: RunMetrics | None, *,
                    cost: dict[str, Any] | None = None) -> dict[str, Any] | None:
    if metrics is None or not metrics.enabled:
        return None
    return metrics.to_dict(cost=cost)


# --------------------------------------------------------------------------
# 分析器包装：把逻辑调用记进指标
# --------------------------------------------------------------------------

class CountingAnalyzer:
    """包装 `FrameAnalyzer`，统计逻辑调用次数、图片张数、模型分布与 token 用量。

    - 不改变判定行为：计数失败绝不影响结果（只记录）；
    - `analyze_window` 只在被包装对象确实支持时暴露（pipeline 靠 `callable()` 判断能力，
      包装器不能凭空"声称"支持多图）；
    - `set_phase()` 让调用方标注当前处于代表帧判定还是复核（默认 PRIMARY）。
    """

    def __init__(self, inner: Any, metrics: RunMetrics) -> None:
        self._inner = inner
        self.metrics = metrics
        self._phase = PHASE_PRIMARY
        if callable(getattr(inner, "analyze_window", None)):
            self.analyze_window = self._analyze_window      # 仅当真支持时暴露

    # -- 透传 -------------------------------------------------------------
    def __getattr__(self, item: str) -> Any:
        if item.startswith("_"):
            # 避免 _inner 缺失时无限递归（__getattr__ 里再访问 self._inner）
            raise AttributeError(item)
        return getattr(self._inner, item)

    @property
    def name(self) -> str:
        return getattr(self._inner, "name", "unknown")

    @property
    def version(self) -> str:
        return getattr(self._inner, "version", "unknown")

    def set_phase(self, phase: str) -> None:
        self._phase = PHASE_REVIEW if phase == PHASE_REVIEW else PHASE_PRIMARY
        setter=getattr(self._inner,"set_phase",None)
        if callable(setter):setter(phase)

    # -- 调用 -------------------------------------------------------------
    def analyze(self, image_path: Any) -> Any:
        return self._call(1, lambda: self._inner.analyze(image_path), multi=False)

    def _analyze_window(self, image_paths: list[Any]) -> Any:
        return self._call(len(image_paths),
                          lambda: self._inner.analyze_window(image_paths), multi=True)

    analyze_frame = analyze

    # -- 内部 -------------------------------------------------------------
    def _call(self, images: int, invoke: Callable[[], Any], *, multi: bool) -> Any:
        stage = STAGE_VISION_REVIEW if self._phase == PHASE_REVIEW else STAGE_VISION_PRIMARY
        start = self.metrics.clock() if self.metrics.enabled else None
        try:
            reading = invoke()
        finally:
            if start is not None:
                self.metrics.record_stage(stage, (self.metrics.clock() - start) * 1000.0)
        self.metrics.bump(COUNTER_VISION_CALLS)
        self.metrics.bump(COUNTER_VISION_IMAGES, max(0, int(images)))
        if multi:
            self.metrics.bump(COUNTER_VISION_MULTI_IMAGE_CALLS)
        self.metrics.count_model(getattr(reading, "model_used", None))
        self.metrics.add_usage(getattr(reading, "trace", None))
        self._absorb_transport()
        return reading

    def _absorb_transport(self) -> None:
        """把视觉客户端自己的传输层计数（含重试）快照进 notes。

        逻辑调用数（`vision.calls`）与真实 HTTP 请求数（`vision.requests`）不是一回事：
        计数不自洽重试与备用模型重试都会增加后者而不增加前者。
        """
        requests = getattr(self._inner, "request_count", None)
        if isinstance(requests, int):
            self.metrics.note("visionRequests", requests)
        notes: list[str] = getattr(self._inner, "request_notes", None) or []
        if notes:
            self.metrics.note("visionRequestNotes", list(notes)[-20:])


# --------------------------------------------------------------------------
# 成本估算（可配置单价；未配置则只报 token）
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class CostModel:
    """按实测 token 计算成本；单价由部署方提供，模块不内置任何价格。"""

    currency: str = "CNY"
    prompt_price_per_1k: float = 0.0
    completion_price_per_1k: float = 0.0

    @property
    def configured(self) -> bool:
        return self.prompt_price_per_1k > 0 or self.completion_price_per_1k > 0

    def estimate(self, prompt_tokens: int, completion_tokens: int) -> dict[str, Any] | None:
        if not self.configured:
            return None
        amount = (float(prompt_tokens) / 1000.0 * self.prompt_price_per_1k
                  + float(completion_tokens) / 1000.0 * self.completion_price_per_1k)
        return {
            "currency": self.currency,
            "estimatedAmount": round(amount, 6),
            "promptTokens": int(prompt_tokens),
            "completionTokens": int(completion_tokens),
            "unitPrices": {"promptPer1KTokens": self.prompt_price_per_1k,
                           "completionPer1KTokens": self.completion_price_per_1k},
            "note": ("按配置单价对实测 token 的算术估算，不是账单；"
                     "图片 token 已计入 prompt_tokens"),
        }


def cost_model_from_config(cfg: Any) -> CostModel:
    return CostModel(currency=str(getattr(cfg, "cost_currency", "CNY")),
                     prompt_price_per_1k=float(getattr(cfg, "vision_price_prompt_per_1k", 0.0)),
                     completion_price_per_1k=float(getattr(cfg, "vision_price_completion_per_1k",
                                                         0.0)))


def estimate_cost(cfg: Any, metrics: RunMetrics | None) -> dict[str, Any] | None:
    if metrics is None or not metrics.enabled:
        return None
    model = cost_model_from_config(cfg)
    return model.estimate(int(metrics.tokens.get("promptTokens", 0)),
                          int(metrics.tokens.get("completionTokens", 0)))


@dataclass
class FrameByteStats:
    """一组帧文件的字节数与张数（用于磁盘用量统计）。"""

    files: int = 0
    bytes: int = 0
    missing: int = 0
    paths: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {"files": self.files, "bytes": self.bytes, "missing": self.missing}


def measure_files(paths: Any) -> FrameByteStats:
    """统计帧文件字节数；文件缺失只计数、不抛错（清理过的目录不该让指标失败）。"""
    from pathlib import Path

    stats = FrameByteStats()
    for item in paths or []:
        try:
            path = Path(item)
            if not path.is_file():
                stats.missing += 1
                continue
            stats.files += 1
            stats.bytes += int(path.stat().st_size)
            stats.paths.append(str(path))
        except OSError:
            stats.missing += 1
    return stats
