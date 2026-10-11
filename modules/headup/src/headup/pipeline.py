"""处理流水线：代表帧 → 抬头判定 → 窗口质量分类 → 标准 payload。

窗口质量分类（抬头专用文档 §3，三态不可混用）：

| 情况 | quality | 计数 | 说明 |
| --- | --- | --- | --- |
| 抽帧失败 / 画面不可判读 / 可判断比例低于阈值 | `LOW_QUALITY` | 两数 null | 不能假定无人 |
| 画面中没有人（或全部不可判断且模型明确上报无人） | `NO_SUBJECTS` | 固定 0/0 | rate=null |
| 人数可判断 | `VALID` | `0 <= headUpCount <= validObservedCount`，分母 > 0 | 0/N 是真实 0 |

`validObservedCount = head_up + head_down`；`unknown`（侧背对、遮挡、糊）不进分母。
"""

from __future__ import annotations

import logging
import hashlib
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from . import counts as counts_mod
from . import media as media_mod
from . import perf
from . import wire
from .analyze import FrameAnalyzer, FrameReading
from .config import Config
from .frames import ExtractedFrame, FrameExtractionError, FramePlan, extract_planned_frames, plan_frames
from .media import DownloadResult, FfmpegTools, MediaError, download_media, probe_media, verify_hash
from .video.frame_extractor import (FrameExtractionFailure, FrameExtractor,  # noqa: F401
                                    verify_batch_equivalence)
from .video.sampling import SamplingPolicy, build_sampling_plan, plan_frame_plans
from .video.workspace import FrameWorkspaceService, TaskWorkspace
from .analysis.quality import (REASON_ANALYSIS_FAILED, REASON_COUNTS_INCONSISTENT,
                               REASON_COVERAGE_BELOW_REVIEW, REASON_COVERAGE_REVIEW_BAND,
                               REASON_FRAME_EXTRACTION_FAILED, REASON_NO_VISIBLE_PERSON,
                               REASON_REVIEW_CONFIRMED_VALID, REASON_REVIEW_INSUFFICIENT,
                               REASON_UNREADABLE, REVIEW_OUTCOME_CONFIRMED_VALID,
                               REVIEW_OUTCOME_INSUFFICIENT, REVIEW_OUTCOME_NOT_REVIEWED,
                               REVIEW_REASON_CONFIG, REVIEW_REASON_QUALITY,
                               REVIEW_REASON_SUDDEN_CHANGE, REVIEW_REASON_UNKNOWN_HIGH,
                               QualityDecision, classify_window, finalize_review,
                               finalize_review_observation, needs_review, select_estimate)
from .wire import TaskError

LOG = logging.getLogger("headup.pipeline")

WARN_PARTIAL = "PARTIAL_PROCESSING"
WARN_NO_SUBJECTS = "NO_SUBJECTS"
WARN_LOW_QUALITY = "LOW_QUALITY_WINDOWS"
WARN_DURATION = "DURATION_MISMATCH"
WARN_FFMPEG = "SAMPLE_FRAME_FALLBACK"
WARN_REVIEWED = "MULTI_FRAME_REVIEW_APPLIED"
WARN_FALLBACK_MODEL = "FALLBACK_MODEL_USED"
WARN_BATCH_EXTRACTION = "BATCH_EXTRACTION_USED"
WARN_BATCH_FALLBACK = "BATCH_EXTRACTION_FALLBACK"

# ---- FRAME-5：任务阶段（plan §18）----------------------------------------
# 对外仍只暴露契约的 QUEUED/RUNNING/SUCCEEDED/FAILED，阶段是内部进度（诊断与页面）。
STAGE_PENDING = "PENDING"
STAGE_PROBING_VIDEO = "PROBING_VIDEO"
STAGE_EXTRACTING_PRIMARY = "EXTRACTING_PRIMARY"
STAGE_ANALYZING_PRIMARY = "ANALYZING_PRIMARY"
STAGE_REVIEWING = "REVIEWING"
STAGE_AGGREGATING = "AGGREGATING"
STAGE_SUCCESS = "SUCCESS"
STAGE_FAILED = "FAILED"

#: 错误码 → 失败阶段（plan §18 的 error_stage）
_ERROR_STAGE_BY_CODE = {
    "FFMPEG_MISSING": STAGE_PROBING_VIDEO,
    "DECODE_FAILED": STAGE_PROBING_VIDEO,
    "UNSUPPORTED_MEDIA": STAGE_PROBING_VIDEO,
    "MEDIA_HASH_MISMATCH": STAGE_PROBING_VIDEO,
    "MEDIA_URL_EXPIRED": STAGE_PROBING_VIDEO,
    "PAYLOAD_TOO_LARGE": STAGE_PROBING_VIDEO,
    "VISION_PROVIDER_ERROR": STAGE_ANALYZING_PRIMARY,
    "VISION_CREDENTIAL_MISSING": STAGE_ANALYZING_PRIMARY,
    "PROCESSING_FAILED": STAGE_AGGREGATING,
}


def _stage_for_error(code: str) -> str:
    return _ERROR_STAGE_BY_CODE.get(code, STAGE_FAILED)


@dataclass
class WindowOutcome:
    plan: FramePlan
    quality: str
    quality_reason: str | None = None
    head_up: int | None = None
    observed: int | None = None
    visible: int | None = None
    report: dict[str, Any] = field(default_factory=dict)
    # v0.2 Round 1：程序计算的比率（进 payload 的增量字段，见 _window_dict）
    head_up_rate: float | None = None
    coverage: float | None = None
    # v0.2 Round 2：三档状态（VALID / REVIEW / NO_DATA / UNREADABLE）
    quality_status: str | None = None
    # v0.2 Round 3：复核证据（是否复核、为什么、逐帧计数、结论）
    review: dict[str, Any] = field(default_factory=dict)


@dataclass
class JobRunResult:
    payload: dict[str, Any]
    warnings: list[str]
    windows: list[WindowOutcome]
    reports: list[dict[str, Any]]
    frames: list[Path]
    work_dir: Path
    stats: dict[str, Any]
    # FRAME-6：本次运行的性能指标（实测），不进契约 payload
    metrics: dict[str, Any] | None = None


# --------------------------------------------------------------------------
# 分类
# --------------------------------------------------------------------------

def classify_reading(reading: FrameReading, *, min_legible_ratio: float) -> tuple[str, str | None, int | None, int | None]:
    """旧版（v0.1）二档分类：仅按"可判断比例"阈值切 VALID / LOW_QUALITY。

    保留用于向后兼容与对照（v0.2 起正式路径使用 `classify_window` 三档）。
    """
    observed = max(0, int(reading.head_up) + int(reading.head_down))
    if observed == 0:
        if int(reading.unknown) == 0:
            return wire.QUALITY_NO_SUBJECTS, "NO_OBSERVABLE_SUBJECTS", 0, 0
        return wire.QUALITY_LOW, "NO_LEGIBLE_SUBJECTS", None, None
    if float(reading.legible_ratio) < float(min_legible_ratio):
        return wire.QUALITY_LOW, "LOW_LEGIBLE_RATIO", None, None
    head_up = max(0, min(observed, int(reading.head_up)))
    return wire.QUALITY_VALID, None, head_up, observed


