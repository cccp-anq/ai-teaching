"""用户报告的缺陷回归测试：抽帧失败窗口（sampleAtMs=null）的逐窗口样本落库。

缺陷（已复现并修复）：

1. `head_up_sample.timestamp_ms` 是 `NOT NULL`，但 payload 里抽帧失败的窗口
   `sampleAtMs` 就是 `null`（它确实没有采样点）⇒ `IntegrityError`；
2. 更严重的是 `save_samples()` 把所有行放在**一个事务**里，一行出错整批回滚
   ⇒ **整个任务**的审计样本全部消失；
3. 异常被 `_persist_samples()` 捕获，任务仍然 `SUCCEEDED`，只在日志里留一行 ERROR
   ⇒ 表面成功、审计线索静默丢失。

修复：
- schema 允许 `timestamp_ms` 为空（老库自动重建迁移，数据保留）；
- `save_samples_detailed()` 逐行 SAVEPOINT 隔离，坏行不连累其它窗口；
- 写入条数与失败窗口进 `metrics`（`samplesWritten` / `samplesPersistFailed`）与日志；
- **不**用窗口起点顶替缺失的采样点（那会伪装成一个真实采样时间）。
"""

from __future__ import annotations

import sqlite3
import unittest

from headup import definitions, wire
from headup.config import Config
from headup.counts import make_counts
from headup.service import JobService
from headup.store import SAMPLE_COLUMNS, Store
from headup.vision.schema import FrameReading
from headup.wire import HeadUpParameters, JobRequest, MediaRef

from support import SYNTHETIC_MEDIA, demo_request_doc, find_tools, temp_root
from test_round3_review import cfg_for_review

HAS_MEDIA = find_tools() is not None and SYNTHETIC_MEDIA.is_file()

# 老库（修复前）的建表语句：timestamp_ms NOT NULL —— 迁移测试用它造"旧库"
OLD_SAMPLE_SCHEMA = """
CREATE TABLE head_up_sample (
    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id                      TEXT NOT NULL,
    client_request_id           TEXT NOT NULL,
    class_session_id            TEXT NOT NULL,
    media_id                    TEXT NOT NULL,
    window_id                   TEXT NOT NULL,
    timestamp_ms                INTEGER NOT NULL,
    window_start_ms             INTEGER NOT NULL,
    window_end_ms               INTEGER NOT NULL,
    visible_count               INTEGER,
    evaluable_count             INTEGER,
    head_up_count               INTEGER,
    head_down_count             INTEGER,
    unknown_count               INTEGER,
    head_up_rate                REAL,
    coverage                    REAL,
    quality                     TEXT NOT NULL,
    quality_status              TEXT,
    quality_reason              TEXT,
    reviewed                    INTEGER NOT NULL DEFAULT 0,
    review_strategy             TEXT,
    final_observation_source    TEXT NOT NULL,
    model_name                  TEXT,
    model_version               TEXT,
    prompt_version              TEXT,
    sampling_policy_version     TEXT,
    metric_version              TEXT,
    statistics_version          TEXT,
    created_at                  TEXT NOT NULL,
    UNIQUE (job_id, window_id)
);
CREATE INDEX idx_head_up_sample_job ON head_up_sample(job_id, timestamp_ms);
"""


def sample_row(window_id: str, timestamp_ms: int | None, *, quality: str = "VALID",
               start_ms: int = 0, reason: str | None = None) -> dict:
    return {
        "client_request_id": "req-defect-001", "class_session_id": "s1", "media_id": "m1",
        "window_id": window_id, "timestamp_ms": timestamp_ms,
        "window_start_ms": start_ms, "window_end_ms": start_ms + 10_000,
        "visible_count": None, "evaluable_count": None, "head_up_count": None,
        "head_down_count": None, "unknown_count": None, "head_up_rate": None, "coverage": None,
        "quality": quality, "quality_status": "UNREADABLE" if quality == "LOW_QUALITY" else "VALID",
        "quality_reason": reason, "reviewed": False, "review_strategy": None,
        "final_observation_source": "PRIMARY", "model_name": "qwen3-vl-flash",
        "model_version": "m+req:1", "prompt_version": "headup-strict-v0.2",
        "sampling_policy_version": "10s-window-midpoint-v0.2",
        "metric_version": "head-up-rate-v0.2", "statistics_version": "stats-v2",
    }


