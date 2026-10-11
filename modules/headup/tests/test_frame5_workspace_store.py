"""FRAME-5 测试：工作目录生命周期 + 任务状态机 + 逐窗口样本落库 + 采样驱动抽帧。

验收（plan §16/§18/§19/§20/§23 FRAME-5）：
- `FrameWorkspaceService`：创建 primary/review 目录、SUCCESS 无残留、FAILED 短期保留、TTL 自动清理
- 任务阶段（PROBING_VIDEO → EXTRACTING_PRIMARY → ANALYZING_PRIMARY → REVIEWING →
  AGGREGATING → SUCCESS）可上报，失败记录 error_stage
- `head_up_sample` 落库：时间戳 / 观测 / 质量 / 版本；带 `final_observation_source`
- 采样策略驱动实际抽帧（批量优先，失败回退）
"""

from __future__ import annotations

import json
import time
import unittest
from pathlib import Path

from headup import definitions, wire
from headup.config import Config
from headup.counts import make_counts
from headup.pipeline import (STAGE_AGGREGATING, STAGE_ANALYZING_PRIMARY,
                             STAGE_EXTRACTING_PRIMARY, STAGE_PROBING_VIDEO, STAGE_REVIEWING,
                             STAGE_SUCCESS, _extract_primary_frames, run_job,
                             workspace_service_for)
from headup.store import Store, SubmitOutcome
from headup.video.workspace import FrameWorkspaceService
from headup.wire import HeadUpParameters, JobRequest, MediaRef

from support import SYNTHETIC_MEDIA, find_tools, temp_root
from test_round3_review import ScriptedAnalyzer, cfg_for_review


def make_request(*, client_request_id: str = "req-frame5-001",
                 duration_ms: int = 20_000, window_ms: int = 10_000) -> JobRequest:
    return JobRequest(
        schema_version="1.0", client_request_id=client_request_id, module_type="HEAD_UP",
        class_session_id="s1", media_id="m1",
        media=MediaRef(sha256="0" * 64, duration_ms=duration_ms, mime_type="video/mp4",
                       source_url="https://media.example.invalid/x.mp4",
                       url_expires_at="2099-01-01T00:00:00Z"),
        analysis_range_start_ms=0, analysis_range_end_ms=duration_ms,
        parameters=HeadUpParameters(window_ms=window_ms, output_mode="COUNTS",
                                    definition_id=definitions.DEFINITION_ID_REAL))