def _window_dict(outcome: WindowOutcome, value_mode: str) -> dict[str, Any]:
    plan = outcome.plan
    if outcome.quality_reason == "FRAME_EXTRACTION_FAILED":
        # 契约：抽不到帧的窗口没有采样点，只能是 LOW_QUALITY + null。
        sample: int | None = None
    else:
        sample = min(max(plan.sample_at_ms, plan.window_start_ms), plan.window_end_ms - 1)
    row: dict[str, Any] = {
        "id": plan.window_id,
        "startMs": plan.window_start_ms,
        "endMs": plan.window_end_ms,
        "sampleAtMs": sample,
        "quality": outcome.quality,
        "qualityReason": outcome.quality_reason,
        "headUpCount": None,
        "validObservedCount": None,
        "visibleStudentCount": None,
        "headUpRatio": None,
    }
    report_counts = (outcome.report or {}).get("counts") or {}
    if outcome.quality in (wire.QUALITY_VALID, wire.QUALITY_REVIEW):
        visible_students = outcome.visible
        if not isinstance(visible_students, int) or isinstance(visible_students, bool):
            visible_students = report_counts.get("visibleCount")
        if isinstance(visible_students, int) and not isinstance(visible_students, bool):
            row["visibleStudentCount"] = visible_students
    elif outcome.quality == wire.QUALITY_NO_SUBJECTS:
        row["visibleStudentCount"] = 0
    if value_mode == wire.VALUE_MODE_COUNTS:
        if outcome.quality in (wire.QUALITY_VALID, wire.QUALITY_REVIEW):
            # REVIEW 也带人数：它只是"可观测性不足"，不是"没有数据"（Round 2）
            row["headUpCount"] = outcome.head_up
            row["validObservedCount"] = outcome.observed
        elif outcome.quality == wire.QUALITY_NO_SUBJECTS:
            row["headUpCount"] = 0
            row["validObservedCount"] = 0
    else:
        if outcome.quality in (wire.QUALITY_VALID, wire.QUALITY_REVIEW) and outcome.observed:
            row["headUpRatio"] = round(outcome.head_up / outcome.observed, 6)
    # v0.2 Round 1/2 增量字段：程序计算的比率与稳定性状态。
    # 契约 v0.1 未定义这些字段，主项目可忽略（向后兼容）。
    rate = outcome.head_up_rate
    if rate is None and value_mode == wire.VALUE_MODE_COUNTS and outcome.observed:
        rate = outcome.head_up / outcome.observed
    if rate is None and value_mode == wire.VALUE_MODE_RATIO_ONLY:
        rate = row["headUpRatio"]
    has_counts = outcome.quality in (wire.QUALITY_VALID, wire.QUALITY_REVIEW)
    row["headUpRate"] = round(float(rate), 6) if (has_counts and rate is not None) else None
    row["coverage"] = None if outcome.coverage is None else round(float(outcome.coverage), 6)
    row["qualityStatus"] = outcome.quality_status
    # Round 4：逐窗记录判定模型与请求 id（主项目据此确认曲线是否混入了换模型的窗口）
    row["modelUsed"] = (outcome.report or {}).get("modelUsed")
    row["modelRequestId"] = (outcome.report or {}).get("modelRequestId")
    # v0.2 Round 3 增量字段：区分"单帧结果"与"窗口估计结果"，并保留复核证据链
    review = outcome.review or {}
    row["resultKind"] = ("WINDOW_ESTIMATE_MULTI_FRAME" if review.get("needed")
                         else "SINGLE_FRAME")
    if review.get("needed"):
        row["review"] = {
            "reasons": review.get("reasons", []),
            "outcome": review.get("outcome"),
            "strategy": review.get("strategy"),
            "frameCount": review.get("frameCount"),
            "medianHeadUpRate": review.get("medianHeadUpRate"),
            "medianCoverage": review.get("medianCoverage"),
            "observation": review.get("observation"),
            "perFrameCounts": review.get("perFrame") or [
                {k: frame.get(k) for k in ("visible_count", "evaluable_count", "head_up_count",
                                           "head_down_count", "unknown_count",
                                           "head_up_rate", "coverage")}
                for frame in review.get("perFrame", [])
            ],
        }
        if review.get("note"):
            row["review"]["note"] = review["note"]
    return row


# --------------------------------------------------------------------------
# 真实处理
# --------------------------------------------------------------------------