class NullTimestampPersistenceTest(unittest.TestCase):
    """核心回归：没有采样点的窗口必须也能留档，且不许拖累别的窗口。"""

    def setUp(self) -> None:
        self.root = temp_root("defect")
        self.store = Store(str(self.root / "db.sqlite3"))

    def test_window_without_sample_point_is_persisted_with_null_timestamp(self) -> None:
        rows = [sample_row("win-001", 5_000),
                sample_row("win-002", None, quality="LOW_QUALITY", start_ms=10_000,
                           reason="FRAME_EXTRACTION_FAILED"),
                sample_row("win-003", 25_000, start_ms=20_000)]
        outcome = self.store.save_samples_detailed("job-1", rows)
        self.assertEqual(outcome.written, 3, f"不得丢样本: {outcome.to_dict()}")
        self.assertTrue(outcome.ok, outcome.to_dict())
        persisted = self.store.samples_of("job-1")
        self.assertEqual([row["window_id"] for row in persisted],
                         ["win-001", "win-002", "win-003"], "按窗口时间顺序返回")
        failed = persisted[1]
        self.assertIsNone(failed["timestamp_ms"], "没有采样点就写 null，不许用窗口起点顶替")
        self.assertEqual(failed["quality_reason"], "FRAME_EXTRACTION_FAILED")
        self.assertEqual((failed["window_start_ms"], failed["window_end_ms"]), (10_000, 20_000))

    def test_one_bad_row_does_not_roll_back_the_whole_batch(self) -> None:
        rows = [sample_row("win-001", 5_000),
                {**sample_row("win-002", 15_000), "quality": None},   # 违反 NOT NULL
                sample_row("win-003", 25_000, start_ms=20_000)]
        outcome = self.store.save_samples_detailed("job-2", rows)
        self.assertEqual(outcome.written, 2, "好行必须照写")
        self.assertEqual(len(outcome.failed), 1)
        self.assertEqual(outcome.failed[0]["windowId"], "win-002")
        self.assertIn("NOT NULL", outcome.failed[0]["error"])
        self.assertEqual([row["window_id"] for row in self.store.samples_of("job-2")],
                         ["win-001", "win-003"])

    def test_save_samples_keeps_returning_written_count(self) -> None:
        """兼容入口：旧调用方拿到的仍是"写了几行"。"""
        written = self.store.save_samples("job-3", [sample_row("win-001", 5_000),
                                                    sample_row("win-002", None,
                                                               quality="LOW_QUALITY",
                                                               start_ms=10_000)])
        self.assertEqual(written, 2)

    def test_empty_input_is_a_noop(self) -> None:
        outcome = self.store.save_samples_detailed("job-empty", [])
        self.assertEqual(outcome.written, 0)
        self.assertTrue(outcome.ok)

    def test_upsert_keeps_idempotent(self) -> None:
        self.store.save_samples("job-4", [sample_row("win-001", 5_000)])
        self.store.save_samples("job-4", [sample_row("win-001", None, quality="LOW_QUALITY",
                                                     reason="FRAME_EXTRACTION_FAILED")])
        rows = self.store.samples_of("job-4")
        self.assertEqual(len(rows), 1, "同 (job_id, window_id) 覆盖")
        self.assertIsNone(rows[0]["timestamp_ms"])
        self.assertEqual(rows[0]["quality_reason"], "FRAME_EXTRACTION_FAILED")