class WorkspaceServiceTest(unittest.TestCase):
    """plan §16：创建 / 成功清理 / 失败保留 / TTL。"""

    def setUp(self) -> None:
        self.base = temp_root("f5ws")
        self.service = FrameWorkspaceService(self.base / "work", failed_retention_hours=12.0)

    def test_create_makes_primary_and_review_dirs_and_meta(self) -> None:
        workspace = self.service.create("task-123", classSessionId="s1")
        self.assertTrue(workspace.primary_dir.is_dir())
        self.assertTrue(workspace.review_dir.is_dir())
        self.assertTrue(workspace.meta_path.is_file())
        meta = json.loads(workspace.meta_path.read_text(encoding="utf-8"))
        self.assertEqual(meta["taskId"], "task-123")
        self.assertEqual(meta["classSessionId"], "s1")
        self.assertEqual(meta["retentionHours"], 12.0)

    def test_review_subdir_is_named_by_center_timestamp(self) -> None:
        workspace = self.service.create("task-r")
        sub = workspace.review_dir_for(120_000)
        self.assertEqual(sub.name, "000120000")
        self.assertTrue(sub.is_dir())

    def test_success_removes_temporary_files(self) -> None:
        workspace = self.service.create("task-ok")
        (workspace.primary_dir / "frame-000005000.jpg").write_bytes(b"x" * 10)
        record = self.service.finish(workspace, succeeded=True)
        self.assertFalse(workspace.root.exists(), "SUCCESS 不应留下临时残留")
        self.assertTrue(record["cleanedUp"])
        self.assertEqual(record["frames"], 1)

    def test_failure_is_retained_for_debugging(self) -> None:
        workspace = self.service.create("task-bad")
        (workspace.primary_dir / "frame-000005000.jpg").write_bytes(b"x" * 10)
        record = self.service.finish(workspace, succeeded=False, stage="DECODE_FAILED")
        self.assertTrue(workspace.root.exists(), "FAILED 应短期保留便于调试")
        self.assertTrue(record["retained"])
        self.assertEqual(record["retentionHours"], 12.0)
        meta = json.loads(workspace.meta_path.read_text(encoding="utf-8"))
        self.assertEqual(meta["outcome"], "FAILED")
        self.assertEqual(meta["stage"], "DECODE_FAILED")

    def test_ttl_cleanup_removes_only_expired_failed_tasks(self) -> None:
        failed = self.service.create("task-old-failed")
        (failed.primary_dir / "f.jpg").write_bytes(b"x")
        self.service.finish(failed, succeeded=False)
        succeeded = self.service.create("task-done")
        (succeeded.primary_dir / "f.jpg").write_bytes(b"x")
        self.service.finish(succeeded, succeeded=True)      # 成功即删
        pending = self.service.create("task-pending")       # 没有终态：不应被清

        # 未过期：不删
        self.assertEqual(self.service.cleanup_expired(), [])
        self.assertTrue(failed.root.exists())
        # 过期：删掉失败目录，保留其它
        removed = self.service.cleanup_expired(now=time.time() + 13 * 3600)
        self.assertEqual(removed, ["task-old-failed", "task-pending"])
        self.assertFalse(failed.root.exists())
        self.assertFalse(succeeded.root.exists())
        self.assertFalse(pending.root.exists(), "崩溃遗留的无终态目录到期也必须清理")

    def test_cleanup_is_idempotent(self) -> None:
        failed = self.service.create("task-twice")
        self.service.finish(failed, succeeded=False)
        self.assertEqual(self.service.cleanup_expired(now=time.time() + 13 * 3600),
                         ["task-twice"])
        self.assertEqual(self.service.cleanup_expired(now=time.time() + 13 * 3600), [])

    def test_keep_on_success_option(self) -> None:
        service = FrameWorkspaceService(self.base / "keep", keep_on_success=True)
        workspace = service.create("task-keep")
        service.finish(workspace, succeeded=True)
        self.assertTrue(workspace.root.is_dir(), "显式保留时不删")

    def test_list_tasks_reports_meta_and_size(self) -> None:
        workspace = self.service.create("task-listed", mediaId="m1")
        (workspace.primary_dir / "f.jpg").write_bytes(b"x" * 100)
        tasks = self.service.list_tasks()
        self.assertEqual([item["taskId"] for item in tasks], ["task-listed"])
        self.assertGreaterEqual(tasks[0]["bytes"], 100)
        self.assertEqual(tasks[0]["meta"]["mediaId"], "m1")


class StageReportingTest(unittest.TestCase):
    """plan §18：阶段可上报，失败记录 error_stage。"""

    def test_store_records_stage_and_ignores_terminal(self) -> None:
        store = Store(str(temp_root("f5st") / "db.sqlite3"))
        request = make_request()
        outcome, envelope = store.submit(request, provider_id="p", module_version="v")
        self.assertEqual(outcome, SubmitOutcome.CREATED)
        job_id = envelope["jobId"]
        store.set_stage(job_id, STAGE_PROBING_VIDEO)
        self.assertEqual(store.stage_of(job_id)["currentStage"], STAGE_PROBING_VIDEO)
        store.set_stage(job_id, STAGE_ANALYZING_PRIMARY, error=True)
        self.assertEqual(store.stage_of(job_id)["errorStage"], STAGE_ANALYZING_PRIMARY)
        # 成功阶段上报后落到 currentStage（此处用非终态 job 验证写入路径）
        store.set_stage(job_id, STAGE_PROBING_VIDEO)
        store.set_stage(job_id, STAGE_SUCCESS)
        self.assertEqual(store.stage_of(job_id)["currentStage"], STAGE_SUCCESS)
        # 终态后不再改阶段
        store.claim_next()                      # QUEUED → RUNNING（finish 才能写终态）
        store.finish(job_id, {
            **store.envelope(job_id), "status": wire.STATUS_FAILED, "revision": 1,
            "payload": None,
            "error": {"code": "DECODE_FAILED", "message": "x", "retryable": False},
            "updatedAt": wire.utc_now()})
        store.set_stage(job_id, STAGE_EXTRACTING_PRIMARY)
        self.assertEqual(store.stage_of(job_id)["currentStage"], STAGE_SUCCESS,
                         "终态后阶段不得再变")