def run_job(*, request: wire.JobRequest, cfg: Config, analyzer: FrameAnalyzer,
            work_root: Path, local_media: Path | None = None,
            definition_text: str | None = None, job_id: str | None = None,
            stage_callback: Callable[[str, bool], None] | None = None,
            metrics: perf.RunMetrics | None = None, checkpoint=None) -> JobRunResult:
    """跑一遍真实处理。

    - `local_media` 仅供 CLI/测试使用本地文件（跳过受控下载与 hash 核验）；
      契约路径（服务端）永远走 `download_media` + `verify_hash`。
    - `job_id` / `stage_callback` 用于 FRAME-5 的任务阶段上报（plan §18）：
      回调收到 (stage, is_error)，由调用方决定落库还是记日志。
    - `metrics`（FRAME-6）收集实测性能指标；未传入时按 `cfg.perf_metrics_enabled` 新建，
      调用方可以从 `JobRunResult.metrics` 取走（关闭时该字段为 None）。
    """
    metrics = metrics if metrics is not None else perf.RunMetrics(enabled=cfg.perf_metrics_enabled)
    raw_analyzer = analyzer
    cloud_batcher = None
    if checkpoint is not None: analyzer = checkpoint.journal_analyzer(analyzer)
    analyzer = _counted_analyzer(analyzer, metrics)
    work_dir = work_root / safe_work_key(request.client_request_id)
    work_dir.mkdir(parents=True, exist_ok=True)
    probe_warning: str | None = None
    reports: list[dict[str, Any]] = []
    media_path: Path | None = None
    downloaded: DownloadResult | None = None
    workspace: TaskWorkspace | None = None
    workspace_record: dict[str, Any] | None = None
    run_result: JobRunResult | None = None
    succeeded = False
    error_stage: str | None = None

    def report_stage(stage: str, *, error: bool = False) -> None:
        if stage_callback is not None:
            try:
                stage_callback(stage, error)
            except Exception:  # pragma: no cover - 阶段上报绝不能影响处理
                LOG.warning("阶段上报失败 stage=%s", stage)

    try:
        if local_media is not None:
            media_path = Path(local_media)
            metrics.note("mediaSource", "LOCAL_FILE")
        else:
            with metrics.stage(perf.STAGE_DOWNLOAD):
                downloaded = download_media(request.media.source_url, cfg, work_dir)
                verify_hash(downloaded, request.media.sha256)
            media_path = downloaded.path
            metrics.note("mediaSource", "CONTROLLED_DOWNLOAD")
            try:
                metrics.add_disk(perf.DISK_MEDIA_BYTES, downloaded.path.stat().st_size)
            except OSError:  # pragma: no cover - 统计失败不影响处理
                pass

        tools = FfmpegTools.locate(cfg)
        if not tools.available:
            raise TaskError("FFMPEG_MISSING", tools.detail)
        report_stage(STAGE_PROBING_VIDEO)
        with metrics.stage(STAGE_PROBING_VIDEO):
            probe = probe_media(tools, media_path, cfg)
        # 只记录探测到的字段：`probe_media` 返回 duration/width/height（不含 fps/codec，
        # 需要更细的元数据时用 `video.probe.probe_video`，见 benchmark 报告）
        metrics.note("mediaMetadata", {"durationMs": probe.duration_ms, "width": probe.width,
                                       "height": probe.height})
        if abs(probe.duration_ms - request.duration_ms) > cfg.duration_tolerance_ms:
            probe_warning = (f"{WARN_DURATION}:registered={request.duration_ms},"
                             f"actual={probe.duration_ms}")

        # FRAME-3：采样网格唯一来源 = SamplingPolicy（FFmpeg 与 pipeline 都不得自行定义网格）
        policy = SamplingPolicy(window_ms=request.parameters.window_ms,
                               position=cfg.sample_position,
                               start_offset_ms=cfg.sampling_start_offset_ms,
                               end_offset_ms=cfg.sampling_end_offset_ms)
        sampling_plan = build_sampling_plan(
            policy, analysis_start_ms=request.analysis_range_start_ms,
            analysis_end_ms=request.analysis_range_end_ms, duration_ms=probe.duration_ms)
        if not sampling_plan.points:
            raise TaskError("PROCESSING_FAILED",
                            "采样策略未产生任何采样点（检查 offset 与 analysisRange）")
        plans = plan_frame_plans(policy, start_ms=request.analysis_range_start_ms,
                                 end_ms=request.analysis_range_end_ms)
        # offset 只过滤、不平移：按采样点保留对应窗口计划
        kept_ids = {point.window_id for point in sampling_plan.points}
        plans = [plan for plan in plans if plan.window_id in kept_ids]
        metrics.note("sampleCount", len(sampling_plan.points))
        metrics.note("samplingPolicyVersion", sampling_plan.policy_version)

        # FRAME-5：任务工作目录（primary / review 分目录，成功即删、失败留 TTL）
        workspace = FrameWorkspaceService(
            Path(cfg.work_dir) / "tasks",
            failed_retention_hours=cfg.failed_task_retention_hours,
            keep_on_success=cfg.frames_retain > 0,
            retain_count=cfg.frames_retain).create(job_id or safe_work_key(request.client_request_id),
            classSessionId=request.class_session_id, mediaId=request.media_id,
            mediaSha256=request.media.sha256, windowMs=request.parameters.window_ms,
            samplingPolicyVersion=sampling_plan.policy_version)
        frames_dir = workspace.primary_dir
        metrics.note("workspaceTaskId", workspace.task_id)
        # 采样策略驱动实际抽帧（FRAME-5）：优先一次批量抽帧，失败或关闭时逐窗 seek
        report_stage(STAGE_EXTRACTING_PRIMARY)
        primary_frames = _extract_primary_frames(
            tools=tools, media_path=media_path, plans=plans, workspace=workspace,
            cfg=cfg, media_duration_ms=probe.duration_ms, metrics=metrics)
        report_stage(STAGE_ANALYZING_PRIMARY)
        outcomes: list[WindowOutcome] = []
        frame_paths: list[Path] = []
        previous_valid_rate: float | None = None
        checkpoint_signature = None
        completed = 0
        if checkpoint is not None:
            from dataclasses import asdict
            from .recovery import signature
            checkpoint_signature = signature(request,cfg,plans)
            saved = checkpoint.load(checkpoint_signature)
            if saved:
                completed = saved['nextIndex']
                outcomes = [WindowOutcome(plan=FramePlan(**row.pop('plan')),**row) for row in saved['outcomes']]
                previous_valid_rate = saved['previousValidRate']
                reports.extend(saved['reports'])
                analyzer.restore(saved.get('usedModels',[]),saved.get('lastTrace',{}),saved.get('analyzerState',{}))
                if completed != len(outcomes) or [x.plan.window_id for x in outcomes] != [x.window_id for x in plans[:completed]]: raise TaskError('PROCESSING_FAILED','检查点前缀不匹配')
        execution_concurrency = min(4, cfg.analysis_concurrency) if callable(getattr(raw_analyzer, 'fork', None)) else 1
        # A recovered task retains its original execution setting/signature.
        if checkpoint is not None and saved:
            execution_concurrency = saved.get('executionConcurrency', 1)
        progress_started = saved.get('processingStartedAt', time.time()) if checkpoint is not None and saved else time.time()
        def save_prefix(next_index):
            if checkpoint is not None:
                checkpoint.check()
                checkpoint.save({'signature':checkpoint_signature,'nextIndex':next_index,
                    'previousValidRate':previous_valid_rate,'outcomes':[asdict(x) for x in outcomes],
                    'reports':reports,'usedModels':list(getattr(analyzer,'used_models',[]) or []),
                    'lastTrace':dict(getattr(analyzer,'last_trace',{}) or {}),
                    'analyzerState':{k:getattr(analyzer,k) for k in ('request_count','token_totals','request_notes') if hasattr(analyzer,k)},
                    'totalWindows':len(plans),'executionConcurrency':execution_concurrency,
                    'effectiveConcurrency':cloud_batcher.gate.limit if cloud_batcher else 1,
                    'processingStartedAt':progress_started,
                    **({'processingFinishedAt':time.time()} if next_index==len(plans) else {})})
        if execution_concurrency > 1:
            from .cloud import CloudBatcher, absorb_primary
            cloud_batcher = CloudBatcher(raw_analyzer, execution_concurrency, checkpoint)
            ordered = cloud_batcher.batches(plans, primary_frames, completed)
        else:
            ordered = ((index, plan, None) for index, plan in enumerate(plans) if index >= completed)
        save_prefix(completed)
        metrics.note('executionStrategy', 'cloud-primary-batch-ordered-review-v1' if cloud_batcher else 'serial')
        metrics.note('executionConcurrency', execution_concurrency)
        for index, plan, primary_result in ordered:
            if checkpoint is not None:
                checkpoint.window_start(plan.window_id)
                if primary_result is not None: checkpoint.ordinal = 1
            if primary_result is not None:
                absorb_primary(raw_analyzer, primary_result, metrics)
            if (previous_valid_rate is not None
                    and any(plan.window_id == item.plan.window_id and "review" in item.report
                            for item in outcomes)):
                report_stage(STAGE_REVIEWING)
            outcome = _process_window(
                tools, media_path, plan, frames_dir, cfg, analyzer, reports,
                previous_valid_rate=previous_valid_rate,
                media_duration_ms=probe.duration_ms,
                primary_frame=primary_frames.get(plan.window_id),
                review_dir=workspace.review_dir_for(plan.sample_at_ms),
                metrics=metrics,
                primary_result=primary_result, stage_callback=report_stage)
            outcomes.append(outcome)
            if outcome.quality == wire.QUALITY_VALID and outcome.head_up_rate is not None:
                previous_valid_rate = outcome.head_up_rate
            save_prefix(index+1)
            if outcome.report.get("frame"):
                frame_paths.append(Path(outcome.report["frame"]))
        metrics.bump(perf.COUNTER_WINDOWS_TOTAL, len(outcomes))
        if cloud_batcher and hasattr(raw_analyzer, 'token_totals') and metrics.enabled:
            metrics.tokens.update(raw_analyzer.token_totals)

        if all(item.quality == wire.QUALITY_LOW for item in outcomes):
            # 契约：没有任何成功处理区间应 FAILED，不造一串 0。
            raise TaskError("PROCESSING_FAILED",
                            "全部窗口都无法得到可判断的观测（抽帧失败或画面不可判读）")

        report_stage(STAGE_AGGREGATING)
        with metrics.stage(STAGE_AGGREGATING):
            payload = assemble_payload(request, outcomes, cfg,
                                       definition_text=definition_text or cfg.definition_text)
            # FRAME-3：把采样策略与网格证据写进结果（可追溯"这些时间点是怎么来的"）
            generation_extra = payload.get("generation") or {}
            generation_extra["samplingPlan"] = sampling_plan.to_dict()
            generation_extra["samplingPolicyVersion"] = sampling_plan.policy_version
            payload["generation"] = generation_extra
            if probe_warning:
                payload["warnings"].append(probe_warning)
            # Round 4：把"实际用了哪些模型/最后一次请求的追溯信息"带回运行结果
            generation = payload.get("generation") or {}
            generation["executionStrategy"] = metrics.notes.get("executionStrategy", "serial")
            generation["executionConcurrency"] = execution_concurrency
            generation["analysisRequests"] = {
                "modelsUsed": list(getattr(analyzer, "used_models", []) or []),
                "lastRequestTrace": dict(getattr(analyzer, "last_trace", {}) or {}),
            }
            payload["generation"] = generation
            stats = compute_stats(payload, request.duration_ms)
        # FRAME-5：先把工作目录收尾并写进结果，再返回（return 之后 finally 里的赋值已无效）
        succeeded = True
        with metrics.stage(perf.STAGE_CLEANUP):
            workspace_record = _finish_workspace(cfg, workspace, succeeded=True,
                                                 stage=STAGE_SUCCESS)
        metrics.note("workspace", workspace_record)
        metrics.note("analyzer", {"name": getattr(analyzer, "name", None),
                                  "version": getattr(analyzer, "version", None)})
        payload["generation"]["workspace"] = workspace_record
        run_result = JobRunResult(payload=payload, warnings=payload["warnings"], windows=outcomes,
                                  reports=reports, frames=frame_paths, work_dir=workspace.root,
                                  stats=stats,
                                  metrics=perf.metrics_to_dict(
                                      metrics, cost=perf.estimate_cost(cfg, metrics)))
        report_stage(STAGE_SUCCESS)
    except TaskError as exc:
        from .recovery import BudgetStop
        if isinstance(exc,BudgetStop): raise
        error_stage = exc.code
        metrics.note("errorCode", exc.code)
        metrics.note("errorStage", _stage_for_error(exc.code))
        if workspace is not None:
            report_stage(_stage_for_error(exc.code), error=True)
        _finish_workspace(cfg, workspace, succeeded=False, stage=error_stage)
        raise
    except Exception:
        metrics.note("errorCode", "INTERNAL_ERROR")
        if workspace is not None:
            _finish_workspace(cfg, workspace, succeeded=False, stage="INTERNAL_ERROR")
        raise
    finally:
        if cloud_batcher is not None:
            cloud_batcher.close()
        if downloaded is not None and not cfg.keep_media_files:
            downloaded.path.unlink(missing_ok=True)
    assert run_result is not None
    return run_result