class SampleMigrationTest(unittest.TestCase):
    """老库（timestamp_ms NOT NULL）自动重建迁移，数据不许丢。"""

    def _old_db(self, rows: list[tuple]) -> str:
        root = temp_root("defect-mig")
        db = root / "old.sqlite3"
        conn = sqlite3.connect(db)
        conn.executescript(OLD_SAMPLE_SCHEMA)
        for index, (window_id, timestamp) in enumerate(rows):
            conn.execute(
                "INSERT INTO head_up_sample (job_id, client_request_id, class_session_id,"
                " media_id, window_id, timestamp_ms, window_start_ms, window_end_ms, quality,"
                " final_observation_source, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                ("job-old", "req-old", "s1", "m1", window_id, timestamp, index * 10_000,
                 (index + 1) * 10_000, "VALID", "PRIMARY", "2026-01-01T00:00:00Z"))
        conn.commit()
        conn.close()
        return str(db)

    def test_migration_preserves_rows_and_drops_not_null(self) -> None:
        db = self._old_db([("win-001", 5_000), ("win-002", 15_000)])
        store = Store(db)                      # 触发迁移
        columns = {row[1]: row[3] for row in sqlite3.connect(db).execute(
            "PRAGMA table_info(head_up_sample)")}
        self.assertEqual(columns["timestamp_ms"], 0, "迁移后 timestamp_ms 必须可空")
        self.assertEqual([row[1] for row in sqlite3.connect(db).execute(
            "PRAGMA table_info(head_up_sample)")], list(SAMPLE_COLUMNS),
            "迁移后的字段顺序必须与当前 schema 一致")
        rows = store.samples_of("job-old")
        self.assertEqual([row["window_id"] for row in rows], ["win-001", "win-002"])
        self.assertEqual([row["timestamp_ms"] for row in rows], [5_000, 15_000])
        self.assertEqual(rows[0]["created_at"], "2026-01-01T00:00:00Z", "原有字段值必须原样保留")
        # 迁移后旧的坏行能被正常写入
        outcome = store.save_samples_detailed("job-old", [
            sample_row("win-003", None, quality="LOW_QUALITY", start_ms=20_000,
                       reason="FRAME_EXTRACTION_FAILED")])
        self.assertTrue(outcome.ok, outcome.to_dict())
        self.assertEqual(len(store.samples_of("job-old")), 3)

    def test_migration_is_idempotent_and_keeps_indexes(self) -> None:
        db = self._old_db([("win-001", 5_000)])
        Store(db)
        Store(db)                              # 再开一次不应重复重建或报错
        indexes = {row[1] for row in sqlite3.connect(db).execute(
            "PRAGMA index_list(head_up_sample)")}
        self.assertIn("idx_head_up_sample_job", indexes)
        self.assertEqual(len(sqlite3.connect(db).execute(
            "SELECT * FROM head_up_sample").fetchall()), 1)

    def test_new_database_matches_canonical_columns(self) -> None:
        root = temp_root("defect-new")
        db = root / "new.sqlite3"
        Store(str(db))
        columns = [row[1] for row in sqlite3.connect(db).execute(
            "PRAGMA table_info(head_up_sample)")]
        self.assertEqual(columns, list(SAMPLE_COLUMNS))


def make_request(*, client_request_id: str, duration_ms: int = 20_000) -> JobRequest:
    return JobRequest(
        schema_version="1.0", client_request_id=client_request_id, module_type="HEAD_UP",
        class_session_id="s1", media_id="m1",
        media=MediaRef(sha256="0" * 64, duration_ms=duration_ms, mime_type="video/mp4",
                       source_url="https://media.example.invalid/x.mp4",
                       url_expires_at="2099-01-01T00:00:00Z"),
        analysis_range_start_ms=0, analysis_range_end_ms=duration_ms,
        parameters=HeadUpParameters(window_ms=10_000, output_mode="COUNTS",
                                    definition_id=definitions.DEFINITION_ID_REAL))


