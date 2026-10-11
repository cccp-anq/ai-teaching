"""提供方（provider）：真实处理路径与合成 fixture 路径。

两条路径严格分离，绝不互相伪装：

- `RealProvider`：受控下载 → 核验 → 抽帧 → 视觉判定 → 标准 payload。缺少凭证/工具时
  **显式失败**（`VISION_CREDENTIAL_MISSING` / `FFMPEG_MISSING`），不会退化成假数字。
- `FixtureProvider`：只输出**人工合成**观测，用于主项目 M2/M3 在无真实媒体与无凭证情况下的
  结构联调；必须是部署显式开启（`ENABLE_FIXTURE_PROVIDER=true`），且只接受已登记的演示身份
  （`session-demo-001` / `media-demo-001`，hash 为 64 个 a），对任何真实课堂身份一律拒绝。

契约联调矩阵（fixture 说明）要求「模型超时/限流、伪造数字、不存在引用」都必须显式失败；
因此合成数据永远不会在真实身份上产生。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from . import definitions, wire
from .analyze import build_analyzer
from .config import Config
from .pipeline import JobRunResult, WindowOutcome, compute_stats, run_job
from .frames import FramePlan, plan_frames
from .wire import TaskError

# fixture 固定数值（与主项目 expected-statistics.json 完全一致）：
# 60s 媒体、6×10s 窗口、rate 依次 0.75 / 0.60 / 0 / null / null / 0.50
DEMO_WINDOW_MS = 10_000
DEMO_DURATION_MS = 60_000
DEMO_COUNTS: list[tuple[int, int] | None] = [(30, 40), (24, 40), (0, 40), (0, 0), None, (20, 40)]
DEMO_RATIOS: list[float | None] = [0.75, 0.60, 0.0, None, None, 0.50]


@dataclass
class ProviderResult:
    payload: dict[str, Any]
    stats: dict[str, Any]
    fixture_observations: bool
    warnings: list[str] = field(default_factory=list)
    reports: list[dict[str, Any]] = field(default_factory=list)
    extra: dict[str, Any] = field(default_factory=dict)


class Provider(Protocol):
    provider_id: str
    version: str

    def run(self, request: wire.JobRequest, *, source_url: str | None,
            job_id: str | None = None,
            stage_callback: Any = None,
            metrics: Any = None) -> ProviderResult:  # pragma: no cover
        ...


# --------------------------------------------------------------------------
# 真实路径
# --------------------------------------------------------------------------

class RealProvider:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.checkpoint = None
        self.provider_id = cfg.provider_id
        self.version = cfg.module_version

    def run(self, request: wire.JobRequest, *, source_url: str | None,
            job_id: str | None = None, stage_callback: Any = None,
            metrics: Any = None) -> ProviderResult:
        if not source_url:
            raise TaskError("MEDIA_URL_EXPIRED", "任务缺少受控下载地址，请用同一 clientRequestId 重提以续期")
        analyzer = build_analyzer(self.cfg)
        result: JobRunResult = run_job(
            request=request,
            cfg=self.cfg,
            analyzer=analyzer,
            checkpoint=self.checkpoint,
            work_root=Path(self.cfg.work_dir) / "jobs",
            definition_text=definitions.text_for(request.parameters.definition_id)
            or self.cfg.definition_text,
            job_id=job_id,
            stage_callback=stage_callback,
            metrics=metrics,
        )
        reports = [dict(item) for item in result.reports]
        for item in reports:
            item["analyzer"] = item.get("analyzer") or {"name": analyzer.name,
                                                       "version": analyzer.version}
        return ProviderResult(
            payload=result.payload,
            stats=result.stats,
            fixture_observations=False,
            warnings=result.warnings,
            reports=reports,
            extra={
                "analyzer": {"name": analyzer.name, "version": analyzer.version},
                "visionModel": self.cfg.vision_model if analyzer.name == "qwen-vl" else None,
                "frameMaxWidth": self.cfg.frame_max_width,
                "tileGrid": self.cfg.frame_tile_grid,
            },
        )


# --------------------------------------------------------------------------
# 合成路径（仅显式开启 + 已登记演示身份）
# --------------------------------------------------------------------------

class FixtureProvider:
    """确定性合成观测；不下载媒体、不调用模型。"""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.provider_id = cfg.fixture_provider_id
        self.version = cfg.fixture_provider_version

    def accepts(self, request: wire.JobRequest) -> bool:
        return (
            self.cfg.enable_fixture_provider
            and request.class_session_id == self.cfg.fixture_demo_class_session_id
            and request.media_id == self.cfg.fixture_demo_media_id
            and request.media.sha256 == self.cfg.fixture_demo_sha256
        )

    def run(self, request: wire.JobRequest, *, source_url: str | None,
            job_id: str | None = None, stage_callback: Any = None,
            metrics: Any = None) -> ProviderResult:
        del source_url, job_id
        if metrics is not None:
            metrics.note("syntheticObservations", True)
        if stage_callback is not None:
            stage_callback("AGGREGATING", False)
        if not self.cfg.enable_fixture_provider:
            raise TaskError("UNSUPPORTED_MEDIA",
                            "本部署未开启合成 provider（ENABLE_FIXTURE_PROVIDER=true 才可用）")
        if not self.accepts(request):
            raise TaskError(
                "UNSUPPORTED_MEDIA",
                "合成 provider 只接受已登记的演示身份（session-demo-001/media-demo-001/64×a），"
                "拒绝在真实课堂身份上产生合成数据")

        value_mode = request.parameters.output_mode
        window_ms = request.parameters.window_ms
        plans = plan_frames(start_ms=request.analysis_range_start_ms,
                            end_ms=request.analysis_range_end_ms,
                            window_ms=window_ms, position="middle", offset_ms=0)
        demo_shape = (window_ms == DEMO_WINDOW_MS
                      and request.duration_ms == DEMO_DURATION_MS
                      and request.analysis_range_start_ms == 0
                      and request.analysis_range_end_ms == DEMO_DURATION_MS
                      and len(plans) == len(DEMO_COUNTS))
        if demo_shape:
            observations = list(DEMO_COUNTS)
            demo_ratios = list(DEMO_RATIOS)
        else:
            observations = _synthetic_counts(len(plans))
            demo_ratios = [
                (None if item is None else (item[0] / item[1] if item[1] else None))
                for item in observations
            ]

        definition_id = request.parameters.definition_id
        definition_text = definitions.text_for(definition_id)
        if definition_text is None:
            # 合成路径只允许已登记口径，避免用未登记口径产出可被误读的结果。
            raise TaskError("UNSUPPORTED_MEDIA",
                            f"合成 provider 不支持未登记口径 {definition_id!r}；"
                            f"可用口径: {', '.join(sorted(definitions.DEFINITIONS))}")

        outcomes: list[WindowOutcome] = []
        reports: list[dict[str, Any]] = []
        for index, plan in enumerate(plans):
            item = observations[index]
            ratio = demo_ratios[index] if index < len(demo_ratios) else None
            if item is None:
                outcome = WindowOutcome(plan=plan, quality=wire.QUALITY_LOW,
                                        quality_reason="OCCLUDED", head_up=None, observed=None)
            elif item[1] == 0:
                outcome = WindowOutcome(plan=plan, quality=wire.QUALITY_NO_SUBJECTS,
                                        quality_reason="NO_OBSERVABLE_SUBJECTS", head_up=0,
                                        observed=0)
            else:
                outcome = WindowOutcome(plan=plan, quality=wire.QUALITY_VALID, head_up=item[0],
                                        observed=item[1])
            if value_mode == wire.VALUE_MODE_RATIO_ONLY:
                # 比例模式不允许出现人数；VALID 窗口给固定比例。
                if outcome.quality == wire.QUALITY_VALID:
                    outcome.head_up = None
                    outcome.observed = None
                    outcome.report = {"demRatio": ratio}
            outcomes.append(outcome)
            reports.append({
                "windowId": plan.window_id,
                "sampleAtMs": plan.sample_at_ms,
                "quality": outcome.quality,
                "qualityReason": outcome.quality_reason,
                "fixture": True,
                "note": "人工合成观测，不是模型对真实媒体的处理结果",
            })

        warnings: list[str] = []
        windows = []
        for index, outcome in enumerate(outcomes):
            row = {
                "id": outcome.plan.window_id,
                "startMs": outcome.plan.window_start_ms,
                "endMs": outcome.plan.window_end_ms,
                "sampleAtMs": (None if outcome.quality == wire.QUALITY_LOW
                               else outcome.plan.sample_at_ms),
                "quality": outcome.quality,
                "qualityReason": outcome.quality_reason,
                "headUpCount": None,
                "validObservedCount": None,
                "headUpRatio": None,
            }
            if value_mode == wire.VALUE_MODE_COUNTS:
                if outcome.quality == wire.QUALITY_VALID:
                    row["headUpCount"] = outcome.head_up
                    row["validObservedCount"] = outcome.observed
                elif outcome.quality == wire.QUALITY_NO_SUBJECTS:
                    row["headUpCount"] = 0
                    row["validObservedCount"] = 0
            else:
                if outcome.quality == wire.QUALITY_VALID:
                    row["headUpRatio"] = demo_ratios[index]
            windows.append(row)

        warnings: list[str] = []
        if not demo_shape and any(item.quality == wire.QUALITY_LOW for item in outcomes):
            # 实现细节告警：主项目不应据此做业务判断（质量状态看每个 window.quality）。
            warnings.append("LOW_QUALITY_WINDOWS")
        payload = {
            "definitionId": definition_id,
            "denominatorDefinition": definition_text,
            "sampleMode": wire.SAMPLE_MODE,
            "valueMode": value_mode,
            "windowMs": window_ms,
            "processedRanges": [{"startMs": request.analysis_range_start_ms,
                                 "endMs": request.analysis_range_end_ms}],
            "windows": windows,
            "warnings": warnings,
        }
        wire.validate_windows(windows,
                              bounds=(request.analysis_range_start_ms,
                                      request.analysis_range_end_ms),
                              window_ms=window_ms,
                              processed=[(request.analysis_range_start_ms,
                                          request.analysis_range_end_ms)],
                              value_mode=value_mode)
        stats = compute_stats(payload, request.duration_ms)
        return ProviderResult(payload=payload, stats=stats, fixture_observations=True,
                              warnings=warnings, reports=reports,
                              extra={"synthetic": True, "provider": "fixture"})


def _synthetic_counts(window_count: int) -> list[tuple[int, int] | None]:
    """非演示形状时的确定性合成计数（仅供结构演练）。"""
    rows: list[tuple[int, int] | None] = []
    for index in range(window_count):
        position = index % 6
        if position == 3:
            rows.append((0, 0))
        elif position == 4:
            rows.append(None)
        else:
            denominator = 40
            head_up = (30, 24, 0, 20)[position if position < 3 else 3]
            rows.append((head_up, denominator))
    return rows


def build_provider(cfg: Config, request: wire.JobRequest) -> Provider:
    """选择处理路径：已登记演示身份 + 显式开启 => 合成；否则真实路径。"""
    fixture = FixtureProvider(cfg)
    if fixture.accepts(request):
        return fixture
    return RealProvider(cfg)