def _counted_analyzer(analyzer: FrameAnalyzer, metrics: perf.RunMetrics) -> FrameAnalyzer:
    """按需把分析器包一层计数（不改变判定行为；关闭指标时原样返回）。"""
    if not metrics.enabled or isinstance(analyzer, perf.CountingAnalyzer):
        return analyzer
    return perf.CountingAnalyzer(analyzer, metrics)


def _finish_workspace(cfg: Config, workspace: TaskWorkspace | None, *,
                      succeeded: bool, stage: str | None) -> dict[str, Any] | None:
    """收尾任务工作目录：成功即删、失败按 TTL 保留（plan §16）。"""
    if workspace is None:
        return None
    try:
        return workspace_service_for(cfg).finish(workspace, succeeded=succeeded, stage=stage)
    except Exception:  # pragma: no cover - 清理失败不能影响任务结果
        LOG.warning("工作目录收尾失败 taskId=%s", workspace.task_id)
        return {"taskId": workspace.task_id, "succeeded": succeeded, "cleanupFailed": True}


def workspace_service_for(cfg: Config) -> FrameWorkspaceService:
    """按配置构造工作目录服务（供服务层做 TTL 清理）。"""
    return FrameWorkspaceService(
        Path(cfg.work_dir) / "tasks",
        failed_retention_hours=cfg.failed_task_retention_hours,
        keep_on_success=cfg.frames_retain > 0,
        retain_count=cfg.frames_retain)


def safe_work_key(value: str) -> str:
    """Opaque path segment: user supplied request IDs never become filesystem paths."""
    return "request-" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:32]


def _extract_primary_frames(*, tools: FfmpegTools, media_path: Path, plans: list[FramePlan],
                            workspace: TaskWorkspace, cfg: Config,
                            media_duration_ms: int | None,
                            metrics: perf.RunMetrics | None = None) -> dict[str, Path]:
    """代表帧抽取：**采样策略给出时间点**，优先一次批量抽帧，失败/关闭时逐窗 seek。

    - 批量路径必须能与逐点 seek 等价（`video.frame_extractor` 的首帧校验 + 整段回退）；
    - 批量失败不影响任务：回退逐点 seek 并加告警，绝不因为"想省时间"而丢窗口。
    - FRAME-6：抽取方式、帧数与落盘字节全部记账（批量/回退/逐点各自计数）。
    """
    metrics = metrics or perf.null_metrics()
    timestamps = [plan.sample_at_ms for plan in plans]
    if cfg.sampling_use_batch_extraction and len(timestamps) > 1:
        extractor = FrameExtractor(cfg, tools, media_path, duration_ms=media_duration_ms)
        try:
            with metrics.stage(STAGE_EXTRACTING_PRIMARY):
                # FRAME-6：真实网格在"末窗短"时不是等间隔（45 分钟课的最后一点），
                # `extract_grid` 用"等间隔前缀批量 + 尾部少量点逐点"，网格与时间戳不变
                samples = extractor.extract_grid(timestamps, workspace.primary_dir)
            by_timestamp = {sample.timestamp_ms: sample.image_path for sample in samples}
            selected = {plan.window_id: by_timestamp[plan.sample_at_ms]
                        for plan in plans if plan.sample_at_ms in by_timestamp}
            # 批量接口内部可能因为首帧不等价而整段回退逐点 seek —— 回退率必须可见
            mode = getattr(extractor, "last_mode", "BATCH")
            split = getattr(extractor, "last_grid_split", None) or {}
            metrics.note("extractionMode", mode)
            metrics.note("extractionGridSplit", dict(split) or None)
            if getattr(extractor, "last_equivalence", None):
                metrics.note("extractionEquivalence", dict(extractor.last_equivalence))
            seek_frames = int(split.get("seekPoints", 0)) if mode != "SEEK_FALLBACK" else len(selected)
            if mode in ("BATCH", "BATCH+SEEK_TAIL"):
                metrics.bump(perf.COUNTER_EXTRACTION_BATCH_RUNS)
            else:
                metrics.bump(perf.COUNTER_EXTRACTION_BATCH_FALLBACKS)
            metrics.bump(perf.COUNTER_EXTRACTION_SEEK_FRAMES, seek_frames)
            _record_frame_files(metrics, list(selected.values()), review=False)
            return selected
        except (FrameExtractionFailure, MediaError, TaskError) as exc:
            metrics.bump(perf.COUNTER_EXTRACTION_BATCH_FALLBACKS)
            metrics.note("extractionFallbackReason", getattr(exc, "message", str(exc)))
            LOG.warning("批量抽帧失败，回退逐点 seek: %s",
                        getattr(exc, "message", str(exc)))
    metrics.note("extractionMode",
                 "SEEK" if not cfg.sampling_use_batch_extraction else "SEEK_FALLBACK")
    frames: dict[str, Path] = {}
    extracted_paths: list[Path] = []
    with metrics.stage(STAGE_EXTRACTING_PRIMARY):
        for plan in plans:
            frame = extractor_single(cfg, tools, media_path, plan, workspace, media_duration_ms)
            if frame is not None:
                frames[plan.window_id] = frame
                extracted_paths.append(frame)
                metrics.bump(perf.COUNTER_EXTRACTION_SEEK_FRAMES)
    _record_frame_files(metrics, extracted_paths, review=False)
    return frames