@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class PipelineLifecycleTest(unittest.TestCase):
    """端到端：阶段顺序、工作目录清理、采样驱动抽帧。"""

    def setUp(self) -> None:
        self.root = temp_root("f5")
        self.cfg = cfg_for_review(work_dir=str(self.root / "work"))

    def test_stages_are_reported_in_order_and_workspace_cleaned(self) -> None:
        stages: list[tuple[str, bool]] = []
        analyzer = ScriptedAnalyzer({"000005000": (10, 9, 1), "000015000": (10, 9, 1)})
        result = run_job(request=make_request(), cfg=self.cfg, analyzer=analyzer,
                         work_root=self.root / "jobs", local_media=SYNTHETIC_MEDIA,
                         job_id="task-stage-001",
                         stage_callback=lambda stage, error: stages.append((stage, error)))
        reported = [stage for stage, _error in stages]
        self.assertIn(STAGE_PROBING_VIDEO, reported)
        self.assertIn(STAGE_EXTRACTING_PRIMARY, reported)
        self.assertIn(STAGE_ANALYZING_PRIMARY, reported)
        self.assertIn(STAGE_AGGREGATING, reported)
        self.assertEqual(reported[-1], STAGE_SUCCESS)
        self.assertLess(reported.index(STAGE_EXTRACTING_PRIMARY),
                        reported.index(STAGE_ANALYZING_PRIMARY))
        self.assertLess(reported.index(STAGE_ANALYZING_PRIMARY),
                        reported.index(STAGE_AGGREGATING))
        # 成功 ⇒ 工作目录已删除（plan §16）
        workspace_root = Path(self.cfg.work_dir) / "tasks" / "headup" / "task-stage-001"
        self.assertFalse(workspace_root.exists(), "SUCCESS 不应留下临时目录")
        self.assertEqual(result.payload["generation"]["workspace"]["taskId"], "task-stage-001")
        self.assertTrue(result.payload["generation"]["workspace"]["cleanedUp"])

    def test_failure_keeps_workspace_and_reports_error_stage(self) -> None:
        stages: list[tuple[str, bool]] = []

        class ExplodingAnalyzer:
            name = "boom"
            version = "test"
            prompt_version = "headup-strict-v0.2"

            def analyze(self, image_path):
                raise wire.TaskError("VISION_PROVIDER_ERROR", "模拟模型故障", retryable=True)

        with self.assertRaises(wire.TaskError):
            run_job(request=make_request(), cfg=self.cfg, analyzer=ExplodingAnalyzer(),
                    work_root=self.root / "jobs", local_media=SYNTHETIC_MEDIA,
                    job_id="task-fail-001",
                    stage_callback=lambda stage, error: stages.append((stage, error)))
        self.assertTrue(any(error for _stage, error in stages), "应上报失败阶段")
        workspace_root = Path(self.cfg.work_dir) / "tasks" / "headup" / "task-fail-001"
        self.assertTrue(workspace_root.is_dir(), "FAILED 应保留目录便于调试")
        meta = json.loads((workspace_root / "task.json").read_text(encoding="utf-8"))
        self.assertEqual(meta["outcome"], "FAILED")

    def test_review_frames_go_into_review_subdir(self) -> None:
        """复核帧落在 review/{centerTimestamp}/（plan §6）；窗口要足够宽以容纳 t±500ms。"""
        cfg = cfg_for_review(work_dir=str(self.root / "work2"), frames_retain=1)
        # 代表帧 coverage 0.6 ⇒ REVIEW（触发复核）；三图复核给 0.9
        analyzer = ScriptedAnalyzer({"000010000": (10, 4, 2)},
                                    window_script={"win-001": (10, 9, 1)})
        run_job(request=make_request(window_ms=20_000), cfg=cfg, analyzer=analyzer,
                work_root=self.root / "jobs", local_media=SYNTHETIC_MEDIA,
                job_id="task-review-001")
        workspace_root = Path(cfg.work_dir) / "tasks" / "headup" / "task-review-001"
        self.assertTrue(workspace_root.is_dir(), "frames_retain=1 时保留目录")
        self.assertEqual(len(list(workspace_root.rglob("*.jpg"))), 1,
                         "FRAMES_RETAIN=1 只能留下恰好一张帧")
        self.assertTrue(analyzer.window_calls, "复核应走一次多图请求")

    def test_review_frames_near_window_edge_are_skipped(self) -> None:
        """t±500ms 越出窗口时必须跳过（不能把相邻窗口的画面算进本窗）。"""
        from headup.frames import plan_frames
        from headup.pipeline import _review_window_centers

        cfg = cfg_for_review()
        narrow = plan_frames(start_ms=0, end_ms=10_000, window_ms=10_000,
                             position="start")[0]      # 采样点 = 窗口起点
        self.assertEqual(narrow.sample_at_ms, 0)
        self.assertEqual(_review_window_centers(cfg, narrow, 20_000), [500],
                         "起点窗口只能取 +500ms 一侧")
        last = plan_frames(start_ms=0, end_ms=20_000, window_ms=10_000, position="end")[-1]
        # 媒体最后一个可解帧是 duration-1 = 19999ms，因此 t+500 被裁到 19999
        self.assertEqual(_review_window_centers(cfg, last, 20_000), [19_499],
                         "末尾窗口只能取 -500ms 一侧，且不越过媒体末尾")

    def test_workspace_service_for_reads_config(self) -> None:
        cfg = Config(work_dir=str(self.root / "work3"), failed_task_retention_hours=3.5)
        service = workspace_service_for(cfg)
        self.assertEqual(service.failed_retention_hours, 3.5)
        # 布局：{workDir}/tasks/headup/{taskId}/（FrameWorkspaceService 自己会加 headup 一级）
        workspace = service.create("task-layout")
        self.assertEqual(workspace.root.parent, service.base_dir)
        self.assertTrue(workspace.primary_dir.is_dir())