class FailingWindowAnalyzer:
    """第一个窗口正常、第二个窗口不可判读（两数 null）——用于走真实流水线。"""

    name = "scripted"
    version = "test-1"
    prompt_version = "headup-strict-v0.2"

    def __init__(self) -> None:
        self.calls = 0

    def analyze(self, image_path):
        del image_path
        self.calls += 1
        if self.calls == 1:
            counts = make_counts(visible_count=10, head_up_count=9, head_down_count=1,
                                 unknown_count=0, confidence=0.9, strict=True)
            return FrameReading(head_up=9, head_down=1, unknown=0, legible_ratio=1.0,
                                confidence=0.9, analysis_notes=[], raw="{}", counts=counts)
        counts = make_counts(visible_count=0, head_up_count=0, head_down_count=0,
                             unknown_count=0, confidence=0.0, strict=False)
        return FrameReading(head_up=0, head_down=0, unknown=0, legible_ratio=0.0,
                            confidence=0.0, analysis_notes=["不可判读"], raw="{}", counts=counts)


class PipelineOutcomeUsesNullSamplePointTest(unittest.TestCase):
    """前置事实（缺陷的起因）：抽帧失败的窗口在 payload 里 `sampleAtMs` 就是 null。"""

    def test_failed_extraction_window_has_null_sample_at(self) -> None:
        from headup.frames import FramePlan
        from headup.pipeline import WindowOutcome, _window_dict

        plan = FramePlan(window_index=1, window_id="win-002", window_start_ms=10_000,
                         window_end_ms=20_000, sample_at_ms=15_000)
        failed = WindowOutcome(plan=plan, quality="LOW_QUALITY",
                               quality_reason="FRAME_EXTRACTION_FAILED")
        row = _window_dict(failed, "COUNTS")
        self.assertIsNone(row["sampleAtMs"], "没有采样点就是 null")
        self.assertEqual((row["startMs"], row["endMs"]), (10_000, 20_000),
                         "窗口边界仍可用于定位")


@unittest.skipUnless(HAS_MEDIA, "缺少 ffmpeg 或测试媒体")
class EndToEndSamplePersistenceTest(unittest.TestCase):
    """端到端（真实流水线 + 真实落库）：一个窗口抽帧失败时，**全部**样本都要留档。"""

    def _payload_with_failed_window(self):
        """用真实 `_process_window()` 造出一个抽帧失败的窗口（monkeypatch 抽帧函数）。"""
        from unittest import mock

        from headup.frames import ExtractedFrame, FrameExtractionError, FramePlan
        from headup.pipeline import _process_window, assemble_payload
        from headup.media import FfmpegTools
        from headup.video.sampling import SamplingPolicy, plan_frame_plans

        root = temp_root("defect-e2e")
        cfg = cfg_for_review(work_dir=str(root / "work"))
        request = make_request(client_request_id="req-null-sample")
        plans = plan_frame_plans(SamplingPolicy(window_ms=10_000), start_ms=0, end_ms=20_000)
        good_frame = root / "frame-000015000.jpg"
        good_frame.write_bytes(b"not-a-real-jpeg-but-the-analyzer-ignores-it")
        tools = FfmpegTools.locate(cfg)
        reports: list[dict] = []
        analyzer = FailingWindowAnalyzer()

        def fake_extract(_tools, _media, window_plans, *, out_dir, max_width, cfg):
            plan = window_plans[0]
            if plan.window_id == "win-001":
                raise FrameExtractionError(
                    plan, wire.TaskError("DECODE_FAILED", "模拟抽帧失败"))
            return [ExtractedFrame(plan=plan, path=good_frame)]

        with mock.patch("headup.pipeline.extract_planned_frames", fake_extract):
            first = _process_window(tools, SYNTHETIC_MEDIA, plans[0], root, cfg, analyzer,
                                    reports)
            second = _process_window(tools, SYNTHETIC_MEDIA, plans[1], root, cfg, analyzer,
                                     reports)
        self.assertEqual(first.quality_reason, "FRAME_EXTRACTION_FAILED")
        self.assertEqual(second.quality, "VALID")
        payload = assemble_payload(request, [first, second], cfg)
        return root, cfg, payload

    def test_service_persists_all_windows_including_the_failed_one(self) -> None:
        from headup import perf
        from headup.providers import ProviderResult

        root, _cfg, payload = self._payload_with_failed_window()
        self.assertIsNone(payload["windows"][0]["sampleAtMs"], "缺陷前提：该窗没有采样点")
        store = Store(str(root / "db.sqlite3"))
        service = JobService(_cfg, store)
        metrics = perf.RunMetrics()
        result = ProviderResult(payload=payload, stats={}, fixture_observations=False)
        service._persist_samples({"job_id": "job-null-sample", "client_request_id":
                                  "req-null-sample", "class_session_id": "s1",
                                  "media_id": "m1"}, result, metrics)

        rows = store.samples_of("job-null-sample")
        self.assertEqual([row["window_id"] for row in rows], ["win-001", "win-002"],
                         "抽帧失败的窗口也必须留档，且不得拖累其它窗口")
        self.assertIsNone(rows[0]["timestamp_ms"])
        self.assertEqual(rows[0]["quality_reason"], "FRAME_EXTRACTION_FAILED")
        self.assertEqual(rows[1]["timestamp_ms"], 15_000)
        self.assertEqual(metrics.notes.get("samplesWritten"), 2)
        self.assertNotIn("samplesPersistFailed", metrics.notes)