def _set_analyzer_phase(analyzer: FrameAnalyzer, phase: str) -> None:
    """标注当前是代表帧判定还是复核（只有计数包装器响应；其它分析器忽略）。"""
    setter = getattr(analyzer, "set_phase", None)
    if callable(setter):
        setter(phase)


def _record_frame_files(metrics: perf.RunMetrics, paths: list[Path], *, review: bool) -> None:
    """把一批帧文件的张数与字节数记账（缺文件只计数，不让统计影响处理）。"""
    stats = perf.measure_files(paths)
    if review:
        metrics.bump(perf.COUNTER_FRAMES_REVIEW, stats.files)
        metrics.add_disk(perf.DISK_REVIEW_FRAME_BYTES, stats.bytes)
    else:
        metrics.bump(perf.COUNTER_FRAMES_PRIMARY, stats.files)
        metrics.add_disk(perf.DISK_PRIMARY_FRAME_BYTES, stats.bytes)
    if stats.missing:
        metrics.bump("frames.missing", stats.missing)


def extractor_single(cfg: Config, tools: FfmpegTools, media_path: Path, plan: FramePlan,
                     workspace: TaskWorkspace, media_duration_ms: int | None) -> Path | None:
    """逐点 seek 抽一帧（失败返回 None，由窗口处理标记该窗不可用）。"""
    extractor = FrameExtractor(cfg, tools, media_path, duration_ms=media_duration_ms)
    try:
        sample = extractor.extract_at(plan.sample_at_ms, workspace.primary_dir)
        if sample.timestamp_ms != plan.sample_at_ms:
            LOG.warning("窗口 %s 无法取得精确采样点，拒绝用 %sms 画面冒充 %sms",
                        plan.window_id, sample.timestamp_ms, plan.sample_at_ms)
            sample.image_path.unlink(missing_ok=True)
            return None
        return sample.image_path
    except (FrameExtractionFailure, MediaError, TaskError) as exc:
        LOG.warning("窗口 %s 抽帧失败: %s", plan.window_id, getattr(exc, "message", str(exc)))
        return None