@unittest.skipUnless(find_tools() is not None and SYNTHETIC_MEDIA.is_file(),
                     "缺少 ffmpeg 或测试媒体")
class SamplePersistenceTest(unittest.TestCase):
    """plan §19/§20：逐窗口样本落库与 final_observation_source。"""

    def setUp(self) -> None:
        self.root = temp_root("f5sp")
        self.store = Store(str(self.root / "db.sqlite3"))

    def test_save_samples_is_idempotent_and_ordered(self) -> None:
        rows = [
            {"window_id": "win-001", "timestamp_ms": 5_000, "window_start_ms": 0,
             "window_end_ms": 10_000, "visible_count": 10, "evaluable_count": 8,
             "head_up_count": 7, "head_down_count": 1, "unknown_count": 2,
             "head_up_rate": 0.875, "coverage": 0.8, "quality": "VALID",
             "quality_status": "VALID", "quality_reason": None, "reviewed": False,
             "review_strategy": None, "final_observation_source": "PRIMARY",
             "model_name": "qwen3-vl-flash", "model_version": "m+req:1",
             "prompt_version": "headup-strict-v0.2",
             "sampling_policy_version": "10s-window-midpoint-v0.2",
             "metric_version": "head-up-rate-v0.2", "statistics_version": "stats-v2",
             "client_request_id": "r1", "class_session_id": "s1", "media_id": "m1"},
            {"window_id": "win-002", "timestamp_ms": 15_000, "window_start_ms": 10_000,
             "window_end_ms": 20_000, "visible_count": 10, "evaluable_count": 9,
             "head_up_count": 8, "head_down_count": 1, "unknown_count": 1,
             "head_up_rate": 0.889, "coverage": 0.9, "quality": "VALID",
             "quality_status": "VALID", "quality_reason": "REVIEW_CONFIRMED_VALID",
             "reviewed": True, "review_strategy": "MULTI_IMAGE_SINGLE_REQUEST",
             "final_observation_source": "REVIEW_WINDOW", "client_request_id": "r1",
             "class_session_id": "s1", "media_id": "m1"},
        ]
        self.assertEqual(self.store.save_samples("job-1", rows), 2)
        stored = self.store.samples_of("job-1")
        self.assertEqual([row["window_id"] for row in stored], ["win-001", "win-002"])
        self.assertEqual(stored[1]["final_observation_source"], "REVIEW_WINDOW")
        self.assertEqual(stored[1]["review_strategy"], "MULTI_IMAGE_SINGLE_REQUEST")
        self.assertEqual(stored[1]["reviewed"], 1)
        self.assertEqual(stored[0]["prompt_version"], "headup-strict-v0.2")
        # 幂等：重复写入不产生重复行
        self.store.save_samples("job-1", rows)
        self.assertEqual(len(self.store.samples_of("job-1")), 2)

    def test_samples_do_not_store_frame_paths(self) -> None:
        """plan §19：库里不保存临时帧，只保存时间戳/观测/质量/版本。"""
        with self.store._connect() as conn:
            columns = {row["name"] for row in
                       conn.execute("PRAGMA table_info(head_up_sample)").fetchall()}
        self.assertTrue(columns, "head_up_sample 表应存在")
        for forbidden in ("image_path", "frame_path", "frames", "image", "frame_file"):
            self.assertNotIn(forbidden, columns)
        for required in ("timestamp_ms", "head_up_rate", "coverage", "quality",
                         "final_observation_source", "prompt_version",
                         "sampling_policy_version", "metric_version"):
            self.assertIn(required, columns, required)

    def test_service_persists_samples_after_success(self) -> None:
        from headup.providers import ProviderResult
        from headup.service import JobService

        cfg = cfg_for_review(work_dir=str(self.root / "work"), db_path=str(self.root / "svc.db"))
        service = JobService(cfg, self.store)
        row = {"job_id": "job-svc-1", "client_request_id": "req-svc-1",
               "class_session_id": "s1", "media_id": "m1"}
        result = ProviderResult(
            payload={"generation": {"modelName": "qwen3-vl-flash", "modelVersion": "m+req:1",
                                    "promptVersion": "headup-strict-v0.2",
                                    "samplingPolicyVersion": "10s-window-midpoint-v0.2",
                                    "metricVersion": "head-up-rate-v0.2",
                                    "statisticsVersion": "stats-v2"},
                     "windows": [
                         {"id": "win-001", "sampleAtMs": 5_000, "startMs": 0, "endMs": 10_000,
                          "headUpCount": 7, "validObservedCount": 8, "headUpRate": 0.875,
                          "coverage": 0.8, "quality": "VALID", "qualityStatus": "VALID",
                          "qualityReason": None,
                          "counts": {"visibleCount": 10, "headDownCount": 1, "unknownCount": 2}},
                     ]},
            stats={}, fixture_observations=False)
        service._persist_samples(row, result)
        stored = self.store.samples_of("job-svc-1")
        self.assertEqual(len(stored), 1)
        self.assertEqual(stored[0]["window_id"], "win-001")
        self.assertEqual(stored[0]["final_observation_source"], "PRIMARY")
        self.assertEqual(stored[0]["model_name"], "qwen3-vl-flash")

    def test_service_persists_samples_for_reviewed_window(self) -> None:
        """复核过的窗口必须标 REVIEW_WINDOW（plan §20：明确哪个是最终结果）。"""
        from headup.providers import ProviderResult
        from headup.service import JobService

        cfg = cfg_for_review(work_dir=str(self.root / "work2"))
        service = JobService(cfg, self.store)
        row = {"job_id": "job-svc-2", "client_request_id": "req-svc-2",
               "class_session_id": "s1", "media_id": "m1"}
        result = ProviderResult(
            payload={"generation": {"modelName": "qwen3-vl-flash"},
                     "windows": [
                         {"id": "win-002", "sampleAtMs": 15_000, "startMs": 10_000,
                          "endMs": 20_000, "headUpCount": 8, "validObservedCount": 9,
                          "headUpRate": 0.889, "coverage": 0.9, "quality": "VALID",
                          "qualityStatus": "VALID", "qualityReason": "REVIEW_CONFIRMED_VALID",
                          "counts": {"visibleCount": 10, "headDownCount": 1, "unknownCount": 1},
                          "review": {"strategy": "MULTI_IMAGE_SINGLE_REQUEST",
                                     "outcome": "CONFIRMED_VALID", "frameCount": 3}},
                     ]},
            stats={}, fixture_observations=False)
        service._persist_samples(row, result)
        stored = self.store.samples_of("job-svc-2")
        self.assertEqual(stored[0]["final_observation_source"], "REVIEW_WINDOW")
        self.assertEqual(stored[0]["reviewed"], 1)
        self.assertEqual(stored[0]["review_strategy"], "MULTI_IMAGE_SINGLE_REQUEST")


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
