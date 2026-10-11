"""任务编排：提交、worker 线程、终态写入。

- HTTP 层只做校验与持久化，处理在 worker 线程里跑（提交即返回 202）；
- 单机 worker 数由 `WORKERS` 配置；任务内并发由 `ANALYSIS_CONCURRENCY` 配置（当前逐窗顺序执行，
  该值在 `/health` 中作为声明项，真正并发执行留待压测后启用）；
- worker 崩溃/重启：`claim_next()` 通过租约回收 RUNNING 任务，因此重启后能继续推进。
"""

from __future__ import annotations

import os
import json
import logging
import threading
import traceback
from pathlib import Path
from typing import Any

from .recovery import Checkpoint, LeaseLost
from . import perf
from .config import Config
from .perf import RunMetrics
from .providers import Provider, ProviderResult, build_provider
from .store import Store, SubmitOutcome
from .wire import (STATUS_FAILED, STATUS_SUCCEEDED, ApiError, TaskError, build_envelope,
                   parse_job_request, utc_now)

LOG = logging.getLogger("headup.service")


class JobService:
    def __init__(self, cfg: Config, store: Store):
        self.cfg = cfg
        self.store = store
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []

    # -- 提交 -------------------------------------------------------------
    def submit(self, raw_body: Any, idempotency_key: str | None) -> tuple[dict[str, Any], int]:
        """返回 (envelope, http_status)。

        202 = 首次受理；200 = 同键同内容复用（含仅续期下载凭据）。
        409 = 同键异内容（契约 §3）；请求非法 = 422/403/400（由 parse_job_request 抛出）。
        """
        request = parse_job_request(
            raw_body,
            idempotency_key=idempotency_key,
            allowed_definition_ids=self.cfg.allowed_definition_ids(),
            max_duration_ms=self.cfg.max_duration_ms,
            max_windows=self.cfg.max_windows,
        )
        provider_id, module_version = self.identity_for(request)

        outcome, envelope = self.store.submit(request, provider_id=provider_id,
                                              module_version=module_version)
        if outcome == SubmitOutcome.CONFLICT:
            raise ApiError(409, "IDEMPOTENCY_CONFLICT",
                           "同一 clientRequestId 的请求内容与已受理任务不同；"
                           "更换分析参数或媒体必须使用新的 clientRequestId")
        if outcome == SubmitOutcome.CREATED:
            return envelope, 202
        if envelope is not None:
            return envelope, 200
        # 仅续期下载凭据：返回当前信封（非终态时为 QUEUED/RUNNING）。
        current = self.store.envelope_by_client_request(request.client_request_id)
        if current is None:
            raise ApiError(500, "PROCESSING_FAILED", "任务状态读取失败", retryable=True)
        return current, 200

    def envelope(self, job_id: str) -> dict[str, Any] | None:
        return self.store.envelope(job_id)

    def identity_for(self, request: Any) -> tuple[str, str]:
        """本任务实际使用的 providerId / moduleVersion（合成路径与真实路径身份不同）。"""
        if self.cfg.enable_fixture_provider:
            from .providers import FixtureProvider

            if FixtureProvider(self.cfg).accepts(request):
                return self.cfg.fixture_provider_id, self.cfg.fixture_provider_version
        return self.cfg.provider_id, self.cfg.module_version

    # -- worker -----------------------------------------------------------
    def start(self) -> None:
        from .pipeline import workspace_service_for
        removed = workspace_service_for(self.cfg).cleanup_expired()
        if removed:
            LOG.info("启动清理已移除 %d 个过期/中断工作目录", len(removed))
        janitor = threading.Thread(target=self._cleanup_loop, name="headup-workspace-janitor",
                                   daemon=True)
        janitor.start()
        self._threads.append(janitor)
        for index in range(max(1, self.cfg.workers)):
            thread = threading.Thread(target=self._loop, name=f"headup-worker-{index}",
                                      daemon=True)
            thread.start()
            self._threads.append(thread)
        LOG.info("worker 已启动: %d 个", max(1, self.cfg.workers))

    def _cleanup_loop(self) -> None:
        from .pipeline import workspace_service_for
        while not self._stop.wait(3600):
            try:
                workspace_service_for(self.cfg).cleanup_expired()
            except Exception:
                LOG.exception("工作目录定时清理失败")

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        for thread in self._threads:
            thread.join(timeout=timeout)

    def _loop(self) -> None:
        while not self._stop.is_set():
            row = self.store.claim_next(lease_seconds=900.0)
            if row is None:
                self._stop.wait(0.5)
                continue
            try:
                done = threading.Event()
                def renew():
                    while not done.wait(30):
                        if not self.store.heartbeat(row["job_id"],row["owner_token"]): return
                heartbeat = threading.Thread(target=renew,daemon=True)
                heartbeat.start()
                try: self._run_one(row)
                finally: done.set(); heartbeat.join(timeout=2)
            except LeaseLost:
                LOG.warning("Stale owner stopped jobId=%s", row["job_id"])
            except Exception:  # pragma: no cover - 兜底，绝不让 worker 死掉
                LOG.error("worker 内部异常 jobId=%s\n%s", row.get("job_id"), traceback.format_exc())
                self.store.release(row["job_id"],owner_token=row.get("owner_token"))

    # -- 单任务 -----------------------------------------------------------
    def _run_one(self, row: dict[str, Any]) -> None:
        job_id = row["job_id"]
        raw_request = json.loads(row["request_json"])
        raw_request["media"] = dict(raw_request.get("media") or {})
        # 落库版本不含下载凭据，这里临时补回（凭证只存在于内存与该列中）。
        raw_request["media"]["sourceUrl"] = row.get("media_source_url") or ""
        raw_request["media"]["urlExpiresAt"] = row.get("media_url_expires_at") or "1970-01-01T00:00:00Z"
        try:
            request = parse_job_request(
                raw_request, idempotency_key=row["client_request_id"],
                allowed_definition_ids=self.cfg.allowed_definition_ids(),
                max_duration_ms=self.cfg.max_duration_ms,
                max_windows=self.cfg.max_windows,
            )
        except ApiError as exc:  # 落库数据异常：终态失败，不静默重试
            self._finish_failed(row, TaskError("PROCESSING_FAILED", f"任务输入无法解析: {exc.message}",
                                               retryable=False))
            return

        metrics = RunMetrics(enabled=self.cfg.perf_metrics_enabled)
        try:
            provider: Provider = build_provider(self.cfg, request)
            if hasattr(provider,"checkpoint"): provider.checkpoint = Checkpoint(self.store,row)
            metrics = RunMetrics(enabled=self.cfg.perf_metrics_enabled)
            metrics.note("providerId", provider.provider_id)
            result: ProviderResult = provider.run(
                request, source_url=row.get("media_source_url"),
                job_id=job_id, stage_callback=self._stage_reporter(job_id,row.get("owner_token")), metrics=metrics)
            if getattr(provider,"checkpoint",None) is not None: provider.checkpoint.check()
            if result.fixture_observations:
                # 合成观测必须与真实模型运行区分：调用数恒为 0，不要拿来当性能数据
                metrics.note("syntheticObservations", True)
            self._finish_success(row, provider, result)
            self._persist_samples(row, result, metrics)
        except LeaseLost:
            raise
        except TaskError as exc:
            if exc.code=="MEDIA_URL_EXPIRED" and os.environ.get("MERGE_MODE")=="1":
                # Credentials may rotate/expire during a restart. The coordinator refreshes
                # the same clientRequestId; retain prefix checkpoints and await that refresh.
                from datetime import datetime
                age=__import__('time').time()-datetime.fromisoformat(row['created_at'].replace('Z','+00:00')).timestamp()
                if age<int(os.environ.get("MERGE_RECOGNITION_TIMEOUT_SECONDS","7200")):
                    self.store.release(job_id,owner_token=row.get('owner_token'))
                    self._stop.wait(2)
                    return
            metrics.note("errorCode", exc.code)
            self._finish_failed(row, exc)
        except Exception as exc:  # 未预期错误：显式失败，不伪装成功
            metrics.note("errorCode", "INTERNAL_ERROR")
            LOG.error("任务处理异常 jobId=%s: %s", job_id, traceback.format_exc())
            self._finish_failed(row, TaskError("PROCESSING_FAILED",
                                               f"内部错误: {type(exc).__name__}", retryable=True))
        finally:
            self._persist_metrics(row, metrics)

    # -- FRAME-5：阶段上报与逐窗口样本落库 ---------------------------------
    def _stage_reporter(self, job_id: str, owner_token: str | None = None):
        def report(stage: str, error: bool = False) -> None:
            self.store.set_stage(job_id, stage, error=error,owner_token=owner_token)
        return report

    def _persist_samples(self, row: dict[str, Any], result: ProviderResult,
                         metrics: RunMetrics | None = None) -> None:
        """把逐窗口样本写进模块自己的库（plan §19）。

        只落"时间戳 + 观测 + 质量 + 版本"，**不落临时帧路径**；并带
        `final_observation_source`，避免同一时间点有多个结果却不知道哪个是最终值。

        补记修复（用户报告）：抽帧失败的窗口在 payload 里 `sampleAtMs=null`（它确实没有
        采样点），旧 schema 的 `timestamp_ms NOT NULL` 会让整批样本回滚丢失。
        现在：①schema 允许 null，窗口用 `window_start_ms/window_end_ms` 定位；
        ②逐行隔离，坏行不再连累其它窗口；③写入条数与失败窗口记进 `metrics` 与日志。
        """
        payload = result.payload or {}
        generation = payload.get("generation") or {}
        samples: list[dict[str, Any]] = []
        for window in payload.get("windows") or []:
            review = window.get("review") or {}
            counts = window.get("counts") or {}
            reviewed = bool(review)
            # 没有采样点就写 null：**不要**用窗口起点顶替，那会伪装成一个真实采样时间点
            timestamp = (window["sampleAtMs"] if "sampleAtMs" in window
                         else window.get("startMs"))
            samples.append({
                "client_request_id": row["client_request_id"],
                "class_session_id": row["class_session_id"],
                "media_id": row["media_id"],
                "window_id": window.get("id"),
                "timestamp_ms": timestamp,
                "window_start_ms": window.get("startMs"),
                "window_end_ms": window.get("endMs"),
                "visible_count": counts.get("visibleCount"),
                "evaluable_count": window.get("validObservedCount"),
                "head_up_count": window.get("headUpCount"),
                "head_down_count": (None if counts.get("headDownCount") is None
                                    else counts.get("headDownCount")),
                "unknown_count": counts.get("unknownCount"),
                "head_up_rate": window.get("headUpRate"),
                "coverage": window.get("coverage"),
                "quality": window.get("quality"),
                "quality_status": window.get("qualityStatus"),
                "quality_reason": window.get("qualityReason"),
                "reviewed": reviewed,
                "review_strategy": review.get("strategy"),
                "final_observation_source": "REVIEW_WINDOW" if reviewed else "PRIMARY",
                "model_name": generation.get("modelName"),
                "model_version": generation.get("modelVersion"),
                "prompt_version": generation.get("promptVersion"),
                "sampling_policy_version": generation.get("samplingPolicyVersion"),
                "metric_version": generation.get("metricVersion"),
                "statistics_version": generation.get("statisticsVersion"),
            })
        try:
            outcome = self.store.save_samples_detailed(row["job_id"], samples,owner_token=row.get("owner_token"))
        except Exception:  # pragma: no cover - 样本落库失败不应影响已成功的任务
            LOG.error("样本落库失败 jobId=%s\n%s", row["job_id"], traceback.format_exc())
            if metrics is not None:
                metrics.note("samplesPersistError", "PROCESSING_FAILED")
                metrics.note("samplesWritten", 0)
            return
        if metrics is not None:
            metrics.note("samplesWritten", outcome.written)
        if outcome.failed:
            # 逐窗口样本是审计线索：少了一条必须看得见（日志 + 指标 + 告警列表）
            LOG.error("部分逐窗口样本未落库 jobId=%s 失败窗口=%s", row["job_id"],
                      ", ".join(str(item["windowId"]) for item in outcome.failed))
            if metrics is not None:
                metrics.note("samplesPersistFailed", outcome.failed)
        else:
            LOG.info("已写入逐窗口样本: jobId=%s 条数=%d", row["job_id"], outcome.written)

    def samples(self, job_id: str) -> list[dict[str, Any]]:
        return self.store.samples_of(job_id)

    def stage(self, job_id: str) -> dict[str, Any] | None:
        return self.store.stage_of(job_id)

    # -- FRAME-6：性能指标 --------------------------------------------------
    def _persist_metrics(self, row: dict[str, Any], metrics: RunMetrics) -> None:
        """把一次运行的实测指标落库（含失败运行：失败发生在哪个阶段同样有价值）。"""
        try:
            self.store.set_metrics(
                row["job_id"],
                perf.metrics_to_dict(metrics, cost=perf.estimate_cost(self.cfg, metrics)),owner_token=row.get("owner_token"))
        except Exception:  # pragma: no cover - 指标落库失败不应影响任务结果
            LOG.error("指标落库失败 jobId=%s\n%s", row["job_id"], traceback.format_exc())

    def metrics(self, job_id: str) -> dict[str, Any] | None:
        return self.store.metrics_of(job_id)

    def _finish_success(self, row: dict[str, Any], provider: Provider,
                        result: ProviderResult) -> None:
        now = utc_now()
        envelope = build_envelope(
            job_id=row["job_id"],
            client_request_id=row["client_request_id"],
            provider_id=provider.provider_id,
            module_version=provider.version,
            class_session_id=row["class_session_id"],
            media_id=row["media_id"],
            media_sha256=row["media_sha256"],
            duration_ms=row["duration_ms"],
            analysis_range={"startMs": row["analysis_start_ms"], "endMs": row["analysis_end_ms"]},
            status=STATUS_SUCCEEDED,
            revision=1,
            created_at=row["created_at"],
            updated_at=now,
            payload=result.payload,
        )
        self.store.finish(row["job_id"], envelope,owner_token=row.get("owner_token"))

    def _finish_failed(self, row: dict[str, Any], error: TaskError) -> None:
        now = utc_now()
        envelope = build_envelope(
            job_id=row["job_id"],
            client_request_id=row["client_request_id"],
            provider_id=row["provider_id"],
            module_version=row["module_version"],
            class_session_id=row["class_session_id"],
            media_id=row["media_id"],
            media_sha256=row["media_sha256"],
            duration_ms=row["duration_ms"],
            analysis_range={"startMs": row["analysis_start_ms"], "endMs": row["analysis_end_ms"]},
            status=STATUS_FAILED,
            revision=1,
            created_at=row["created_at"],
            updated_at=now,
            error=error.to_dict(),
        )
        self.store.finish(row["job_id"], envelope,owner_token=row.get("owner_token"))


def work_root(cfg: Config) -> Path:
    return Path(cfg.work_dir) / "jobs"