def _process_window(tools: FfmpegTools, media_path: Path, plan: FramePlan, frames_dir: Path,
                    cfg: Config, analyzer: FrameAnalyzer,
                    reports: list[dict[str, Any]],
                    previous_valid_rate: float | None = None,
                    media_duration_ms: int | None = None,
                    primary_frame: Path | None = None,
                    review_dir: Path | None = None,
                    metrics: perf.RunMetrics | None = None,
                    primary_result=None, stage_callback=None) -> WindowOutcome:
    """处理一个窗口。

    - `primary_frame` 由调用方提供时（批量/逐点预抽帧）不再自己抽帧；
    - `review_dir` 是复核帧的落盘目录（FRAME-5：`review/{centerTimestamp}/`），
      缺省时退回 `frames_dir`（兼容旧调用）；
    - `metrics`（FRAME-6）：只记账，不改变任何判定。
    """
    metrics = metrics or perf.null_metrics()
    if primary_frame is None:
        try:
            extracted: list[ExtractedFrame] = extract_planned_frames(
                tools, media_path, [plan], out_dir=frames_dir, max_width=cfg.frame_max_width,
                cfg=cfg)
        except FrameExtractionError as exc:
            metrics.bump(perf.COUNTER_FRAME_EXTRACTION_FAILURES)
            reports.append({
                "windowId": plan.window_id, "sampleAtMs": plan.sample_at_ms, "frame": None,
                "error": exc.task_error.code, "quality": wire.QUALITY_LOW,
                "qualityReason": "FRAME_EXTRACTION_FAILED",
            })
            return WindowOutcome(plan=plan, quality=wire.QUALITY_LOW,
                                 quality_reason="FRAME_EXTRACTION_FAILED",
                                 report=reports[-1])
        frame = extracted[0]
        _record_frame_files(metrics, [frame.path], review=False)
    else:
        frame = ExtractedFrame(plan=plan, path=Path(primary_frame))
    try:
        if primary_result is not None and isinstance(primary_result.reading, TaskError):
            raise primary_result.reading
        reading = primary_result.reading if primary_result is not None else analyzer.analyze(frame.path)
    except TaskError as exc:
        from .recovery import BudgetStop
        if isinstance(exc,BudgetStop): raise
        if exc.code in ("VISION_CREDENTIAL_MISSING",):
            raise
        metrics.bump(perf.COUNTER_ANALYSIS_FAILURES)
        reports.append({
            "windowId": plan.window_id, "sampleAtMs": plan.sample_at_ms,
            "frame": str(frame.path), "error": exc.code, "quality": wire.QUALITY_LOW,
            "qualityReason": "ANALYSIS_FAILED", "requestStatus": "UNKNOWN_NOT_RESUBMITTED" if type(exc).__name__=="UnknownRequest" else "FAILED",
        })
        return WindowOutcome(plan=plan, quality=wire.QUALITY_LOW, quality_reason="ANALYSIS_FAILED",
                            report=reports[-1])

    # v0.2 Round 2：三档质量（以 coverage 为准）
    decision = classify_window(reading.counts, valid_threshold=cfg.quality_valid_threshold,
                               review_threshold=cfg.quality_review_threshold)
    counts = reading.counts
    # v0.2 Round 3：仅对不稳定窗口做三帧复核（正常窗口仍只调用一次模型）
    review_decision = needs_review(
        counts=counts, coverage=decision.coverage,
        unknown_ratio=counts_mod.unknown_ratio(counts) if counts else None,
        window_rate=decision.head_up_rate, previous_valid_rate=previous_valid_rate,
        cfg=cfg, status=decision.status)
    review_evidence: dict[str, Any] = {
        "needed": review_decision.needed,
        "reasons": list(review_decision.reasons),
        "outcome": REVIEW_OUTCOME_NOT_REVIEWED,
        "samplingPolicyVersion": counts_mod.SAMPLING_POLICY_VERSION,
    }
    review_observation: ReviewObservation | None = None
    if review_decision.needed:
        if stage_callback: stage_callback(STAGE_REVIEWING)
        metrics.bump(perf.COUNTER_WINDOWS_REVIEWED)
        _set_analyzer_phase(analyzer, perf.PHASE_REVIEW)
        try:
            with metrics.stage(STAGE_REVIEWING):
                review_observation = _review_observation(
                    tools=tools, media_path=media_path, plan=plan,
                    frames_dir=review_dir if review_dir is not None else frames_dir,
                    cfg=cfg, analyzer=analyzer, media_duration_ms=media_duration_ms,
                    primary_frame=frame.path if frame is not None else None,
                    metrics=metrics)
        except TaskError as exc:
            from .recovery import UnknownRequest
            if not isinstance(exc, UnknownRequest): raise
            review_evidence['requestStatus'] = 'UNKNOWN_NOT_RESUBMITTED'
            review_evidence['note'] = exc.message
        finally:
            _set_analyzer_phase(analyzer, perf.PHASE_PRIMARY)
            if stage_callback: stage_callback(STAGE_ANALYZING_PRIMARY)
        if review_observation is not None:
            metrics.bump(perf.COUNTER_WINDOWS_REVIEW_APPLIED)
            metrics.bump(f"review.strategy.{review_observation.strategy}")
            # 多图（或合并单帧）结果本身就是一个窗口观测 ⇒ 直接判级（中位数聚合仅用于逐帧路径）
            decision, evidence = finalize_review_observation(
                review_observation.counts,
                valid_threshold=cfg.quality_valid_threshold,
                review_threshold=cfg.quality_review_threshold,
                frame_count=review_observation.frame_count)
            review_evidence.update(evidence)
            review_evidence["strategy"] = review_observation.strategy
            if review_observation.per_frame:
                review_evidence["perFrame"] = review_observation.per_frame
            if review_observation.trace:
                review_evidence["modelTrace"] = review_observation.trace
        else:
            # 相邻帧取不到 ⇒ 不臆测，保持原判（REVIEW 不进均值）
            review_evidence["outcome"] = REVIEW_OUTCOME_INSUFFICIENT
            if review_evidence.get('requestStatus')=='UNKNOWN_NOT_RESUBMITTED':
                # Insufficient review cannot promote a suspect primary window to VALID.
                decision = classify_window(None, valid_threshold=cfg.quality_valid_threshold,
                                           review_threshold=cfg.quality_review_threshold)
            else:
                review_evidence["note"] = "相邻帧抽取失败，保持单帧判定"
        review_evidence["reasons"] = list(review_decision.reasons)
        review_evidence["needed"] = True
    quality = decision.quality
    head_up = decision.counts.head_up_count if decision.counts else None
    observed = decision.counts.evaluable_count if decision.counts else None
    reason = decision.reason
    if review_decision.needed and review_evidence.get("outcome") == REVIEW_OUTCOME_CONFIRMED_VALID:
        reason = REASON_REVIEW_CONFIRMED_VALID
    elif (review_decision.needed
          and review_evidence.get("outcome") == REVIEW_OUTCOME_INSUFFICIENT):
        reason = REASON_REVIEW_INSUFFICIENT
    report = {
        "windowId": plan.window_id,
        "sampleAtMs": plan.sample_at_ms,
        "frame": str(frame.path),
        "quality": quality,
        "qualityReason": reason,
        "qualityStatus": decision.status,
        "modelQuality": decision.model_quality,
        "resultKind": "WINDOW_ESTIMATE" if review_decision.needed else "SINGLE_FRAME",
        "headUpCount": head_up,
        "validObservedCount": observed,
        "unknownCount": reading.unknown,
        "legibleRatio": reading.legible_ratio,
        "modelConfidence": reading.confidence,
        "autoLabeled": False,
        "review": review_evidence,
        "thresholds": {
            "legacyMinLegibleRatio": cfg.min_legible_ratio,
            "coverageValidThreshold": cfg.quality_valid_threshold,
            "coverageReviewThreshold": cfg.quality_review_threshold,
        },
        "analyzer": {"name": analyzer.name, "version": analyzer.version},
        "notes": reading.analysis_notes[:3],
        # v0.2 Round 1：模型原始计数 + 程序计算的比率（可追溯、可复核）
        "counts": {
            "visibleCount": counts.visible_count if counts else None,
            "evaluableCount": counts.evaluable_count if counts else None,
            "headUpCount": counts.head_up_count if counts else None,
            "headDownCount": counts.head_down_count if counts else None,
            "unknownCount": counts.unknown_count if counts else None,
            "strict": counts.strict if counts else None,
        },
        "headUpRate": decision.head_up_rate,
        "coverage": decision.coverage,
        "metricVersion": counts_mod.METRIC_VERSION,
        "promptVersion": getattr(analyzer, "prompt_version", None) or cfg.prompt_version,
        # Round 4：逐窗追溯——本窗由哪个模型判定、该次请求 id（无服务端快照号时以此为准）
        "modelUsed": reading.model_used,
        "modelRequestId": (reading.trace or {}).get("requestId"),
    }
    reports.append(report)
    return WindowOutcome(plan=plan, quality=quality, quality_reason=reason, head_up=head_up,
                         observed=observed,
                         visible=decision.counts.visible_count if decision.counts else None,
                         report=report,
                         head_up_rate=decision.head_up_rate, coverage=decision.coverage,
                         quality_status=decision.status, review=review_evidence)


@dataclass
class ReviewObservation:
    """一次复核的产物：窗口观测（多图或单帧合并）+ 逐帧证据 + 用了什么策略。"""

    counts: counts_mod.WindowCounts
    frame_count: int
    strategy: str                      # MULTI_IMAGE_SINGLE_REQUEST | THREE_SINGLE_FRAME
    per_frame: list[dict[str, Any]] = field(default_factory=list)
    trace: dict[str, Any] = field(default_factory=dict)


def _review_window_centers(cfg: Config, plan: FramePlan,
                           media_duration_ms: int | None) -> list[int]:
    """复核帧的时间点（默认 t±500ms），必须留在本窗口内且不越界。"""
    offset = max(0, int(cfg.review_adjacent_frame_offset_ms))
    if offset == 0:
        return []
    budget = max(1, int(cfg.review_total_max_attempts)) - 1     # 减去代表帧
    upper = (media_duration_ms - 1) if media_duration_ms else None
    candidates: list[int] = []
    for delta in (-offset, offset):
        at = plan.sample_at_ms + delta
        if at < plan.window_start_ms or at >= plan.window_end_ms:
            continue                     # 复核帧必须留在本窗口内，避免污染窗口语义
        if upper is not None:
            at = min(at, upper)
        if at not in candidates:
            candidates.append(at)
    return candidates[:budget]