class FixtureLowQualitySampleTest(unittest.TestCase):
    """合成 provider 的 LOW_QUALITY 窗口 `sampleAtMs` 就是 null —— 这是套件里真实触发过缺陷的路径。

    （`FixtureProvider` 对 `quality == LOW_QUALITY` 的窗口写 `sampleAtMs: null`，
    与流水线对"抽帧失败"的处理一致；修复前这条路径会让该任务的样本**全部**丢失。）
    """

    def test_demo_fixture_low_quality_window_keeps_its_sample_row(self) -> None:
        from headup import definitions
        from headup.providers import FixtureProvider, ProviderResult

        cfg = Config(enable_fixture_provider=True,
                     definition_id=definitions.DEFINITION_ID_DEMO,
                     definition_text=definitions.DEFINITION_TEXT_DEMO,
                     work_dir=str(temp_root("defect-fixture") / "work"))
        request = wire.parse_job_request(
            demo_request_doc(), idempotency_key="req-head-up-001",
            allowed_definition_ids=(definitions.DEFINITION_ID_DEMO,),
            max_duration_ms=cfg.max_duration_ms, max_windows=cfg.max_windows)
        result: ProviderResult = FixtureProvider(cfg).run(request, source_url=None)
        windows = result.payload["windows"]
        null_sample = [row for row in windows if row["sampleAtMs"] is None]
        self.assertTrue(null_sample, "演示形状里必须存在没有采样点的 LOW_QUALITY 窗口（缺陷前提）")
        self.assertTrue(all(row["quality"] == "LOW_QUALITY" for row in null_sample))

        root = temp_root("defect-fixture-db")
        store = Store(str(root / "db.sqlite3"))
        JobService(cfg, store)._persist_samples(
            {"job_id": "job-demo-001", "client_request_id": "req-head-up-001",
             "class_session_id": "session-demo-001", "media_id": "media-demo-001"},
            result)
        stored = store.samples_of("job-demo-001")
        self.assertEqual(len(stored), len(windows), "每个窗口都要有一条审计样本")
        by_id = {row["window_id"]: row for row in stored}
        for row in null_sample:
            self.assertIsNone(by_id[row["id"]]["timestamp_ms"])
            self.assertEqual(by_id[row["id"]]["quality"], "LOW_QUALITY")


def _metrics():
    from headup import perf

    return perf.RunMetrics()


if __name__ == "__main__":  # pragma: no cover
    unittest.main()