def _review_observation(*, tools: FfmpegTools, media_path: Path, plan: FramePlan,
                        frames_dir: Path, cfg: Config, analyzer: FrameAnalyzer,
                        media_duration_ms: int | None,
                        primary_frame: Path | None = None,
                        metrics: perf.RunMetrics | None = None) -> ReviewObservation | None:
    """三帧复核（FRAME-4）。

    优先用 `analyze_window([t-0.5s, t, t+0.5s])` —— **一次请求带三张图**（用户决策③）；
    分析器不支持多图时回退为"逐帧单图调用"（兼容旧分析器与离线实现），
    并在证据里注明所用策略，避免两种语义混淆。

    任何一帧抽不到就少一份证据；全部取不到返回 None（调用方保持原判，不臆测）。
    """
    metrics = metrics or perf.null_metrics()
    centers = _review_window_centers(cfg, plan, media_duration_ms)
    if not centers:
        return None
    # 保证恰好是"三帧观察窗口"：t-500 / t / t+500（客户端已判读的代表帧会被重新抽到
    # review 目录，使复核证据自包含、可单独审计）
    window_centers = sorted({plan.sample_at_ms, *centers})
    max_frames = max(1, int(cfg.review_total_max_attempts))
    if len(window_centers) > max_frames:
        middle = plan.sample_at_ms
        others = [value for value in window_centers if value != middle]
        window_centers = sorted([middle, *others[:max_frames - 1]])
    frames: list[Path] = []
    for index, at in enumerate(window_centers):
        sub_plan = FramePlan(window_index=plan.window_index + 1,
                             window_id=f"{plan.window_id}-r{index + 1}",
                             window_start_ms=plan.window_start_ms,
                             window_end_ms=plan.window_end_ms, sample_at_ms=at)
        try:
            extracted = extract_planned_frames(tools, media_path, [sub_plan],
                                               out_dir=frames_dir,
                                               max_width=cfg.frame_max_width, cfg=cfg)
        except FrameExtractionError:
            metrics.bump(perf.COUNTER_FRAME_EXTRACTION_FAILURES)
            continue
        frames.append(extracted[0].path)
    _record_frame_files(metrics, frames, review=True)
    del primary_frame      # 复核帧自包含：三帧都落在 review 目录，便于证据审计

    supports_window = (cfg.review_multi_image
                       and callable(getattr(analyzer, "analyze_window", None)))
    if supports_window and len(frames) >= 2:
        try:
            reading = analyzer.analyze_window(frames)          # type: ignore[attr-defined]
            return ReviewObservation(counts=reading.counts, frame_count=reading.frame_count,
                                     strategy="MULTI_IMAGE_SINGLE_REQUEST",
                                     trace=dict(reading.trace or {}))
        except TaskError as exc:
            from .recovery import BudgetStop, UnknownRequest
            if isinstance(exc,(BudgetStop, UnknownRequest)): raise
            if exc.code == "VISION_CREDENTIAL_MISSING":
                raise
            LOG.warning("多图复核失败，回退逐帧单图调用: %s", exc.message)

    readings: list[counts_mod.WindowCounts] = []
    per_frame: list[dict[str, Any]] = []
    for frame in frames:
        try:
            reading = analyzer.analyze(frame)
        except TaskError as exc:
            from .recovery import BudgetStop, UnknownRequest
            if isinstance(exc,(BudgetStop, UnknownRequest)): raise
            if exc.code == "VISION_CREDENTIAL_MISSING":
                raise
            continue
        readings.append(reading.counts)
        per_frame.append({"frame": str(frame), **reading.counts.to_dict()})
    if not readings:
        return None
    merged = counts_mod.aggregate_counts(readings)
    return ReviewObservation(counts=merged, frame_count=len(readings),
                             strategy="THREE_SINGLE_FRAME", per_frame=per_frame)


def _review_frames(*, tools: FfmpegTools, media_path: Path, plan: FramePlan, frames_dir: Path,
                   cfg: Config, analyzer: FrameAnalyzer,
                   media_duration_ms: int | None) -> list[counts_mod.WindowCounts]:
    """兼容入口：强制走"逐帧单图"回退路径并返回逐帧计数（旧调用方/旧测试使用）。

    正式路径见 `_review_observation()`（优先一次多图请求）。
    """
    legacy_cfg = cfg
    if getattr(cfg, "review_multi_image", False):
        from dataclasses import replace as _replace

        legacy_cfg = _replace(cfg, review_multi_image=False)
    observation = _review_observation(tools=tools, media_path=media_path, plan=plan,
                                      frames_dir=frames_dir, cfg=legacy_cfg,
                                      analyzer=analyzer, media_duration_ms=media_duration_ms)
    if observation is None or observation.strategy == "MULTI_IMAGE_SINGLE_REQUEST":
        return []
    return [counts_mod.make_counts(**{k: v for k, v in item.items()
                                      if k in ("visible_count", "head_up_count",
                                               "head_down_count", "unknown_count")},
                                   strict=True)
            for item in observation.per_frame]


def _generation_policy(cfg: Config, outcomes: list[WindowOutcome]) -> dict[str, Any]:
    """模型与口径版本固化（Round 4 §2）。

    必须能回答：这个结果是谁算的、用什么口径算的。
    `model_version` 说明：DashScope 兼容接口只返回请求 id 与服务端使用的模型名
    （`system_fingerprint` 实测为 null），没有可登记的不可变快照号，因此
    `modelVersion` 记录"请求名 + 首次请求 id"，其余版本均来自本地配置/台账。
    """
    used: list[str] = []
    first_request_id: str | None = None
    for item in outcomes:
        report = item.report or {}
        model = report.get("modelUsed")
        if model and model not in used:
            used.append(model)
        if first_request_id is None and report.get("modelRequestId"):
            first_request_id = report["modelRequestId"]
    primary = cfg.vision_model
    others = [model for model in used if model and model != primary]
    windows_with_other = [item.plan.window_id for item in outcomes
                          if (item.report or {}).get("modelUsed") in others]
    return {
        "modelName": primary,
        "modelFallbackName": cfg.vision_fallback_model,
        "modelVersion": (f"{primary}+req:{first_request_id}" if first_request_id else primary),
        "modelVersionNote": ("服务端未返回不可变快照号（system_fingerprint=null）；"
                            "以请求名 + 首次请求 id 追溯"),
        "modelsUsed": used or [primary],
        "promptVersion": cfg.prompt_version,
        "samplingPolicyVersion": counts_mod.SAMPLING_POLICY_VERSION,
        "metricVersion": counts_mod.METRIC_VERSION,
        "statisticsVersion": "stats-v2",
        "qualityThresholds": {
            "coverageValid": cfg.quality_valid_threshold,
            "coverageReview": cfg.quality_review_threshold,
        },
        "reviewPolicy": {
            "enabled": cfg.review_enabled,
            "unknownRatioThreshold": cfg.review_unknown_ratio_threshold,
            "suddenChangeThreshold": cfg.review_sudden_change_threshold,
            "adjacentFrameOffsetMs": cfg.review_adjacent_frame_offset_ms,
        },
        "generationParameters": {
            "temperature": cfg.vision_temperature,
            "maxTokens": cfg.vision_max_tokens,
            "seed": cfg.vision_seed,
            "disableThinking": cfg.vision_disable_thinking,
        },
        "windowsWithOtherModel": windows_with_other,
    }


def assemble_payload(request: wire.JobRequest, outcomes: list[WindowOutcome],
                     cfg: Config, *, definition_text: str | None = None) -> dict[str, Any]:
    value_mode = request.parameters.output_mode
    windows = [_window_dict(item, value_mode) for item in outcomes]
    processed = [(item.plan.window_start_ms, item.plan.window_end_ms) for item in outcomes]
    warnings: list[str] = []
    if any((item.report or {}).get('requestStatus')=='UNKNOWN_NOT_RESUBMITTED' or
           (item.review or {}).get('requestStatus')=='UNKNOWN_NOT_RESUBMITTED' for item in outcomes):
        warnings.append('WARN_UNKNOWN_REQUEST_NOT_RESUBMITTED')
    analysis_range = (request.analysis_range_start_ms, request.analysis_range_end_ms)
    if wire.union_size(processed) < analysis_range[1] - analysis_range[0]:
        warnings.append(WARN_PARTIAL)
    if any(item.quality == wire.QUALITY_LOW for item in outcomes):
        warnings.append(WARN_LOW_QUALITY)
    if outcomes and all(item.quality == wire.QUALITY_NO_SUBJECTS for item in outcomes):
        warnings.append(WARN_NO_SUBJECTS)
    reviewed = sum(1 for item in outcomes if (item.review or {}).get("needed"))
    if reviewed:
        warnings.append(f"{WARN_REVIEWED}:{reviewed}")

    generation = _generation_policy(cfg, outcomes)
    if generation["windowsWithOtherModel"]:
        # Flash/Plus 等不同模型的窗口必须显式标出，禁止混进同一条正式曲线而不留痕
        warnings.append(f"{WARN_FALLBACK_MODEL}:"
                        f"{','.join(generation['windowsWithOtherModel'])}")

    payload: dict[str, Any] = {
        "definitionId": request.parameters.definition_id,
        "denominatorDefinition": definition_text or cfg.definition_text,
        "sampleMode": wire.SAMPLE_MODE,
        "valueMode": value_mode,
        "windowMs": request.parameters.window_ms,
        "processedRanges": [{"startMs": a, "endMs": b} for a, b in wire.merge_ranges(processed)],
        "windows": windows,
        "warnings": warnings,
        "metricVersion": counts_mod.METRIC_VERSION,
        "promptVersion": cfg.prompt_version,
        # Round 4：模型与口径版本固化（每个正式结果都能追溯到"谁、用什么口径算的"）
        "generation": generation,
    }
    wire.validate_windows(windows, bounds=analysis_range, window_ms=request.parameters.window_ms,
                          processed=[(a, b) for a, b in wire.merge_ranges(processed)],
                          value_mode=value_mode)
    return payload


# --------------------------------------------------------------------------
# 统计
#
# stats-v1（契约 v0.1 冻结口径）：只含有效窗口的**时长加权**平均 Σ(r_i·d_i)/Σd_i
# stats-v2（v0.2 Round 2）：课程均值 = **SUM(head_up_count) / SUM(evaluable_count)**，
#   且只统计 VALID 窗口。两者在窗口等长时数值相同；不等长时会分叉。
#   为保持可追溯，`headUp.v1DurationWeightedMeanRate` 保留 v1 口径值作对照。
# --------------------------------------------------------------------------

def compute_stats(payload: dict[str, Any], duration_ms: int) -> dict[str, Any]:
    windows = payload["windows"]
    counts_mode = payload["valueMode"] == wire.VALUE_MODE_COUNTS

    rates: list[float | None] = []          # 仅 VALID 窗口有值（v1/v2 共用的聚合序列）
    rates_all: list[float | None] = []      # VALID + REVIEW：诊断用，不参与均值
    lengths: list[int] = []
    sum_up = 0
    sum_evaluable = 0
    for row in windows:
        lengths.append(row["endMs"] - row["startMs"])
        if counts_mode:
            denominator = row["validObservedCount"]
            raw_rate = (row["headUpCount"] / denominator) if denominator else None
        else:
            raw_rate = row["headUpRatio"]
        rates_all.append(raw_rate)
        if row["quality"] == wire.QUALITY_VALID:
            rates.append(raw_rate)
            if counts_mode and denominator:
                sum_up += int(row["headUpCount"])
                sum_evaluable += int(denominator)
            elif not counts_mode and raw_rate is not None:
                # RATIO_ONLY 没有人数，无法做人数求和；用 1/1 的等价权重退化为窗口均值
                sum_up += 0  # 占位：比率模式下 headUpRatio 由模块给出，人数未知
                sum_evaluable += 0
        else:
            rates.append(None)

    valid_pairs = [(r, d) for r, d in zip(rates, lengths) if r is not None]
    valid_ms = sum(d for _r, d in valid_pairs)
    v1_mean = (sum(r * d for r, d in valid_pairs) / valid_ms) if valid_ms else None
    if counts_mode:
        v2_mean = (sum_up / sum_evaluable) if sum_evaluable else None
    else:
        # 比率模式没有人数，退回"有效窗口的算术平均"（与 v1 等长时一致）
        v2_mean = (sum(r for r, _d in valid_pairs) / len(valid_pairs)) if valid_pairs else None

    processed_ms = wire.union_size(wire.coerce_ranges(payload["processedRanges"]))
    changes = []
    for index in range(1, len(windows)):
        current, previous = rates[index], rates[index - 1]
        if current is None or previous is None:
            continue
        if windows[index - 1]["endMs"] != windows[index]["startMs"]:
            continue  # 有时间缺口时不跨缺口计算相邻变化
        changes.append({
            "fromWindowId": windows[index - 1]["id"],
            "toWindowId": windows[index]["id"],
            "deltaPercentagePoints": 100 * (current - previous),
        })

    total_ms = sum(lengths)
    quality_counts: dict[str, int] = {}
    for row in windows:
        quality_counts[row["quality"]] = quality_counts.get(row["quality"], 0) + 1
    return {
        "statisticsVersion": "stats-v2",
        "metricVersion": counts_mod.METRIC_VERSION,
        "durationMs": duration_ms,
        "headUp": {
            "processedDurationMs": processed_ms,
            "processedCoverage": processed_ms / duration_ms if duration_ms else None,
            "validDurationMs": valid_ms,
            "validCoverage": valid_ms / duration_ms if duration_ms else None,
            # v0.2 正式均值：SUM(up)/SUM(evaluable)，只含 VALID 窗口
            "meanRate": v2_mean,
            "aggregation": "sum_ratio",
            "aggregatedWindowCount": len(valid_pairs),
            "aggregatedDurationMs": valid_ms,
            # 追溯用：v1（时长加权）与 v2（人数比）并列，便于主项目核对口径切换
            "v1DurationWeightedMeanRate": v1_mean,
            "sumHeadUpCount": sum_up if counts_mode else None,
            "sumEvaluableCount": sum_evaluable if counts_mode else None,
            "windowRates": rates,
            "windowRatesAll": rates_all,
            "qualityCounts": quality_counts,
            "changes": changes,
        },
    }
